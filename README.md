![image](https://github.com/user-attachments/assets/9af50704-e1d9-434a-9bcc-84197947c638)

# Spotibot

Spotibot is a Java-based music bot that allows users to play and manage audio tracks seamlessly. This bot can be run locally or deployed on a server, providing a robust solution for music streaming and scheduling.

## Changelog
See `CHANGELOG.md` for release notes and history.

# Project Setup and Compilation Guide

## Requirements
This project requires the following tools:
- **Maven** (build tool)
- **Java 21 JDK** (Java Development Kit)
- **YT-DLP**

## Compilation and Running the Project

### Step 1: Clone the Repository
Clone the repository to your local machine:
```bash
git clone <repository-url>
cd Bots
```

### Step 2: Build the Project
Use Maven to clean and build the project:
```bash
mvn clean package
```

### Step 3: Run the Project
Execute the generated JAR file. If you have multiple JDK versions installed, explicitly run with JDK 21:
```bash
<path-to-jdk-21>/bin/java -jar target/spotibot-1.0-SNAPSHOT.jar
```

For example, if you installed JDK 21 via Chocolatey:
```bash
C:\ProgramData\chocolatey\lib\openjdk21\bin\java -jar target/spotibot-1.0-SNAPSHOT.jar
```

## Configuration

The bot requires a `config.json` file in the `bots` directory. You can create this file manually or use the provided script.

### Example `config.json`
```json
{
  "bot_token": "YOUR_BOT_TOKEN_HERE",
  "status": "Playing music on Discord",
  "default_volume": 60,
  "queue_format": {
    "now_playing": "🎶 **Now Playing:** {title}",
    "queued": "📍 **{index}. {title}**"
  },
  "emojis": {
    "skip": "⏩",
    "stop": "⏹️",
    "queue": "📝"
  }
}
```

![image](https://github.com/user-attachments/assets/8ac13fd5-6e14-480a-bd65-da4c7a397490)

## Features
- Play audio tracks from a variety of sources
- Manage track scheduling and playlists
- Built-in audio player handlers
- Customizable configurations

## Troubleshooting
If you encounter any issues:
- Verify that **Maven**, and **Java 21** are installed and added to your system's `PATH`.
- To ensure JDK 21 is used, specify the absolute path to the JDK as shown above.
- Ensure that dependencies in the `pom.xml` file are properly declared.
- Review project configuration settings in the `config.json` file.

## Running the Bot with Docker

### Step 1: Build the Docker Image
Navigate to the project directory and build the Docker image:
```bash
docker build -t spotibot .
```

### Step 2: Run the Docker Container
Run the Docker container with the necessary environment variables:
```bash
docker run -e BOT_TOKEN="your discord token" \
           -e STATUS="Streaming tunes" \
           -e DEFAULT_VOLUME=75 \
           -e NOW_PLAYING="🎵 **Playing Now:** {title}" \
           -e QUEUED="🎧 **{index}. {title}**" \
           -e SKIP_EMOJI="⏭" \
           -e STOP_EMOJI="⏹" \
           -e QUEUE_EMOJI="🗒" \
           -v /path/to/config:/app/config \
           spotibot
```

### Step 3: Access the Bot
Ensure that the bot is running and accessible on your Discord server.

## Authentication 
- Export your YouTube cookies using a browser extension (e.g., EditThisCookie).
- Save the cookies as a cookies.txt file.
- Place the cookies.txt file in the same folder as the config.json
- Run the bot. Spotibot will automatically use your cookies for YouTube requests.

For further assistance, consult:
- [Maven Documentation](https://maven.apache.org/)
- [Spotibot Issues](https://github.com/your-username/your-repo/issues) (if applicable)

## License
This project is licensed under the MIT License.

For any questions or feature requests, feel free to contact the maintainers or open a GitHub issue.

