# Changelog

All notable changes to this project will be documented in this file.

## [1.0] - 2025-12-17
### Added
- Upgrade project to **Java 21 (LTS)**
  - Set `maven.compiler.source` and `maven.compiler.target` to `21` and updated `maven-compiler-plugin` to `3.11.0`.
- CI and Docker updates to support Java 21
  - Updated `Dockerfile` and `Dockerfile.bak` to use Temurin/Java 21 images.
  - Updated `.github/workflows/maven-build.yml` to set up JDK `21` for the Build and Deploy workflow.
- Verified builds
  - Local `mvn verify` passed with Java 21.
  - GitHub Actions jobs (Docker Image CI and Build and Deploy) passed on Java 21.

### Changed
- Project version set to `1.0` (tag `v1.0`) and published release.
- Bumped `pom.xml` to `1.1-SNAPSHOT` for continued development after release.

### Misc
- PR: #8 — `chore: upgrade to Java 21` (merged via squash)
- Notable commits included in this release:
  - `0669f3d` chore: upgrade to Java 21
  - `78e4607` chore(release): 1.0
  - `50ae958` chore: bump version to 1.1-SNAPSHOT

---

## [Unreleased]
- Prepare changes for next development cycle (1.1)
