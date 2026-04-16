#!/bin/sh
set -e

# ---- update yt-dlp before anything else ----
if command -v yt-dlp >/dev/null 2>&1; then
  echo "🔄 Updating yt-dlp via self-update..."
  yt-dlp -U || echo "⚠️ yt-dlp update failed, continuing"
fi
# -------------------------------------------

BOT_TOKEN="${BOT_TOKEN:-YOUR_BOT_TOKEN_HERE}"
STATUS="${STATUS:-Streaming tunes}"
DEFAULT_VOLUME="${DEFAULT_VOLUME:-75}"
NOW_PLAYING="${NOW_PLAYING:-🎶 **Now Playing:** {title}}"
QUEUED="${QUEUED:-📍 **{index}. {title}**}"
SKIP_EMOJI="${SKIP_EMOJI:-⏩}"
STOP_EMOJI="${STOP_EMOJI:-⏹️}"
QUEUE_EMOJI="${QUEUE_EMOJI:-📝}"

mkdir -p /app/config

# create config if missing/empty
if [ ! -s /app/config/config.json ]; then
  cat > /app/config/config.json <<JSON
{
  "bot_token": "${BOT_TOKEN}",
  "status": "${STATUS}",
  "default_volume": ${DEFAULT_VOLUME},
  "queue_format": {
    "now_playing": "${NOW_PLAYING}",
    "queued": "${QUEUED}"
  },
  "emojis": {
    "skip": "${SKIP_EMOJI}",
    "stop": "${STOP_EMOJI}",
    "queue": "${QUEUE_EMOJI}"
  }
}
JSON
fi

exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar
