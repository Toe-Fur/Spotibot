# Changelog

All notable changes to this project will be documented in this file.

---

## [2.0] — 2026-11-06

The big one: one live queue message with buttons, voice control (Gaming mode), radio, Jellyfin playlists,
slash commands, and an optional streaming mode that starts songs in seconds. Everything new beyond the
basics is optional; with no extra setup the bot works exactly like before (classic mode).

### Added
- **Live queue panel** — one message per server that is edited as songs load and play, instead of a message per song.
  Shows now playing with elapsed time and a progress bar, the queue 5 songs per page, and a status line.
- **Panel buttons** — `-10s` `-5s` `Pause/Resume` `+5s` `+10s`, `Skip` `Stop` `Leave` `Prev` `Next`,
  `Shuffle` `Repeat` (off / song / queue), plus `Gaming mode`, `Radio` and `Save` when those features are set up,
  and a `Guide & updates` link to this page.
- **Gaming mode** (optional, needs a speech server) — control the bot by voice while you play:
  "Spotibot, play Africa by Toto", "Spotibot, skip", pause, resume, stop, leave, shuffle, repeat, louder, quieter,
  "what's playing", "play my Vibes playlist", "play something like this". Toggle with the panel button, `!gaming on|off` or `/gaming`.
  Speech is transcribed on your own server, in memory only; only recognised commands are logged.
- **Stays in the channel** — the bot no longer leaves when the queue ends. It leaves when dismissed (`Leave`, `!leave`,
  "Spotibot, leave"), after 30 minutes with nothing playing, or after 5 minutes alone. `Stop` now only clears the music.
- **Radio** (streaming mode) — when the queue runs out, similar songs keep playing (YouTube Music radio).
  `!radio on|off`, `!radio now` / "play something like this" queues songs like the current one right after it.
- **Jellyfin** (optional) — `!playlists`, `!playlist <name>` / `/playlist` (with autocomplete) play your Jellyfin playlists;
  `Save` / `!save` favorites the song in Jellyfin (with the JellyMusicDiscovery plugin this downloads it to your library).
- **Slash commands** — `/play` (with song suggestions as you type in streaming mode), `/playlist`, `/skip`, `/pause`,
  `/resume`, `/stop`, `/leave`, `/join`, `/queue`, `/np`, `/shuffle`, `/repeat`, `/volume`, `/radio`, `/gaming`, `/save`,
  `/settings`, `/help`. Replies are only visible to you.
- `!join`, `!leave`, `!playnext <song>` (or `/play ... next:true`), `!shuffle`, `!repeat off|song|queue`, `!settings`.
- **Per-server settings** (`!settings`, `/settings`; managers only to change) — idle timeout, tidy commands, gaming mode, radio.
  Saved in `config/guild-settings.json`.
- **Streaming mode** (optional) — set `STREAM_SERVER_URL` to a
  [ytmusic-stream-server](https://github.com/DrGitStone/JellyMusicDiscovery): songs start as soon as the first seconds
  are ready (about 5 s for a new song, instantly when cached) and the next 5 songs are pre-loaded.

### Changed
- Control commands (`!pause`, `!skip`, `!stop`, `!volume`, `!queue`, `!np`) report on the panel instead of posting replies,
  and the command message is deleted if the bot has Manage Messages (turn off with `!settings tidy off`).
- Docker image ships the standalone yt-dlp binary (bundles its own Python), so `yt-dlp -U` keeps updating.
- Classic mode retries failed downloads with fallback YouTube clients (works around YouTube's new 403s).
- Spotify tracks keep title and artist separate for better matching.

### Fixed
- Temporary cookie copies are deleted after each download (they used to pile up in `config/tmp`).
- Skipping the last song while more were still loading no longer leaves the old song playing.

---

## [1.1] — 2026-04-16

### Added
- `!pause` / `!resume` — pause and resume playback
- `!volume` / `!volume <n>` — show or set volume (0–100)
- `!np` / `!nowplaying` — re-display the Now Playing embed for the current track
- YouTube playlist support — `!play <playlist URL>` enumerates videos via `yt-dlp --flat-playlist` and queues them individually
- "Searching…" feedback — `!play` immediately responds with a searching message that edits to "📍 Queued" or an error when the download completes
- Per-guild blackjack isolation — each server now has its own `TableState`, game thread, and scheduler; games in one guild no longer affect another

### Changed
- Now Playing embed footer updated to include `!pause`
- Blackjack: all mutable game state moved from static class fields into a `ConcurrentHashMap<Long, TableState>` registry keyed by guild ID

---

## [1.0] — 2025-12-17

### Added
- Upgrade project to **Java 21 (LTS)**
  - `maven.compiler.source` / `target` set to `21`, `maven-compiler-plugin` bumped to `3.11.0`
- CI and Docker updates for Java 21
  - `Dockerfile` and `Dockerfile.bak` updated to Temurin/Java 21 images
  - `.github/workflows/maven-build.yml` updated to set up JDK 21
- Verified builds — local `mvn verify` and GitHub Actions (Docker Image CI + Build and Deploy) all passing on Java 21

### Changed
- Project version set to `1.0`, tag `v1.0`, published release
- `pom.xml` bumped to `1.1-SNAPSHOT` for continued development

### Misc
- PR #8 — `chore: upgrade to Java 21` (merged via squash)
- Notable commits: `0669f3d`, `78e4607`, `50ae958`
