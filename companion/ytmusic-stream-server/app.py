"""
ytmusic-stream-server — tiny FastAPI service that bridges Jellyfin Music
Discovery to YouTube Music for on-demand audio streaming.

Endpoints:
  GET /health              -> {ok:true}
  GET /search?artist=&title=
                           -> {videoId, title, artist, durationSeconds, thumbnail}
  GET /stream?artist=&title=
                           -> 302 redirect to a direct audio URL (or 502 on failure)
  GET /stream/by-video/{videoId}
                           -> 302 redirect to a direct audio URL for that video

Notes:
  - YouTube audio URLs are signed and short-lived (~6 hours), but the redirect
    is ad-hoc so each play resolves a fresh URL.
  - We prefer YouTube Music search results because they're audio-first.
"""

from __future__ import annotations
import asyncio
import logging
import os
import re
import time
from typing import Optional

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import RedirectResponse, JSONResponse, StreamingResponse, Response
from ytmusicapi import YTMusic
import httpx
import yt_dlp

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("ytmusic-stream-server")

app = FastAPI(title="ytmusic-stream-server", version="0.1.0")

# A single shared YTMusic client — instantiation is non-trivial.
_yt: Optional[YTMusic] = None
def yt() -> YTMusic:
    global _yt
    if _yt is None:
        _yt = YTMusic()
    return _yt

