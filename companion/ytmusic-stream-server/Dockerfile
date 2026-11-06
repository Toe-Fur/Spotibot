FROM denoland/deno:bin AS deno
FROM python:3.12-slim
# yt-dlp needs a JS runtime (deno) for YouTube now
COPY --from=deno /deno /usr/local/bin/deno

# Install ffmpeg (yt-dlp uses it for some streams) and curl (for the healthcheck).
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# Always use the very latest yt-dlp at build time (YouTube breaks constantly).
RUN pip install --no-cache-dir -U --pre "yt-dlp[default]" ytmusicapi bgutil-ytdlp-pot-provider

COPY app.py .

EXPOSE 8077

# ONE worker: the in-flight transcode set is per process; 2 workers raced on the same cache file.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8077", "--workers", "1"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8077/health || exit 1
