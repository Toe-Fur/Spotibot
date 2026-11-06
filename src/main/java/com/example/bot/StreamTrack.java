package com.example.bot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * metzfam: a song request resolved against the ytmusic-stream-server (same backend as the Jellyfin
 * music plugin). Instead of downloading a whole file with yt-dlp, the bot streams the server's MP3,
 * which starts as soon as the first bytes are transcoded and is cached for replays.
 */
public final class StreamTrack {
    private static final Logger logger = LoggerFactory.getLogger(StreamTrack.class);

    /** Separator between title and artist in Spotify-derived requests. */
    public static final String SEP = "\u001F";

    /** ytmusic-stream-server base URL. Unset = classic mode (download each song with yt-dlp). */
    public static final String BASE = envOr("STREAM_SERVER_URL", "");

    private static final Pattern VIDEO_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // uvicorn drops POST bodies on an h2c upgrade
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public final String title;
    public final String artist;
    public final String videoId;
    public String display;
    /** Best-known song name/artist (filled from oEmbed for links) for Save and Radio. */
    public volatile String songTitle;
    public volatile String songArtist;
    /** Queued by radio autoplay (shown in the panel). */
    public boolean fromRadio;
    /** Who asked for it (vote skip: the requester can skip their own song; history). */
    public volatile long requesterId;
    public volatile String requesterName;
    /** Stable id for queue actions (move / remove) from the panel. */
    public final long uid = UIDS.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicLong UIDS = new java.util.concurrent.atomic.AtomicLong();

    public record SearchHit(String videoId, String title, String artist, String duration) {
        public String label() {
            String l = title + (artist == null || artist.isBlank() ? "" : " - " + artist) + (duration == null || duration.isBlank() ? "" : "  (" + duration + ")");
            return l.length() > 100 ? l.substring(0, 97) + "..." : l;
        }
    }

