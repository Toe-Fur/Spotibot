# Use Maven image to build the project for the specified platform
FROM --platform=linux/amd64,linux/arm64 maven:3.8.7-openjdk-18-slim AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the project
RUN mvn clean package -DskipTests

# Use a lightweight JRE image for the runtime for the specified platform
FROM --platform=linux/amd64,linux/arm64 openjdk:17-jdk-slim

# Set working directory
WORKDIR /app

# Install yt-dlp and required dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    python3 \
    python3-pip && \
    pip3 install yt-dlp && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy the JAR file from the builder stage
COPY --from=builder /app/target/Spotibot.jar app.jar

# Command to run the application
CMD ["java", "-jar", "app.jar"]
