@echo off
rem
rem Starts MDViewer with the settings its own runtime needs, so nobody has to
rem know them. Runs from wherever it is unzipped.
rem
rem From Java 24 onwards the JVM prints two warnings, both about JavaFX rather
rem than about this application: its rasteriser uses sun.misc.Unsafe, and loading
rem its native libraries is a restricted operation. Nothing here can stop JavaFX
rem doing either; the flags say "yes, that is expected" so the console stays
rem readable. They are only passed to a JVM new enough to understand them - an
rem unknown option stops the JVM starting, which is a far worse trade than a
rem warning.

setlocal enabledelayedexpansion

set "HERE=%~dp0"
set "JAR=%HERE%mdviewer-1.0.0.jar"

if not exist "%JAR%" (
    echo mdviewer-1.0.0.jar is not next to this script ^(looked in %HERE%^).
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo Java is not installed, or not on the PATH. MDViewer needs Java 21 or newer.
    exit /b 1
)

rem  openjdk version "25.0.4" ...  ->  25
set "MAJOR=0"
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "RAW=%%~v"
    for /f "delims=. tokens=1" %%m in ("!RAW!") do set "MAJOR=%%m"
    goto :gotversion
)
:gotversion

set "FLAGS="
if !MAJOR! GEQ 24 set "FLAGS=--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

java %FLAGS% -jar "%JAR%" %*
