$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:MAVEN_HOME = "C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

# A running MDViewer holds target\mdviewer-1.0.0.jar open, and Windows will not let the
# build overwrite or delete it. Maven reports that as "Failed to clean project" or
# "Could not create modular JAR file" - neither of which mentions the actual cause, so
# check for it here and say so plainly instead.
$jar = Join-Path $PWD 'target\mdviewer-1.0.0.jar'
if (Test-Path $jar) {
    try {
        [IO.File]::Open($jar, 'Open', 'ReadWrite', 'None').Close()
    } catch {
        Write-Host ""
        Write-Host "  target\mdviewer-1.0.0.jar is open in another process." -ForegroundColor Red
        Write-Host "  That is almost certainly MDViewer itself - note it runs as javaw.exe," -ForegroundColor Red
        Write-Host "  so it will not show up if you go looking for `"java`"." -ForegroundColor Red
        Write-Host ""
        Write-Host "  Close every MDViewer window and run this again." -ForegroundColor Red
        Write-Host ""
        exit 1
    }
}

# Produces target\mdviewer-1.0.0.jar - a single self-contained jar with JavaFX,
# commonmark, PlantUML and mermaid inside it. Run it with: java -jar <jar> [file.md]
mvn clean package