# yt-dlp config — pull the best audio-only stream URL, no download.
# Leaving player_client at default lets yt-dlp pick whichever YT client surface
# exposes audio-only formats. The CLI default works; explicit overrides hide
# audio-only formats on some client surfaces.
# Prefer m4a (AAC) over webm (Opus) — Safari/iOS only reliably plays m4a/aac.
# Other browsers (Chrome/Firefox) support both, so this preference order works
# universally. Final fallback to "best" handles videos with weird format sets.
# metzfam: YouTube cookies (spotibot's export, filtered to YouTube/Google only, private copy because yt-dlp rewrites it)
COOKIE_SRC, COOKIE_FILE = "/cookies-src/cookies.txt", "/data/cookies.txt"
def _refresh_cookies():
    try:
        if os.path.exists(COOKIE_FILE) and os.path.getmtime(COOKIE_FILE) >= os.path.getmtime(COOKIE_SRC):
            return
        keep = []
        for line in open(COOKIE_SRC, encoding="utf-8", errors="replace"):
            dom = line.split("\t", 1)[0].replace("#HttpOnly_", "").lstrip(".")
            if line.startswith("# Netscape") or dom.endswith(("youtube.com", "google.com")):
                keep.append(line)
        fd = os.open(COOKIE_FILE + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("# Netscape HTTP Cookie File\n" if not keep or not keep[0].startswith("# Netscape") else "")
            f.writelines(keep)
        os.replace(COOKIE_FILE + ".tmp", COOKIE_FILE)
    except OSError as ex:
        log.warning("cookies: %s", ex)

def _opts(base: dict) -> dict:
    """yt-dlp options with fresh cookies (if spotibot's export is mounted)."""
    _refresh_cookies()
    o = dict(base)
    # metzfam 2026-11-05: PO tokens from the bgutil-pot sidecar (audio-only formats 403 without one)
    o["extractor_args"] = {"youtube": {"fetch_pot": ["always"]},
                         "youtubepot-bgutilhttp": {"base_url": [os.environ.get("POT_URL", "http://bgutil-pot:4416")]}}
    if os.path.exists(COOKIE_FILE):
        o["cookiefile"] = COOKIE_FILE
    return o

YDL_OPTS = ({
    "format": "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best",
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
})

# Video selector — prefer high-quality H.264 DASH up to 1080p. yt-dlp
# returns two URLs (video + audio) for DASH; we mux them with ffmpeg
# `-c copy` (no re-encode, just container repack) to a single MP4 that
# serves seekably from disk.
#
# Format priority:
#   bestvideo[h264, <=1080] + bestaudio[aac]   — DASH 1080p, our target
#   22                                          — legacy 720p progressive (rare)
#   18                                          — 360p progressive fallback
#
# We ALWAYS pick avc1/H.264 over VP9 because iOS Safari refuses VP9 and
# our muxer-copy can't transcode codecs without burning Pi CPU.
YDL_VIDEO_OPTS = ({
    "format": (
        "bestvideo[height<=1080][vcodec^=avc1]+bestaudio[acodec^=mp4a]/"
        "bestvideo[height<=720][vcodec^=avc1]+bestaudio[acodec^=mp4a]/"
        "22/18/best[ext=mp4]"
    ),
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "noplaylist": True,
})

# metzfam 2026-11-06: one YouTube client resolves ~4.4 s vs ~6.7 s for yt-dlp's multi-client default
FAST_CLIENT = os.environ.get("YT_FAST_CLIENT", "android_vr")

_url_cache: dict[str, tuple[float, str]] = {}
# Note: video URLs aren't cached in-memory anymore — we cache the muxed
# MP4 file on disk instead, which gives both URL persistence and fast
# subsequent plays in one mechanism.

def _expire_from_yt_url(url: str) -> Optional[int]:
    """YouTube embeds an `expire=<unix>` query param in signed audio URLs."""
    import urllib.parse as up
    try:
        q = up.urlparse(url).query
        for kv in q.split("&"):
            if kv.startswith("expire="):
                return int(kv.split("=", 1)[1])
    except Exception:
        pass
    return None



def _wait_available(info) -> None:
    """metzfam: with cookies YouTube hands out URLs that 403 until `available_at` (yt-dlp's
    "Sleeping N seconds as required by the site"). Wait it out (+0.5 s) before anyone fetches."""
    try:
        wait = float((info or {}).get("available_at") or 0) - time.time() + 0.5
        if wait > 0:
            time.sleep(min(wait, 10))
    except (TypeError, ValueError):
        pass

_dur_cache: dict[str, Optional[float]] = {}

_avail_cache: dict[str, float] = {}

def get_audio_info(video_id: str, force: bool = False) -> Optional[tuple[str, Optional[float]]]:
    # metzfam: retry (force) after an early 403 — the URL we already have just isn't live yet.
    # Wait for its available_at instead of paying for a whole new yt-dlp resolve (~5 s + another wait).
    if force:
        avail, cached = _avail_cache.get(video_id), _url_cache.get(video_id)
        if avail and cached and time.time() < avail + 60:
            _wait_available({"available_at": avail})
            return cached[1], _dur_cache.get(video_id)
    # metzfam 2026-11-06: first try = fast single client, no ad-timer wait (measured 15/15 OK with PO tokens);
    # the retry after a 403 uses yt-dlp's default clients and waits for available_at.
    url = get_audio_url(video_id, force, wait=force)
    return (url, _dur_cache.get(video_id)) if url else None

def get_audio_url(video_id: str, force: bool = False, wait: bool = True) -> Optional[str]:
    """Resolve a YouTube videoId to a direct audio stream URL via yt-dlp."""
    now = time.time()
    if not force:
        cached = _url_cache.get(video_id)
        # Cache hit only if the URL still has a comfortable lifetime.
        # YouTube signed URLs are ~6h but they can be invalidated earlier; we
        # honor the embedded expire and pad 5 minutes for clock skew.
        if cached and cached[0] > now + 300:
            return cached[1]
    url = f"https://music.youtube.com/watch?v={video_id}"
    opts = _opts(YDL_OPTS)
    if not force and FAST_CLIENT:
        opts["extractor_args"] = {**opts["extractor_args"], "youtube": {**opts["extractor_args"]["youtube"], "player_client": [FAST_CLIENT]}}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if wait:  # metzfam: usually unnecessary; _produce only waits on its retry
            _wait_available(info)
        audio_url = info.get("url") if info else None
        if not audio_url and info and info.get("requested_formats"):
            audio_url = info["requested_formats"][0].get("url")
        if audio_url:
            expire = _expire_from_yt_url(audio_url) or (int(now) + 60 * 30)
            _url_cache[video_id] = (expire, audio_url)
            # metzfam: the signed URL carries the exact duration (dur=216.013); yt-dlp's is rounded to whole seconds,
            # and the size estimate built on it padded every in-progress song with ~1 s of silence at the end.
            m_dur = re.search(r"[?&]dur=([0-9.]+)", audio_url)
            _dur_cache[video_id] = float(m_dur.group(1)) if m_dur else info.get("duration")
            if info.get("available_at"):
                _avail_cache[video_id] = float(info["available_at"])
        return audio_url
    except Exception as ex:
        log.warning("yt-dlp failed for %s: %s", video_id, ex)
        return None


def get_video_urls(video_id: str) -> Optional[tuple[str, Optional[str]]]:
    """Resolve a YouTube videoId to (video_url, audio_url_or_None) via
    yt-dlp. For DASH formats (the 720p+ path) we get two URLs and mux
    them server-side. For progressive formats (the 22/18 fallback) only
    the video URL is set — it already contains audio."""
    url = f"https://www.youtube.com/watch?v={video_id}"
    try:
        with yt_dlp.YoutubeDL(_opts(YDL_VIDEO_OPTS)) as ydl:
            info = ydl.extract_info(url, download=False)
        _wait_available(info)
        if not info:
            return None
        # DASH path: yt-dlp picked separate streams.
        rf = info.get("requested_formats")
        if rf and len(rf) >= 2:
            v_url = rf[0].get("url")
            a_url = rf[1].get("url")
            if v_url and a_url:
                return (v_url, a_url)
        # Progressive single-URL path.
        single = info.get("url")
        if single:
            return (single, None)
        return None
    except Exception as ex:
        log.warning("yt-dlp video failed for %s: %s", video_id, ex)
        return None


def find_artist_videos(artist: str, limit: int = 20) -> list:
    """
    Search YouTube Music for music videos by an artist. Returns a list of
    {videoId, title, artist, thumbnail, durationSeconds} dicts, scored to
    prefer official music videos (artist name match, "Official" in title).
    """
    if not artist:
        return []
    try:
        results = yt().search(artist, filter="videos", limit=max(limit * 3, 30))
    except Exception as ex:
        log.warning("ytmusicapi videos search failed: %s", ex)
        return []
    if not results:
        return []

    artist_l = artist.lower()

    def score(r: dict) -> int:
        s = 0
        rt = (r.get("title") or "").lower()
        # Boost the things that look like real official MVs over random
        # uploads / lyric videos / cover bands.
        if "official" in rt: s += 30
        if any(k in rt for k in ("music video", "official video", "official mv")): s += 20
        if "lyric" in rt: s -= 10
        if any(k in rt for k in ("cover", "remake", "tribute", "karaoke")): s -= 30
        for a in r.get("artists") or []:
            an = (a.get("name") or "").lower()
            if an == artist_l: s += 50
            elif artist_l and artist_l in an: s += 20
        return s

    results.sort(key=score, reverse=True)
    out = []
    for r in results[:limit]:
        vid = r.get("videoId")
        if not vid:
            continue
        out.append({
            "videoId": vid,
            "title": r.get("title"),
            "artist": ", ".join(a.get("name") for a in (r.get("artists") or []) if a.get("name")) or artist,
            "thumbnail": _normalize_thumbnail(r),
            "durationSeconds": r.get("duration_seconds"),
        })
    return out


def best_match(artist: str, title: str) -> Optional[dict]:
    """Search YT Music for `artist - title` and pick the best song result."""
    if not title:
        return None
    query = f"{artist} {title}".strip() if artist else title
    try:
        results = yt().search(query, filter="songs", limit=8)
    except Exception as ex:
        log.warning("ytmusicapi search failed: %s", ex)
        return None
    if not results:
        return None

    title_l = title.lower()
    artist_l = (artist or "").lower()

    def score(r: dict) -> int:
        s = 0
        rt = (r.get("title") or "").lower()
        if rt == title_l: s += 50
        if title_l in rt: s += 20
        for a in r.get("artists") or []:
            an = (a.get("name") or "").lower()
            if artist_l and an == artist_l: s += 40
            elif artist_l and artist_l in an: s += 15
        return s

    results.sort(key=score, reverse=True)
    top = results[0]
    return {
        "videoId": top.get("videoId"),
        "title": top.get("title"),
        "artist": ", ".join(a.get("name") for a in (top.get("artists") or []) if a.get("name")),
        "durationSeconds": top.get("duration_seconds"),
        "thumbnail": (top.get("thumbnails") or [{}])[-1].get("url"),
    }


@app.get("/health")
def health():
    return {"ok": True, "service": "ytmusic-stream-server", "version": "0.1.0"}


@app.get("/search")
def search(artist: str = Query(""), title: str = Query(...)):
    match = best_match(artist, title)
    if not match or not match.get("videoId"):
        raise HTTPException(status_code=404, detail="No match found.")
    return match


# ---------------------------------------------------------------------
# metzfam: fast-start streaming.
#   - Cache keyed by videoId (was: signed-URL id), and artist/title -> videoId
#     memoized in sqlite, so a replay never touches YouTube.
#   - First play streams the MP3 while ffmpeg is still writing it, with an
#     estimated Content-Length (CBR 192k, zero-padded / capped to match).
#   - Producer/consumer coordinate through the cache dir only, so it works
#     across uvicorn workers and with /prefetch.
#   - Oldest files evicted once the cache passes CACHE_MAX_GB.
# ---------------------------------------------------------------------
import sqlite3

CACHE_DIR = os.getenv("CACHE_DIR", "/cache")
os.makedirs(CACHE_DIR, exist_ok=True)
CACHE_MAX_BYTES = int(float(os.getenv("CACHE_MAX_GB", "5")) * 1024**3)
MP3_BPS = 192000 // 8
MATCH_DB = os.path.join(os.getenv("HOME", "/data"), "matches.sqlite")
_prefetch_sem = asyncio.Semaphore(3)
_producing: set[str] = set()


# metzfam 2026-11-06: volume levelling. Every song is loudness-normalised (EBU R128, -14 LUFS like the big
# streaming services) while it is transcoded, so quiet and loud songs play at about the same volume.
# Already-cached songs are levelled by a slow background job (one at a time, low priority).
LOUDNORM = os.environ.get("LOUDNORM", "1") not in ("0", "false", "off")
LOUDNORM_ARGS = ["-af", "loudnorm=I=-14:TP=-1.5:LRA=11", "-ar", "44100"] if LOUDNORM else []


def _mark_normalized(vid: str) -> None:
    try:
        with _db() as c:
            c.execute("INSERT OR REPLACE INTO norm VALUES (?)", (vid,))
    except sqlite3.Error as ex:
        log.warning("norm db: %s", ex)


async def _normalize_backlog() -> None:
    """Level the loudness of songs cached before volume levelling existed."""
    await asyncio.sleep(60)
    while LOUDNORM:
        try:
            with _db() as c:
                done = {r[0] for r in c.execute("SELECT vid FROM norm")}
            todo = [f[:-4] for f in os.listdir(CACHE_DIR) if f.endswith(".mp3") and "." not in f[:-4] and f[:-4] not in done]
        except Exception as ex:
            log.warning("normalize backlog: %s", ex)
            todo = []
        if not todo:
            await asyncio.sleep(3600)
            continue
        log.info("levelling volume of %d cached songs in the background", len(todo))
        for vid in todo:
            final, part, _ = _paths(vid)
            if vid in _producing or not os.path.exists(final):
                continue
            tmp = final + ".norm.mp3"
            proc = await asyncio.create_subprocess_exec(
                "nice", "-n", "19", "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", final,
                *LOUDNORM_ARGS, "-c:a", "libmp3lame", "-b:a", "192k", "-write_xing", "0", "-f", "mp3", tmp,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
            _, err = await proc.communicate()
            if proc.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 0:
                os.replace(tmp, final)
                _mark_normalized(vid)
            else:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                _mark_normalized(vid)  # don't retry a broken file forever
                log.warning("level %s failed: %s", vid, err.decode(errors="replace")[:200])
            await asyncio.sleep(1)


@app.on_event("startup")
async def _start_backlog():
    asyncio.create_task(_normalize_backlog())


def _db():
    c = sqlite3.connect(MATCH_DB, timeout=5)
    c.execute("CREATE TABLE IF NOT EXISTS m (k TEXT PRIMARY KEY, vid TEXT, ts REAL)")
    c.execute("CREATE TABLE IF NOT EXISTS norm (vid TEXT PRIMARY KEY)")
    return c


def match_video_id(artist: str, title: str) -> Optional[str]:
    """best_match() memoized for 30 days."""
    k = f"{artist.strip().lower()}\x1f{title.strip().lower()}"
    try:
        with _db() as c:
            row = c.execute("SELECT vid, ts FROM m WHERE k=?", (k,)).fetchone()
        if row and row[1] > time.time() - 30 * 86400:
            return row[0]
    except sqlite3.Error as ex:
        log.warning("match db: %s", ex)
    match = best_match(artist, title)
    vid = match.get("videoId") if match else None
    if vid:
        try:
            with _db() as c:
                c.execute("INSERT OR REPLACE INTO m VALUES (?,?,?)", (k, vid, time.time()))
        except sqlite3.Error as ex:
            log.warning("match db: %s", ex)
    return vid


def _paths(video_id: str) -> tuple[str, str, str]:
    base = os.path.join(CACHE_DIR, f"{video_id}.mp3")
    return base, base + ".part", base + ".len"


def _evict() -> None:
    try:
        files = []
        for e in os.scandir(CACHE_DIR):
            if e.is_file() and not e.name.endswith((".part", ".len")):
                st = e.stat()
                files.append((st.st_mtime, st.st_size, e.path))
        total = sum(f[1] for f in files)
        for _, size, path in sorted(files):
            if total <= CACHE_MAX_BYTES:
                break
            os.remove(path)
            total -= size
            log.info("evicted %s", os.path.basename(path))
    except OSError as ex:
        log.warning("evict: %s", ex)


async def _produce(video_id: str) -> None:
    """Resolve + transcode video_id into the cache. Safe to call repeatedly."""
    final, part, lenf = _paths(video_id)
    if os.path.exists(final) or video_id in _producing:
        return
    try:
        if time.time() - os.path.getmtime(part) < 60:
            return  # another worker is on it
        os.remove(part)  # stale leftover from a crash/restart
    except OSError:
        pass
    _producing.add(video_id)
    try:
        for attempt in (0, 1):
            info = await asyncio.to_thread(get_audio_info, video_id, attempt == 1)
            if not info:
                break
            audio_url, duration = info
            # 192 kbps CBR = 24000 B/s + ~1.2-1.6 KB of header/encoder frames (measured); off by <10 ms either way.
            est = int((duration or 600) * MP3_BPS) + 1400
            with open(lenf + ".tmp", "w") as f:
                f.write(str(est))
            os.replace(lenf + ".tmp", lenf)
            open(part, "wb").close()
            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-hide_banner", "-loglevel", "warning", "-i", audio_url,
                "-vn", *LOUDNORM_ARGS, "-c:a", "libmp3lame", "-b:a", "192k", "-write_xing", "0",
                "-f", "mp3", "-y", part,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
            )
            _, err = await proc.communicate()
            if proc.returncode == 0 and os.path.exists(part) and os.path.getsize(part) > 0:
                os.replace(part, final)
                if LOUDNORM_ARGS:
                    _mark_normalized(video_id)
                log.info("cached %s (%d KB)", video_id, os.path.getsize(final) // 1024)
                await asyncio.to_thread(_evict)
                return
            log.warning("ffmpeg failed for %s (attempt %d): %s", video_id, attempt, err.decode(errors="replace")[:300])
            # Only retry if nobody has started reading; else readers would see a reset.
            if os.path.exists(part) and os.path.getsize(part) > 0:
                break
        for p in (part, lenf):
            try:
                os.remove(p)
            except OSError:
                pass
    finally:
        _producing.discard(video_id)


def _parse_range(h: Optional[str]) -> tuple[int, Optional[int]]:
    if h and h.startswith("bytes="):
        a, _, b = h[6:].split(",")[0].partition("-")
        if a.strip():
            return int(a), (int(b) if b.strip() else None)
    return 0, None


async def _serve_video(video_id: str, request: Request):
    from fastapi.responses import FileResponse
    final, part, lenf = _paths(video_id)
    if os.path.exists(final):
        os.utime(final)  # LRU touch
        return FileResponse(final, media_type="audio/mpeg", filename=os.path.basename(final))

    asyncio.create_task(_produce(video_id))
    # Wait for the producer to publish the length estimate + part file.
    deadline = time.time() + 45
    fd = None
    while time.time() < deadline:
        if os.path.exists(final):
            return FileResponse(final, media_type="audio/mpeg", filename=os.path.basename(final))
        try:
            fd = open(part, "rb")
            est = int(open(lenf).read())
            break
        except (OSError, ValueError):
            if fd:
                fd.close()
                fd = None
        await asyncio.sleep(0.1)
    if fd is None:
        raise HTTPException(status_code=502, detail="Could not start audio.")

    start, end = _parse_range(request.headers.get("range"))
    end = est - 1 if end is None else min(end, est - 1)
    if start >= est:
        fd.close()
        return Response(status_code=416, headers={"content-range": f"bytes */{est}"})
    length = end - start + 1

    async def body():
        # The open fd follows the inode through os.replace(part -> final).
        try:
            pos, sent, idle = start, 0, 0.0
            while sent < length:
                fd.seek(pos)
                chunk = fd.read(min(65536, length - sent))
                if chunk:
                    pos += len(chunk); sent += len(chunk); idle = 0.0
                    yield chunk
                    continue
                if os.path.exists(final) or (not os.path.exists(part) and video_id not in _producing and idle > 2):
                    break  # producer finished (or died): fall through to padding
                idle += 0.05
                if idle > 60:
                    break
                await asyncio.sleep(0.05)
            # Estimate was high: zero padding is skipped by MP3 decoders.
            while sent < length:
                n = min(65536, length - sent)
                sent += n
                yield b"\0" * n
        finally:
            fd.close()

    headers = {"accept-ranges": "bytes", "content-length": str(length)}
    status = 200
    if request.headers.get("range"):
        status = 206
        headers["content-range"] = f"bytes {start}-{end}/{est}"
    if request.method == "HEAD":
        fd.close()
        return Response(status_code=status, headers=headers, media_type="audio/mpeg")
    return StreamingResponse(body(), status_code=status, headers=headers, media_type="audio/mpeg")


@app.api_route("/stream", methods=["GET", "HEAD"])
async def stream(request: Request, artist: str = Query(""), title: str = Query(...)):
    vid = await asyncio.to_thread(match_video_id, artist, title)
    if not vid:
        raise HTTPException(status_code=404, detail="No YT Music match.")
    return await _serve_video(vid, request)


@app.get("/download")
async def download(artist: str = Query(""), title: str = Query(...), album: str = Query(""),
                   track: str = Query(""), year: str = Query("")):
    """metzfam: complete, tagged MP3 for saving into the library (fallback when Soulseek has nothing)."""
    from fastapi.responses import FileResponse
    from starlette.background import BackgroundTask
    vid = await asyncio.to_thread(match_video_id, artist, title)
    if not vid:
        raise HTTPException(status_code=404, detail="No YT Music match.")
    final, _, _ = _paths(vid)
    if not os.path.exists(final):
        asyncio.create_task(_produce(vid))
        deadline = time.time() + 300
        while not os.path.exists(final):
            if time.time() > deadline:
                raise HTTPException(status_code=504, detail="Download timed out.")
            await asyncio.sleep(0.5)
    out = f"{final}.dl-{os.getpid()}-{int(time.time() * 1000)}.mp3"
    meta = {"title": title, "artist": artist, "album_artist": artist, "album": album or title, "track": track, "date": year}
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", final, "-map", "0:a", "-c", "copy",
           "-map_metadata", "-1", "-id3v2_version", "3"]
    for k, v in meta.items():
        if v:
            cmd += ["-metadata", f"{k}={v}"]
    cmd.append(out)
    proc = await asyncio.create_subprocess_exec(*cmd, stderr=asyncio.subprocess.PIPE)
    _, err = await proc.communicate()
    if proc.returncode != 0 or not os.path.exists(out):
        log.warning("download tag failed for %s: %s", vid, err.decode(errors="replace")[:300])
        raise HTTPException(status_code=500, detail="Tagging failed.")
    log.info("download %s -> %s - %s", vid, artist, title)
    return FileResponse(out, media_type="audio/mpeg", headers={"x-video-id": vid},
                        background=BackgroundTask(os.remove, out))


@app.api_route("/stream/by-video/{video_id}", methods=["GET", "HEAD"])
async def stream_by_video(video_id: str, request: Request):
    return await _serve_video(video_id, request)


@app.post("/prefetch")
async def prefetch(request: Request):
    """Body: {"tracks": [{"artist": .., "title": ..}, ...]} — warm the cache in order."""
    tracks = (await request.json()).get("tracks") or []

    async def run():
        for t in tracks[:10]:
            async with _prefetch_sem:
                vid = await asyncio.to_thread(match_video_id, t.get("artist") or "", t.get("title") or "")
                if vid:
                    await _produce(vid)

    asyncio.create_task(run())
    return JSONResponse({"queued": min(len(tracks), 10)}, status_code=202)


# =====================================================================
# /search/videos — search YouTube Music for an artist's videos.
# Used by the Jellyfin plugin to populate per-artist Music Video tabs.
# =====================================================================
@app.get("/search/videos")
def search_videos(artist: str = Query(..., min_length=1), limit: int = Query(20, ge=1, le=50)):
    videos = find_artist_videos(artist, limit=limit)
    return {"artist": artist, "count": len(videos), "videos": videos}


# =====================================================================
# /video — resolve an artist+title to a YouTube music-video URL and
# 302-redirect the client to YouTube's CDN. No proxying through the Pi
# — saves bandwidth, and YouTube's edge caches handle range requests
# better than we ever could. The signed URL is good for ~6h, which is
# fine since the player connects once at start of playback.
# =====================================================================
@app.api_route("/video", methods=["GET", "HEAD"])
async def video(request: Request, artist: str = Query(""), title: str = Query(...)):
    match = best_match(artist, title)
    if not match or not match.get("videoId"):
        # Fallback: search videos directly (catches things YT Music's "songs"
        # filter excludes — concert footage, vlogs that are still music-video-
        # shaped uploads, etc.)
        videos = find_artist_videos(f"{artist} {title}", limit=1)
        if not videos:
            raise HTTPException(status_code=404, detail="no video match")
        video_id = videos[0].get("videoId")
    else:
        video_id = match["videoId"]
    return await _video_redirect(video_id, request)


@app.api_route("/video/by-id/{video_id}", methods=["GET", "HEAD"])
async def video_by_id(video_id: str, request: Request):
    return await _video_redirect(video_id, request)


def _video_cache_path(video_id: str) -> str:
    return os.path.join(CACHE_DIR, f"video-{video_id}.mp4")


async def _video_redirect(video_id: str, request: Request):
    """
    Stream a fragmented MP4 directly from ffmpeg's stdout. ffmpeg copies
    DASH video+audio streams (no re-encode) into fragmented MP4 fragments
    that browsers can play as they arrive. First-byte latency is ~1-3s
    rather than the ~2 minutes a full-disk-mux would take to complete.

    Trade-offs vs. the disk-cache approach:
      ✗ no seek bar — fragmented MP4 served as a stream isn't seekable in
        most browsers (the moov is at the start but byte ranges into a
        live producer don't work)
      ✗ no cache — subsequent plays re-mux from scratch
      ✓ playback starts almost immediately

    `frag_keyframe+empty_moov` makes ffmpeg write a streamable MP4 (moov
    upfront, video data in fragments) instead of the default "moov at end"
    layout that forces a full-file write before the client sees any bytes.
    """
    if request.method == "HEAD":
        return Response(status_code=200, headers={"accept-ranges": "none", "content-type": "video/mp4"})

    urls = get_video_urls(video_id)
    if urls is None:
        raise HTTPException(status_code=502, detail="yt-dlp could not resolve video URL")
    v_url, a_url = urls
    log.info("/video stream vid=%s dash=%s", video_id, a_url is not None)

    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error"]
    if a_url:
        cmd += ["-i", v_url, "-i", a_url, "-map", "0:v:0", "-map", "1:a:0"]
    else:
        cmd += ["-i", v_url]
    cmd += [
        "-c", "copy",
        "-movflags", "frag_keyframe+empty_moov+default_base_moof",
        "-f", "mp4",
        "pipe:1",
    ]

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    async def stream_bytes():
        try:
            while True:
                chunk = await proc.stdout.read(64 * 1024)
                if not chunk:
                    break
                yield chunk
        finally:
            if proc.returncode is None:
                try: proc.kill()
                except ProcessLookupError: pass
            try:
                err = (await proc.stderr.read()).decode(errors="replace")[:500]
                if err:
                    log.debug("ffmpeg stderr for %s: %s", video_id, err)
            except Exception: pass

    return StreamingResponse(stream_bytes(), media_type="video/mp4",
                             headers={"accept-ranges": "none"})


# =====================================================================
# /discover/playlists — curated playlists for the Jellyfin plugin's
# DiscoveryPlaylistFeed. Cached in memory for ~6 hours; refresh is
# triggered by the plugin via a cache-bust query param.
# =====================================================================

_discover_cache: dict = {"sections": [], "expires": 0.0}
_DISCOVER_TTL_SEC = 6 * 60 * 60


def _normalize_thumbnail(t: dict) -> Optional[str]:
    """Pull the largest thumbnail URL from a YT Music item."""
    thumbs = t.get("thumbnails") or []
    if not thumbs:
        return None
    return thumbs[-1].get("url")


def _normalize_artists(item: dict) -> str:
    """Join YT Music artist list ['name'] entries with comma."""
    artists = item.get("artists") or []
    names = [a.get("name") for a in artists if a.get("name")]
    return ", ".join(names)


def _track_payload(t: dict) -> Optional[dict]:
    """Convert a YT Music track-shaped dict into our discovery-track JSON.
    Wrapped in try/except so a single malformed entry doesn't kill the
    whole section (some chart entries from non-US regions have unusual
    shapes that confuse ytmusicapi's parser)."""
    try:
        vid = t.get("videoId")
        title = t.get("title")
        if not vid or not title:
            return None
        album = t.get("album")
        album_name = album.get("name") if isinstance(album, dict) else None
        return {
            "videoId": vid,
            "title": title,
            "artist": _normalize_artists(t),
            "album": album_name,
            "durationSeconds": t.get("duration_seconds"),
            "thumbnail": _normalize_thumbnail(t),
        }
    except Exception as ex:
        log.debug("_track_payload skipped malformed entry: %s", ex)
        return None


def _normalize_playlist_tracks(playlist: dict, limit: int = 100) -> list:
    out = []
    for t in (playlist.get("tracks") or [])[:limit]:
        p = _track_payload(t)
        if p:
            out.append(p)
    return out


def _normalize_chart_tracks(items: list, limit: int = 50) -> list:
    out = []
    for t in items[:limit]:
        p = _track_payload(t)
        if p:
            out.append(p)
    return out


# Spec: each entry is (section_key, display_title, sub_label, mood_keyword).
# We match mood_keyword case-insensitively against YT Music's mood category
# titles, then take the first playlist in that mood as the section's content.
# Note: YT Music's actual mood-category names (verified via get_mood_categories):
#   Chill, Commute, Energize, Feel good, Focus, Gaming, Party, Romance,
#   Sad, Sleep, Workout, African, Arabic, ..., J-Pop, K-Pop, Pop, Rock, ...
_MOOD_PICKS = [
    ("mood-workout",         "YT 🏋️ Workout",          "Pumped-up YT Music mix",   "workout"),
    ("mood-party",           "YT 🎉 Party",             "Crowd-pleasers",            "party"),
    ("mood-commute",         "YT 🚗 Commute",           "Tunes for the drive",       "commute"),
    # "Energy Boosters" in the user's request → YT Music's category is "Energize".
    ("mood-energy",          "YT ⚡ Energy Boosters",    "High-energy picks",         "energize"),
    ("mood-feelgood",        "YT 😊 Feel Good",          "Upbeat & cheery",           "feel good"),
    # J-Pop is a YT Music mood category, but ytmusicapi 1.8.2 has a parser
    # bug ('musicTwoRowItemRenderer') for genre-mood playlists. Pulled via
    # search instead — see _SEARCH_PICKS below.
]

# Hot Hits genre playlists — fetched via YT Music search for the exact phrase.
# YT Music has dedicated "Hot Hits {genre}" curated playlists that update weekly.
_HOT_HITS = [
    ("hothits-pop",        "YT 🎤 Hot Hits Pop",         "Hot Hits Pop"),
    ("hothits-rock",       "YT 🎸 Hot Hits Rock",        "Hot Hits Rock"),
    ("hothits-emo",        "YT 🖤 Hot Hits Emo",         "Hot Hits Emo"),
    ("hothits-electronic", "YT 🎧 Hot Hits Electronic",  "Hot Hits Electronic"),
]


async def _yt_chart_section(section_key: str, title: str, sub_label: str, country: str, limit: int = 50) -> Optional[dict]:
    """Pull country-specific top songs. JP and other non-US regions sometimes
    return a slightly different shape from the US chart — we walk known
    fallbacks rather than assuming `songs.items`."""
    try:
        charts = await asyncio.to_thread(yt().get_charts, country=country)
    except Exception as ex:
        log.warning("get_charts(country=%s) raised: %s", country, ex)
        return None
    if not charts:
        return None

    # Try several shapes ytmusicapi has used historically.
    candidates = []
    songs = charts.get("songs")
    if isinstance(songs, dict):
        candidates.append(songs.get("items"))
    elif isinstance(songs, list):
        candidates.append(songs)
    # Some regions surface only 'trending' or 'videos' instead of 'songs'.
    for k in ("trending", "videos", "topVideos"):
        v = charts.get(k)
        if isinstance(v, dict):
            candidates.append(v.get("items"))
        elif isinstance(v, list):
            candidates.append(v)

    items = next((c for c in candidates if c), None)
    if not items:
        log.warning("chart %s: no items found in any known shape (keys=%s)",
                    country, list(charts.keys()))
        return None

    tracks = _normalize_chart_tracks(items, limit)
    if not tracks:
        return None
    return {"section": section_key, "title": title, "subLabel": sub_label, "tracks": tracks}


async def _yt_search_playlist_section(section_key: str, title: str, query: str, limit: int = 100) -> Optional[dict]:
    """Search YT Music for a curated playlist by name, return its tracks."""
    try:
        results = await asyncio.to_thread(yt().search, query, filter="playlists", limit=5)
        if not results:
            return None
        # Pick the first playlist result. Some results have browseId, some
        # have playlistId — accept either.
        first = results[0]
        pid = first.get("browseId") or first.get("playlistId")
        if not pid:
            return None
        # browseId comes prefixed with 'VL' for playlists; strip if present.
        if pid.startswith("VL"):
            pid = pid[2:]
        playlist = await asyncio.to_thread(yt().get_playlist, pid, limit)
        tracks = _normalize_playlist_tracks(playlist, limit)
        if not tracks:
            return None
        return {
            "section": section_key,
            "title": title,
            "subLabel": playlist.get("title", query),
            "tracks": tracks,
        }
    except Exception as ex:
        log.warning("search-playlist '%s' failed: %s", query, ex)
        return None


async def _yt_mood_section(section_key: str, title: str, sub_label: str, mood_keyword: str, limit: int = 80) -> Optional[dict]:
    """Find a YT Music mood category whose title contains `mood_keyword`,
    take the first playlist in it, return its tracks."""
    try:
        mood_cats = await asyncio.to_thread(yt().get_mood_categories)
        target_params = None
        target_title = None
        # mood_cats is a dict like {'Moods & moments': [{'title': 'Workout', 'params': '...'}, ...], ...}
        for group_items in mood_cats.values():
            for item in (group_items or []):
                if mood_keyword.lower() in (item.get("title") or "").lower():
                    target_params = item.get("params")
                    target_title = item.get("title")
                    break
            if target_params:
                break
        if not target_params:
            # Help diagnose missing matches by dumping what categories ARE there.
            available = []
            for items in mood_cats.values():
                for it in (items or []):
                    if it.get("title"):
                        available.append(it["title"])
            log.warning("mood %s: no category matched keyword '%s'. Available: %s",
                        section_key, mood_keyword, available)
            return None
        log.info("mood %s: matched '%s'", section_key, target_title)

        mood_pls = await asyncio.to_thread(yt().get_mood_playlists, target_params)
        if not mood_pls:
            return None
        pid = mood_pls[0].get("playlistId")
        if not pid:
            return None
        playlist = await asyncio.to_thread(yt().get_playlist, pid, limit)
        tracks = _normalize_playlist_tracks(playlist, limit)
        if not tracks:
            return None
        return {
            "section": section_key,
            "title": title,
            "subLabel": f"{sub_label} — {mood_pls[0].get('title','')}",
            "tracks": tracks,
        }
    except Exception as ex:
        log.warning("mood %s failed: %s", section_key, ex)
        return None


async def _build_discover() -> dict:
    """Fetch all sections, ignoring failures so partial successes still ship."""
    log.info("[/discover/playlists] building fresh snapshot")

    coros = []
    # Mood-based picks (J-Pop now comes from the mood "J-Pop" category —
    # ytmusicapi 1.8.2's get_charts(country='JP') is broken with
    # "list index out of range").
    for key, title, sub, mood_kw in _MOOD_PICKS:
        coros.append(_yt_mood_section(key, title, sub, mood_kw, 80))
    # 3. Hot Hits genre playlists
    for key, title, query in _HOT_HITS:
        coros.append(_yt_search_playlist_section(key, title, query, 100))
    # 4. Forgotten Favorites
    coros.append(_yt_search_playlist_section(
        "forgotten-favorites", "YT 💭 Forgotten Favorites", "Forgotten Favorites", 100))
    # 5. New Releases
    coros.append(_yt_search_playlist_section(
        "new-releases-yt", "YT 🆕 New Releases", "New Releases", 100))
    # 6. J-Pop (via search since the mood path is broken in ytmusicapi)
    coros.append(_yt_search_playlist_section(
        "jpop-yt", "YT 🇯🇵 J-Pop", "J-Pop Hits", 100))

    results = await asyncio.gather(*coros, return_exceptions=False)
    sections = [r for r in results if r is not None]
    log.info("[/discover/playlists] built %d sections", len(sections))
    return {"sections": sections}


# metzfam: search suggestions for the Jellyfin web UI's dropdown.
_suggest_cache: dict[str, tuple[float, list]] = {}

@app.get("/catalog/search")
async def catalog_search(q: str = Query(..., min_length=1, max_length=120), limit: int = Query(5, ge=1, le=20)):
    """metzfam: top song matches (Spotibot /search picker)."""
    try:
        res = await asyncio.to_thread(yt().search, q, filter="songs", limit=max(limit, 10))
    except Exception as ex:
        log.info("search %r: %s", q, ex)
        return []
    out = []
    for r in res or []:
        if not r.get("videoId"):
            continue
        out.append({"videoId": r["videoId"], "title": r.get("title") or "", "artist": _normalize_artists(r),
                    "duration": r.get("duration") or ""})
        if len(out) >= limit:
            break
    return out


@app.get("/catalog/suggest")
async def catalog_suggest(q: str = Query("", max_length=80)):
    q = q.strip()
    if not q:
        return []
    key = q.lower()
    hit = _suggest_cache.get(key)
    if hit and hit[0] > time.time():
        return hit[1]
    try:
        out = await asyncio.to_thread(yt().get_search_suggestions, q)
    except Exception as ex:
        log.info("suggest %r: %s", q, ex)
        return []
    out = [s for s in out if isinstance(s, str)][:8]
    _suggest_cache[key] = (time.time() + 3600, out)
    if len(_suggest_cache) > 5000:
        _suggest_cache.clear()
    return out


# metzfam: song radio (YouTube Music's "Start radio"), used by the plugin's Instant Mix.
_radio_cache: dict[str, tuple[float, list]] = {}

def _secs(v) -> Optional[int]:
    try:
        n = 0
        for part in str(v).split(":"):
            n = n * 60 + int(part)
        return n or None
    except (TypeError, ValueError):
        return None

def _radio_tracks(video_id: str, limit: int) -> list:
    w = yt().get_watch_playlist(videoId=video_id, radio=True, limit=limit)
    out, seen = [], set()
    for t in w.get("tracks") or []:
        p = _track_payload(t)
        if not p or p["videoId"] in seen:
            continue
        seen.add(p["videoId"])
        if not p.get("durationSeconds"):
            p["durationSeconds"] = _secs(t.get("length"))
        if not p.get("thumbnail") and t.get("thumbnail"):
            p["thumbnail"] = _normalize_thumbnail({"thumbnails": t["thumbnail"]})
        out.append(p)
    return out

_album_cache: dict = {}

def _norm(x: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (x or "").lower()).strip()

def _album_tracks(artist: str, album: str) -> Optional[dict]:
    """metzfam: album track list from YT Music (fallback when Deezer refuses)."""
    res = yt().search(f"{artist} {album}", filter="albums", limit=10) or []
    na, nb = _norm(album), _norm(artist)
    def score(r):
        t = _norm(r.get("title")); arts = " ".join(_norm(a.get("name")) for a in r.get("artists") or [])
        return (3 if t == na else 2 if na and (na in t or t in na) else 0) + (2 if nb and (nb in arts or arts in nb) else 0)
    res = sorted((r for r in res if r.get("browseId")), key=score, reverse=True)
    if not res or score(res[0]) < 3:
        return None
    a = yt().get_album(res[0]["browseId"])
    tracks = []
    for i, t in enumerate(a.get("tracks") or [], 1):
        if not t.get("title"):
            continue
        tracks.append({
            "title": t["title"],
            "artist": ", ".join(x.get("name") for x in (t.get("artists") or []) if x.get("name")) or artist,
            "videoId": t.get("videoId"),
            "trackNumber": t.get("trackNumber") or i,
            "durationSeconds": t.get("duration_seconds") or _secs(t.get("duration")),
        })
    return {"title": a.get("title"), "browseId": res[0]["browseId"], "tracks": tracks}


@app.get("/catalog/album")
async def catalog_album(artist: str = Query(""), album: str = Query(...)):
    key = f"{_norm(artist)}|{_norm(album)}"
    hit = _album_cache.get(key)
    if hit and hit[0] > time.time():
        return hit[1]
    try:
        r = await asyncio.to_thread(_album_tracks, artist, album)
    except Exception as ex:
        log.warning("album %s - %s: %s", artist, album, ex)
        raise HTTPException(status_code=502, detail="Album lookup failed.")
    if not r or not r["tracks"]:
        raise HTTPException(status_code=404, detail="No album match.")
    _album_cache[key] = (time.time() + 6 * 3600, r)
    return r


@app.get("/catalog/radio")
async def catalog_radio(videoId: str = Query(""), artist: str = Query(""), title: str = Query(""),
                        limit: int = Query(50, ge=5, le=100)):
    """Tracks for a radio seeded by a song (videoId, or artist+title we match first)."""
    vid = videoId or (await asyncio.to_thread(match_video_id, artist, title) if title else None)
    if not vid:
        raise HTTPException(status_code=404, detail="No seed match.")
    key = f"{vid}:{limit}"
    hit = _radio_cache.get(key)
    if hit and hit[0] > time.time():
        return {"seed": vid, "tracks": hit[1]}
    try:
        tracks = await asyncio.to_thread(_radio_tracks, vid, limit)
    except Exception as ex:
        log.warning("radio %s: %s", vid, ex)
        raise HTTPException(status_code=502, detail="Radio unavailable.")
    _radio_cache[key] = (time.time() + 1800, tracks)
    return {"seed": vid, "tracks": tracks}


@app.get("/discover/playlists")
async def discover_playlists(refresh: bool = Query(False)):
    now = time.time()
    if refresh or now > _discover_cache["expires"] or not _discover_cache["sections"]:
        snap = await _build_discover()
        _discover_cache["sections"] = snap["sections"]
        _discover_cache["expires"] = now + _DISCOVER_TTL_SEC
    return {"sections": _discover_cache["sections"]}
