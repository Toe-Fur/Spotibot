# Spotibot v1.0 — 2025-12-17

Release highlights

- Upgrade project to **Java 21 (LTS)**
  - Set `maven.compiler.source` and `maven.compiler.target` to `21` and updated `maven-compiler-plugin` to `3.11.0`.
- CI and Docker updates to support Java 21
  - Updated `Dockerfile` and `Dockerfile.bak` to use Temurin/Java 21 images.
  - Updated `.github/workflows/maven-build.yml` to set up JDK `21` for the Build and Deploy workflow.
- Verified builds
  - Local `mvn verify` passed with Java 21.
  - GitHub Actions jobs (Docker Image CI and Build and Deploy) passed on Java 21.

Other changes

- Project version set to `1.0` and tag `v1.0` created.
- Bumped `pom.xml` to `1.1-SNAPSHOT` on `main` for further development.

Commits included

- `0669f3d` chore: upgrade to Java 21
- `78e4607` chore(release): 1.0
- `50ae958` chore: bump version to 1.1-SNAPSHOT

Artifacts

- Build artifact `Spotibot` is attached to this release (from workflow run `20297494532`).

---

Full changelog: see `CHANGELOG.md` in the repository.
