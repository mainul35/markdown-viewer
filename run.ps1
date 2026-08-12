$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:MAVEN_HOME = "C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

# Runs from source. Deliberately NOT "mvn clean": clean deletes target\, which includes
# the packaged jar, and Windows refuses to delete a jar that a running MDViewer still has
# open - so "clean" made this script fail for the entirely unrelated reason that the app
# was already running. Use package.ps1 when you actually want a clean build.
mvn compile javafx:run

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "  Run failed. If the error mentions a file in target\ that cannot be" -ForegroundColor Yellow
    Write-Host "  deleted or written, close any running MDViewer window and try again." -ForegroundColor Yellow
    Write-Host ""
}
