# Use the official Maven image to build the project
FROM maven:3.9.5-eclipse-temurin-17 as builder

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven project files
COPY pom.xml .
COPY src ./src

# Run Maven package to build the project
RUN mvn clean package -DskipTests

# Use a lightweight JRE image to run the application
FROM eclipse-temurin:17-jre

# Set the working directory for the runtime container
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/Spotibot.jar Spotibot.jar

# Add the entrypoint script
COPY entrypoint.sh /app/entrypoint.sh

# Make the script executable
RUN chmod +x /app/entrypoint.sh


# Set the entrypoint to the shell script
ENTRYPOINT ["/app/entrypoint.sh"]