    /**
     * Top matches for /search: YouTube Music songs via the stream server, or (classic mode) a yt-dlp search.
     */
    public static List<SearchHit> search(String q, int limit) {
        List<SearchHit> out = new java.util.ArrayList<>();
        if (q == null || q.isBlank()) return out;
        try {
            if (enabled()) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/catalog/search?limit=" + limit + "&q=" + enc(q)))
                        .timeout(Duration.ofSeconds(15)).build();
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    JSONArray arr = new JSONArray(r.body());
                    for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (o.optString("videoId").isBlank()) continue;
                        out.add(new SearchHit(o.getString("videoId"), o.optString("title"), o.optString("artist"), o.optString("duration")));
                    }
                    return out;
                }
            }
            // classic mode (or the stream server has no /catalog/search): yt-dlp
            Process p = Tools.withPath(new ProcessBuilder(Tools.ytdlp(), "--flat-playlist", "--no-warnings", "--print", "%(id)s\t%(title)s\t%(channel)s\t%(duration_string)s",
                    "ytsearch" + limit + ":" + q)).redirectErrorStream(false).start();
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 2 && f[0].length() == 11) out.add(new SearchHit(f[0], f[1], f.length > 2 ? f[2].replaceAll(" - Topic$", "") : "", f.length > 3 && !"NA".equals(f[3]) ? f[3] : ""));
                }
            }
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("search failed: {}", e.toString());
        }
        return out;
    }

    public static StreamTrack fromHit(SearchHit h) {
        StreamTrack t = new StreamTrack(h.title(), h.artist(), h.videoId(), h.artist() == null || h.artist().isBlank() ? h.title() : h.title() + " - " + h.artist());
        return t;
    }

    private StreamTrack(String title, String artist, String videoId, String display) {
        this.title = title;
        this.artist = artist;
        this.videoId = videoId;
        this.display = display;
        this.songTitle = title;
        this.songArtist = artist;
    }

    public static boolean enabled() {
        return !BASE.isEmpty();
    }

    /** yt-dlp target for classic (download) mode. */
    public String legacyQuery() {
        if (videoId != null) return "https://www.youtube.com/watch?v=" + videoId;
        return "ytsearch:" + (title + " " + artist).trim();
    }

    /**
     * Songs similar to `seed` (YouTube Music radio, via the stream server). Skips the seed itself
     * and anything in `exclude` (video ids already played/queued).
     */
    public static List<StreamTrack> radio(StreamTrack seed, int limit, java.util.Set<String> exclude) {
        List<StreamTrack> out = new java.util.ArrayList<>();
        if (!enabled() || seed == null) return out;
        try {
            String q = seed.videoId != null ? "videoId=" + enc(seed.videoId)
                    : "artist=" + enc(seed.songArtist) + "&title=" + enc(seed.songTitle);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/catalog/radio?limit=" + Math.max(5, limit + 5) + "&" + q))
                    .timeout(Duration.ofSeconds(30)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return out;
            JSONObject j = new JSONObject(r.body());
            String seedVid = j.optString("seed", "");
            JSONArray arr = j.optJSONArray("tracks");
            for (int i = 0; arr != null && i < arr.length() && out.size() < limit; i++) {
                JSONObject t = arr.getJSONObject(i);
                String vid = t.optString("videoId", "");
                if (vid.isEmpty() || vid.equals(seedVid) || exclude.contains(vid)) continue;
                String title = t.optString("title", "").trim();
                String artist = t.optString("artist", "").trim();
                StreamTrack st = new StreamTrack(title, artist, vid, artist.isEmpty() ? title : title + " - " + artist);
                st.fromRadio = true;
                out.add(st);
            }
        } catch (Exception e) {
            logger.warn("radio failed: {}", e.toString());
        }
        return out;
    }

    /** Typed suggestions for slash-command autocomplete (YouTube Music, popularity ordered). */
    public static List<String> suggest(String q) {
        if (!enabled() || q == null || q.isBlank()) return List.of();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/catalog/suggest?q=" + enc(q)))
                    .timeout(Duration.ofMillis(2000)).build();
            JSONArray arr = new JSONArray(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
            List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static StreamTrack parse(String input) {
        String s = input.replace("ytsearch:", "").trim();
        Matcher m = VIDEO_ID.matcher(s);
        if ((s.contains("youtube.com") || s.contains("youtu.be")) && m.find()) {
            return new StreamTrack("", "", m.group(1), s);
        }
        int i = s.indexOf(SEP);
        if (i >= 0) {
            String title = s.substring(0, i).trim();
            String artist = s.substring(i + 1).trim();
            return new StreamTrack(title, artist, null, title + " - " + artist);
        }
        return new StreamTrack(s, "", null, s);
    }

    public String streamUrl() {
        if (videoId != null) return BASE + "/stream/by-video/" + videoId;
        return BASE + "/stream?artist=" + enc(artist) + "&title=" + enc(title);
    }

    /**
     * Blocks until the server has resolved the song and started producing audio (HEAD returns once
     * the first bytes exist). Lavaplayer's own HTTP probe then connects instantly.
     */
    public boolean warm() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(streamUrl()))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(120))
                    .build();
            int code = HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 400) logger.warn("stream-server {} for {}", code, display);
            return code < 400;
        } catch (Exception e) {
            logger.warn("stream-server HEAD failed for {}: {}", display, e.toString());
            return false;
        }
    }

    /** For a YouTube link, swap the raw URL for the real video title (YouTube oEmbed, no auth). */
    public void fetchTitleIfLink() {
        if (videoId == null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://www.youtube.com/oembed?format=json&url=" + enc("https://www.youtube.com/watch?v=" + videoId)))
                    .timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JSONObject j = new JSONObject(r.body());
                String t = j.optString("title", "");
                String a = j.optString("author_name", "").replaceAll(" - Topic$", "");
                if (!t.isBlank()) {
                    display = a.isBlank() || t.contains(a) ? t : t + " - " + a;
                    songTitle = t.replaceAll("(?i)\\s*[\\(\\[](official|lyric|audio|music video|video|hd|4k)[^\\)\\]]*[\\)\\]]", "").trim();
                    songArtist = a;
                }
            }
        } catch (Exception e) {
            logger.debug("oEmbed failed for {}: {}", videoId, e.toString());
        }
    }

    /** Ask the server to warm the cache for upcoming songs (fire and forget, like the Jellyfin plugin's look-ahead). */
    public static void prefetch(List<StreamTrack> upcoming) {
        JSONArray arr = new JSONArray();
        for (StreamTrack t : upcoming) {
            if (t.videoId != null) continue; // /prefetch works on artist/title
            arr.put(new JSONObject().put("artist", t.artist).put("title", t.title));
        }
        if (arr.isEmpty()) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/prefetch"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("tracks", arr).toString()))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> { logger.debug("prefetch failed: {}", e.toString()); return null; });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v.replaceAll("/+$", "");
    }
}
