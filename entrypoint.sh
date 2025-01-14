#!/bin/bash

# Set default values if environment variables are not provided
BOT_TOKEN="${BOT_TOKEN:-YOUR_BOT_TOKEN_HERE}"
STATUS="${STATUS:-Playing music on Discord}"
DEFAULT_VOLUME="${DEFAULT_VOLUME:-60}"
NOW_PLAYING="${NOW_PLAYING:-🎶 **Now Playing:** {title}}"
QUEUED="${QUEUED:-📍 **{index}. {title}**}"
SKIP_EMOJI="${SKIP_EMOJI:-⏩}"
STOP_EMOJI="${STOP_EMOJI:-⏹️}"
QUEUE_EMOJI="${QUEUE_EMOJI:-📝}"

# Generate the config.json file
cat <<EOF > /config/config.json
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
EOF

# Run the Spotibot JAR
exec java -jar Spotibot.jar
