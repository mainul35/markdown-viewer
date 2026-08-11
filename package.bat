@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
set MAVEN_HOME=C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

rem Produces target\mdviewer-1.0.0.jar - a single self-contained jar with JavaFX,
rem commonmark, PlantUML and mermaid inside it. Run it with: java -jar <jar> [file.md]
mvn clean package
pause
