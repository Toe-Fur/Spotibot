# Changelog

All notable changes to this project will be documented in this file.

---

## [Unreleased] — 1.1

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
