@echo off
setlocal
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
set MAVEN_HOME=C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

rem Runs from source. Deliberately NOT "mvn clean": clean deletes target\, which
rem includes the packaged jar, and Windows refuses to delete a jar that a running
rem MDViewer still has open - so "clean" made this script fail for the entirely
rem unrelated reason that the app was already running. Running from source has no
rem business deleting the packaged artifact anyway; use package.bat for a clean build.
mvn compile javafx:run

if errorlevel 1 (
    echo.
    echo   Run failed. If the error mentions a file in target\ that cannot be
    echo   deleted or written, close any running MDViewer window and try again.
    echo.
)
pause
