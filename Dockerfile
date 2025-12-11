# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
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
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=America/Los_Angeles
COPY --from=build /build/app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl ca-certificates ffmpeg python3 && \
    rm -rf /var/lib/apt/lists/* && \
    curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod +x /usr/local/bin/yt-dlp
VOLUME ["/app/config"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD pgrep -f 'java .*app.jar' >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
