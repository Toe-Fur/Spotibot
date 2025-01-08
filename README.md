
# Spotibot

Spotibot is a Java-based music bot that allows users to play and manage audio tracks seamlessly. This bot can be run locally or deployed on a server, providing a robust solution for music streaming and scheduling.

---

# Project Setup and Compilation Guide

## Requirements
This project requires the following tools:
- **Chocolatey** (Windows package manager)
- **Maven** (build tool)
- **Java 17 JDK** (Java Development Kit)

---

## Installation Instructions

### Step 1: Install Chocolatey
1. Open **PowerShell** as Administrator.
2. Run the following command to install Chocolatey:
   ```powershell
   Set-ExecutionPolicy Bypass -Scope Process -Force; `
   [System.Net.ServicePointManager]::SecurityProtocol = `
   [System.Net.ServicePointManager]::SecurityProtocol `
   -bor 3072; iex ((New-Object `
   System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))
   ```
3. Close and reopen PowerShell to ensure Chocolatey is properly installed.

---

### Step 2: Install Dependencies Using Chocolatey
Run the following commands in PowerShell to install Maven and Java 17 JDK:
```powershell
choco install maven -y
choco install openjdk17 -y
```

---

### Step 3: Verify Installation
To ensure everything is set up correctly, verify Maven and Java installations by running:
```powershell
mvn -version
java -version
```

You should see outputs confirming Maven and Java 17 JDK are installed.

---

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
Execute the generated JAR file. If you have multiple JDK versions installed, explicitly run with JDK 17:
```bash
<path-to-jdk-17>/bin/java -jar target/spotibot-1.0-SNAPSHOT.jar
```

For example, if you installed JDK 17 via Chocolatey:
```bash
C:\ProgramData\chocolatey\lib\openjdk17\bin\java -jar target/spotibot-1.0-SNAPSHOT.jar
```

---

## Features
- Play audio tracks from a variety of sources
- Manage track scheduling and playlists
- Built-in audio player handlers
- Customizable configurations

---

## Docker (Optional)
If you prefer running Spotibot in a containerized environment:
1. Build the Docker image:
   ```bash
   docker build -t spotibot .
   ```
2. Run the Docker container:
   ```bash
   docker run -d -p 8080:8080 spotibot
   ```

---

## Troubleshooting
If you encounter any issues:
- Verify that **Chocolatey**, **Maven**, and **Java 17** are installed and added to your system's `PATH`.
- To ensure JDK 17 is used, specify the absolute path to the JDK as shown above.
- Ensure that dependencies in the `pom.xml` file are properly declared.
- Review project configuration settings in the `config.json` file.

For further assistance, consult:
- [Chocolatey Documentation](https://chocolatey.org/docs)
- [Maven Documentation](https://maven.apache.org/)
- [Spotibot Issues](https://github.com/your-username/your-repo/issues) (if applicable)

---

## License
This project is licensed under the MIT License.

For any questions or feature requests, feel free to contact the maintainers or open a GitHub issue.
