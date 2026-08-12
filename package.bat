@echo off
setlocal
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
set MAVEN_HOME=C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

rem A running MDViewer holds target\mdviewer-1.0.0.jar open, and Windows will not let
rem the build overwrite or delete it. Maven reports that as "Failed to clean project" or
rem "Could not create modular JAR file" - neither of which mentions the actual cause, so
rem check for it here and say so plainly instead.
if exist "target\mdviewer-1.0.0.jar" (
    powershell -NoProfile -Command "try { [IO.File]::Open((Join-Path $PWD 'target\mdviewer-1.0.0.jar'),'Open','ReadWrite','None').Close(); exit 0 } catch { exit 1 }"
    if errorlevel 1 (
        echo.
        echo   target\mdviewer-1.0.0.jar is open in another process.
        echo   That is almost certainly MDViewer itself - note it runs as javaw.exe,
        echo   so it will not show up if you go looking for "java".
        echo.
        echo   Close every MDViewer window and run this again.
        echo.
        pause
        exit /b 1
    )
)

rem Produces target\mdviewer-1.0.0.jar - a single self-contained jar with JavaFX,
rem commonmark, PlantUML and mermaid inside it. Run it with: java -jar <jar> [file.md]
mvn clean package
pause
