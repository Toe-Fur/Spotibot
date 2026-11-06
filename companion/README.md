# Optional companion services

Spotibot works on its own (classic mode: each song is downloaded with yt-dlp). These two small services
unlock the extra features. Run them with `docker-compose.streaming.example.yml` in the repo root.

| Service | Unlocks | Spotibot setting |
|---|---|---|
| `ytmusic-stream-server` | Streaming mode (songs start in ~5 s, instantly when cached), look-ahead pre-loading, Radio, `/play` suggestions | `STREAM_SERVER_URL=http://ytmusic-stream-server:8077` |
| `bgutil-pot` (public image) | YouTube "PO tokens" for the stream server (without it YouTube answers 403) | none (stream server finds it at `http://bgutil-pot:4416`) |
| `spotibot-stt` | Gaming mode (voice commands, speech-to-text runs locally with Whisper) | `STT_URL=http://spotibot-stt:8078` |

## ytmusic-stream-server
From [JellyMusicDiscovery](https://github.com/DrGitStone/JellyMusicDiscovery) (MIT, see `LICENSE`), with fixes:
PO tokens via the bgutil provider, a single fast YouTube client (no ad-timer wait), progressive MP3 streaming
while it transcodes, a cache keyed by video id with LRU eviction (`CACHE_MAX_GB`, default 5), `/prefetch`,
`/catalog/radio` and `/catalog/suggest`.

YouTube blocks most server IPs without a signed-in session. Export YouTube cookies (Netscape `cookies.txt`
format; only youtube.com and google.com cookies are used) and mount the file at `/cookies-src/cookies.txt`.

YouTube changes often. Rebuild the image regularly to get the newest yt-dlp:
`docker compose -f docker-compose.streaming.example.yml build --pull --no-cache ytmusic-stream-server`.

## spotibot-stt
FastAPI + [faster-whisper](https://github.com/SYSTRAN/faster-whisper) `base.en` on CPU (int8, about 1.2 s per
spoken phrase on 2 cores, ~600 MB RAM). `POST /transcribe` takes raw 16 kHz mono 16-bit PCM and returns
`{"text": ...}`. Audio is processed in memory and never stored.
