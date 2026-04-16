```
  ╔══════════════════════════════════════════════════════════╗
  ║                                                          ║
  ║   ♫  S P O T I B O T                                   ║
  ║                                                          ║
  ║   Self-hosted Discord music bot                          ║
  ║   Java 21 · LavaPlayer · yt-dlp · Docker-ready          ║
  ║                                                          ║
  ╚══════════════════════════════════════════════════════════╝
```

> Play music in your Discord server from YouTube, Spotify, or a plain search term.
> Spotibot downloads audio on demand, queues it locally, and streams it via LavaPlayer.
> No third-party music API subscriptions required.

---

## Table of Contents

- [How It Works](#how-it-works)
- [Features](#features)
- [Commands](#commands)
- [Requirements](#requirements)
- [Quick Start — Windows](#quick-start--windows)
- [Quick Start — Docker](#quick-start--docker)
- [Docker Compose (Dockge / Portainer)](#docker-compose-dockge--portainer)
- [Configuration](#configuration)
- [YouTube Cookie Authentication](#youtube-cookie-authentication)
- [Spotify Support](#spotify-support)
- [Blackjack](#blackjack)
- [Troubleshooting](#troubleshooting)
- [Changelog](#changelog)
- [License](#license)

---

## How It Works

### Why download instead of stream directly?

YouTube actively blocks direct audio stream requests from bots. Their player uses obfuscated JavaScript to generate signed, short-lived stream URLs — and they throttle or reject anything that doesn't look like a real browser session. Attempting to pipe a raw HTTPS audio stream into Discord fails reliably.

Spotibot sidesteps this by using **yt-dlp**, which runs YouTube's own player JavaScript (via an embedded Deno runtime) to decrypt and resolve the stream URL, then downloads the audio file to disk. Once the file is local, LavaPlayer reads it cleanly with no dependency on YouTube's servers during playback. This approach is far more reliable than direct streaming and handles age-gating, throttling, and format selection automatically.

### Request flow

```
  User types !play <query or URL>
          │
          ▼
  CommandHandler detects source type
  ┌─────────────────────────────────────────────────┐
  │  Spotify track    →  resolve title via API       │
  │  Spotify playlist →  resolve all titles via API  │
  │  YouTube URL      →  pass URL directly           │
  │  YouTube playlist →  enumerate URLs via yt-dlp   │
  │  Plain text       →  use as yt-dlp search term   │
  └─────────────────────────────────────────────────┘
          │
          ▼
  DownloadQueueHandler
  · Sends "🔍 Searching for `title`..." immediately
  · Checks local cache — if file exists, skip download
  · yt-dlp downloads best audio format to disk
  · Edits message → "📍 Queued: title" when ready
          │
          ▼
  LavaPlayer reads the local file and streams into voice
  · TrackScheduler manages the per-guild playback queue
  · Now Playing embed with interactive buttons (⏩ ⏹️ 📝)
  · Auto-disconnects when queue empties and no downloads pending
```

### File storage and lifecycle

Each Discord server gets its own folder under the config directory, named by guild ID:

```
config/
├── config.json
├── spotifyconfig.json   (optional)
├── cookies.txt          (optional)
└── downloads/
    ├── 123456789012345678/  ← Guild A
    │   ├── never_gonna_give_you_up.webm
    │   └── bohemian_rhapsody.webm
    └── 987654321098765432/  ← Guild B
        └── some_other_song.webm
```

Files are named by sanitizing the search query or URL (non-alphanumeric characters replaced with `_`) and saved as `.webm` (best available audio format selected by yt-dlp).

**Cache behavior** — if the sanitized filename already exists on disk, Spotibot skips the download entirely and queues the cached file immediately. This means replaying a song that was recently played is instant.

**Automatic deletion** — files are deleted as soon as they finish playing. The TrackScheduler deletes the previous track's file each time it advances to the next one, so disk usage stays low even during long sessions.

**On `!stop`** — the entire guild folder is wiped (`clearDownloadsFolder`), the playback queue is cleared, in-flight downloads are cancelled, and the bot disconnects. The folder is recreated fresh on the next `!play`.

### Download queue and permissions

Downloads are processed through a single shared thread pool with a `LinkedBlockingQueue`. This means:

- Multiple simultaneous `!play` commands queue up safely — no race conditions
- A 180-second per-download timeout prevents a stuck yt-dlp from blocking the queue forever
- The bot stays in the voice channel if downloads are still pending even after the current track finishes — it only leaves when both the playback queue and the pending download counter reach zero

**Folder permissions** — the config directory must be writable by the process running Spotibot. On Docker, the mounted volume is writable by default. On Windows, run the bot as a user with write access to the config folder (the default works out of the box). No elevated/admin permissions are needed.

---

## Features

- **Multi-source playback** — YouTube URLs, YouTube playlists, Spotify tracks, Spotify playlists, or plain search queries
- **Per-guild isolation** — independent queues, players, and state for every server
- **Interactive embeds** — Now Playing card with skip, stop, and queue buttons
- **Paginated queue** — `!queue` shows tracks page-by-page with prev/next navigation
- **Playback controls** — pause, resume, volume, skip, stop
- **Local audio cache** — downloaded files persist in `config/<guild-id>/` to avoid re-downloading
- **YouTube cookie support** — bypass age-gating by mounting your cookies file
- **Spotify resolution** — converts Spotify links to search terms and fetches via yt-dlp
- **Blackjack mini-game** — per-guild card game with persistent chip balances
- **Docker-first** — multi-stage Dockerfile, auto-updates yt-dlp on start, health check included

---

## Commands

### Music

| Command | Description |
|---|---|
| `!play <query or URL>` | Join your voice channel and play or queue audio |
| `!pause` | Pause the current track |
| `!resume` | Resume a paused track |
| `!volume` | Show current volume |
| `!volume <0–100>` | Set volume |
| `!np` / `!nowplaying` | Show the current track embed |
| `!skip` | Skip the current track |
| `!stop` | Stop playback, clear the queue, and disconnect |
| `!queue` | Show the current queue (paginated) |
| `!help` | Show the help message |

**Supported `!play` inputs:**

```
!play never gonna give you up
!play https://www.youtube.com/watch?v=dQw4w9WgXcQ
!play https://www.youtube.com/playlist?list=PLxxxxxxx
!play https://open.spotify.com/track/xxxxxxx
!play https://open.spotify.com/playlist/xxxxxxx
```

### Blackjack

| Command | Description |
|---|---|
| `!blackjack` | Start or join a blackjack table |
| `!blackjack help` | Show blackjack rules and commands |
| `!hit` | Draw a card |
| `!stand` | End your turn |
| `!double` | Double your bet and draw one card |
| `!split` | Split a matching pair into two hands |
| `!quit` | Leave the current game |
| `!ledger` | View your chip history |
| `!funds <amount> @user` | *(Admin)* Give chips to a user |

---

## Requirements

| Tool | Version | Notes |
|---|---|---|
| Java JDK | 21 (LTS) | [Temurin](https://adoptium.net/) recommended |
| Maven | 3.9+ | Build tool |
| yt-dlp | latest | Audio downloader |
| ffmpeg | any recent | Audio processing (required by yt-dlp) |
| Docker | 20+ | Optional — for containerised deployment |

---

## Quick Start — Windows

### 1. Install prerequisites

Using [Chocolatey](https://chocolatey.org/) (run as Administrator):

```powershell
choco install openjdk21 maven yt-dlp ffmpeg -y
```

Or install manually:
- **Java 21** — https://adoptium.net/
- **Maven** — https://maven.apache.org/download.cgi
- **yt-dlp** — https://github.com/yt-dlp/yt-dlp/releases (add to PATH)
- **ffmpeg** — https://ffmpeg.org/download.html (add to PATH)

### 2. Clone and build

```powershell
git clone https://github.com/<your-username>/spotibot.git
cd spotibot
mvn clean package -DskipTests
```

### 3. Configure

Create `config\config.json`:

```json
{
  "bot_token": "YOUR_BOT_TOKEN_HERE",
  "status": "Streaming tunes",
  "default_volume": 75
}
```

See [Configuration](#configuration) for all options.

### 4. Run

```powershell
java -jar target\spotibot-1.1-SNAPSHOT.jar
```

If you have multiple Java versions installed, specify the path explicitly:

```powershell
"C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe" -jar target\spotibot-1.1-SNAPSHOT.jar
```

### Autostart on Windows boot

The repo includes `SpotibotStart.ps1`. To run it at login:
1. Press `Win + R`, type `shell:startup`
2. Place a shortcut to `SpotibotStart.ps1` in that folder (or use the included `.lnk`)

---

## Quick Start — Docker

### Build and run

```bash
docker build -t spotibot .

docker run -d \
  --name spotibot \
  --restart unless-stopped \
  -e BOT_TOKEN="your-discord-bot-token" \
  -e DEFAULT_VOLUME=75 \
  -v $(pwd)/config:/app/config \
  spotibot
```

All environment variables generate `config.json` on first start if one does not exist.

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `BOT_TOKEN` | *(required)* | Your Discord bot token |
| `STATUS` | `Streaming tunes` | Bot status text |
| `DEFAULT_VOLUME` | `75` | Startup volume (0–100) |
| `NOW_PLAYING` | `🎶 **Now Playing:** {title}` | Now Playing format string |
| `QUEUED` | `📍 **{index}. {title}**` | Queue entry format string |
| `SKIP_EMOJI` | `⏩` | Skip button emoji |
| `STOP_EMOJI` | `⏹️` | Stop button emoji |
| `QUEUE_EMOJI` | `📝` | Queue button emoji |
| `TZ` | `America/Los_Angeles` | Container timezone |

---

## Docker Compose (Dockge / Portainer)

Copy the block below into a `docker-compose.yml` (or paste directly into Dockge/Portainer).

```yaml
services:
  spotibot:
    build:
      context: .
    # — or pull from GHCR if you publish a package:
    # image: ghcr.io/<your-username>/spotibot:latest
    container_name: spotibot
    restart: unless-stopped
    environment:
      BOT_TOKEN: ${BOT_TOKEN}
      STATUS: ${STATUS:-Streaming tunes}
      DEFAULT_VOLUME: ${DEFAULT_VOLUME:-75}
      TZ: America/Los_Angeles
    volumes:
      - ./config:/app/config:rw
    healthcheck:
      test: ["CMD-SHELL", "pgrep -f 'java .*app.jar' >/dev/null"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 30s
```

Create a `.env` file next to `docker-compose.yml`:

```env
BOT_TOKEN=your-discord-bot-token-here
DEFAULT_VOLUME=75
STATUS=Streaming tunes
```

Then start with:

```bash
docker compose up -d
```

### Dockge tip

In Dockge, paste the compose block into a new stack. Set the environment variables in the **Env** tab instead of using a `.env` file — Dockge stores them securely and injects them at runtime.

The `/app/config` volume will be created automatically on the host at the path Dockge assigns for the stack. Your downloaded audio cache, `config.json`, and `cookies.txt` all live there.

---

## Configuration

`config/config.json` is the only required file. If it is absent and you are running Docker, it is generated from environment variables on startup.

```jsonc
{
  // Required
  "bot_token": "YOUR_BOT_TOKEN_HERE",

  // Optional — bot presence text
  "status": "Streaming tunes",

  // Optional — startup volume (0–100)
  "default_volume": 75,

  // Optional — message format for now-playing and queue entries
  // {title} and {index} are substituted at runtime
  "queue_format": {
    "now_playing": "🎶 **Now Playing:** {title}",
    "queued":      "📍 **{index}. {title}**"
  },

  // Optional — emoji overrides for the interactive buttons
  "emojis": {
    "skip":  "⏩",
    "stop":  "⏹️",
    "queue": "📝"
  }
}
```

### File layout inside the config volume

```
config/
├── config.json          ← bot configuration (required)
├── spotifyconfig.json   ← Spotify credentials (optional, enables Spotify URLs)
├── cookies.txt          ← YouTube cookies (optional, see below)
└── downloads/
    └── <guild-id>/      ← downloaded audio cache per server
        ├── song_title.webm
        └── ...
```

---

## YouTube Cookie Authentication

Some YouTube videos require a signed-in session (age-restricted content, region locks). Spotibot automatically passes a `cookies.txt` file to yt-dlp if it finds one in the config directory.

### Export cookies from your browser

1. Install a cookie export extension:
   - Chrome/Edge: [Get cookies.txt LOCALLY](https://chrome.google.com/webstore/detail/get-cookiestxt-locally/cclelndahbckbenkjhflpdbgdldlbecc)
   - Firefox: [cookies.txt](https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/)
2. Open [https://www.youtube.com](https://www.youtube.com) while signed in
3. Click the extension and export — save the file as `cookies.txt`
4. Place it in your config directory:
   - **Windows:** next to `config.json`
   - **Docker:** inside the mounted `/app/config` volume

Spotibot picks it up automatically on the next `!play` command. No restart needed.

---

## Spotify Support

Spotibot resolves Spotify links by fetching track metadata via the Spotify Web API, then searching YouTube for the audio.

### Setup

1. Go to [https://developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Log in → **Create app** — name and description can be anything; set the redirect URI to `http://localhost`
3. Open the app → **Settings** → copy **Client ID** and **Client Secret**
4. Create a **separate** file called `spotifyconfig.json` in your config directory (next to `config.json`):

```json
{
  "client_id": "YOUR_CLIENT_ID",
  "client_secret": "YOUR_CLIENT_SECRET"
}
```

Spotibot uses the [Client Credentials flow](https://developer.spotify.com/documentation/web-api/tutorials/client-credentials-flow) — machine-to-machine, no user login or OAuth callback needed. The token is fetched automatically on first use and refreshed before it expires.

Without `spotifyconfig.json`, Spotify URLs will return an error. YouTube URLs and plain search terms always work without Spotify credentials.

---

## Blackjack

Spotibot includes a full multi-player blackjack game per guild.

```
  ┌─────────────────────────────────────────┐
  │  !blackjack      — join / start table   │
  │  !hit            — draw a card          │
  │  !stand          — end your turn        │
  │  !double         — 2× bet, one card     │
  │  !split          — split a pair         │
  │  !quit           — leave the table      │
  │  !ledger         — chip history         │
  │  !funds <n> @u   — (admin) give chips   │
  └─────────────────────────────────────────┘
```

- Chip balances are saved to disk and persist across restarts
- Each guild has its own isolated table and game thread
- Admins (server owner, Administrator, Manage Server, or `Bot Admin` role) can issue chips with `!funds`

---

## Troubleshooting

**Bot does not join voice channel**
- You must be in a voice channel before typing `!play`
- Ensure the bot has the **Connect** and **Speak** permissions in that channel

**`yt-dlp: command not found`**
- Add yt-dlp to your PATH, or install it system-wide
- Docker builds install it automatically

**`ffmpeg: command not found`**
- ffmpeg is required by yt-dlp for audio conversion
- Docker builds install it automatically

**Music cuts out / bot disconnects**
- Check the container/process logs for LavaPlayer errors
- Some live streams are unsupported

**Spotify link returns an error**
- Confirm `spotify_client_id` and `spotify_client_secret` are set in `config.json`
- The Spotify API token is fetched fresh each session — no manual refresh needed

**Age-restricted YouTube video fails**
- Export and place a `cookies.txt` as described in [YouTube Cookie Authentication](#youtube-cookie-authentication)

**Multiple Java versions — bot won't start**
- Specify the JDK 21 binary explicitly:
  ```powershell
  & "C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe" -jar target\spotibot-1.1-SNAPSHOT.jar
  ```

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

---

## License

MIT — see [LICENSE](LICENSE) for details.
