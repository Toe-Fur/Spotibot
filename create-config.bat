@echo off

set "CONFIG_FILE=%~dp0config.json"

if exist "%CONFIG_FILE%" (
    echo Config file already exists at "%CONFIG_FILE%". No changes made.
    exit /b 0
)

(
echo {
echo   "bot_token": "YOUR_BOT_TOKEN_HERE",
echo   "status": "Playing music on Discord",
echo   "default_volume": 60,
echo   "queue_format": {
echo     "now_playing": "🎶 **Now Playing:** {title}",
echo     "queued": "📍 **{index}. {title}**"
echo   },
echo   "emojis": {
echo     "skip": "⏩",
echo     "stop": "⏹️",
echo     "queue": "📝"
echo   }
echo }
) > "%CONFIG_FILE%"

echo Default config.json created at "%CONFIG_FILE%".
