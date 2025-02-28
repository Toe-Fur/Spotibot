# Set the working directory to the script's folder (i.e., the main bots folder)
Set-Location $PSScriptRoot

# Launch Spotibot.jar using Java
Start-Process java -ArgumentList '-jar "C:\Users\htopr\OneDrive\Desktop\Bots\target\Spotibot.jar"'
