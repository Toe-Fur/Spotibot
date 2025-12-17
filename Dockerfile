# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    set -e; \
    mvn -q -DskipTests package; \
    JAR="$(ls -1 target/*.jar | grep -Ev '(sources|javadoc|original)' | head -n1)"; \
    cp "$JAR" /build/app.jar


# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ENV TZ=America/Los_Angeles

COPY --from=build /build/app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl ca-certificates ffmpeg python3 unzip && \
    rm -rf /var/lib/apt/lists/* && \
    \
    # yt-dlp
    curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
      -o /usr/local/bin/yt-dlp && \
    chmod +x /usr/local/bin/yt-dlp && \
    \
    # Deno (JS runtime for yt-dlp challenges)
    curl -L https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip \
      -o /tmp/deno.zip && \
    unzip /tmp/deno.zip -d /usr/local/bin && \
    chmod +x /usr/local/bin/deno && \
    rm -f /tmp/deno.zip

VOLUME ["/app/config"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD pgrep -f 'java .*app.jar' >/dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
