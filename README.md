# Project Setup and Compilation Guide

## Requirements
This project requires the following tools:
- Chocolatey (package manager)
- Maven (build tool)
- Java 17 JDK

## Installation Instructions

### Step 1: Install Chocolatey
1. Open PowerShell as Administrator.
2. Run the following command to install Chocolatey:
   ```powershell
   Set-ExecutionPolicy Bypass -Scope Process -Force; `
   [System.Net.ServicePointManager]::SecurityProtocol = `
   [System.Net.ServicePointManager]::SecurityProtocol `
   -bor 3072; iex ((New-Object `
   System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))
   ```
3. Close and reopen PowerShell to ensure Chocolatey is properly installed.

### Step 2: Install Dependencies Using Chocolatey
Run the following commands in PowerShell to install Maven and Java 17 JDK:
```powershell
choco install maven -y
choco install openjdk17 -y
```

### Step 3: Verify Installation
Check if Maven and Java are correctly installed:
```powershell
mvn -version
java -version
```

You should see output confirming Maven and Java 17 JDK are installed.

## Compilation and Running the Project
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/your-repo.git
   cd your-repo
   ```

2. Use Maven to compile and build the project:
   ```bash
   mvn clean install
   ```

3. Run the project:
   ```bash
   java -jar target/your-project-name.jar
   ```

Replace `your-project-name.jar` with the actual name of your JAR file generated in the `target` directory.

---

## Troubleshooting
If you encounter any issues:
- Ensure Chocolatey, Maven, and Java are properly installed and added to your system `PATH`.
- Check the project's configuration and ensure dependencies are correctly declared in the `pom.xml` file.

For additional help, consult the [Chocolatey Documentation](https://chocolatey.org/docs) or [Maven Documentation](https://maven.apache.org/).
