$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:MAVEN_HOME = "C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

# Produces target\mdviewer-1.0.0.jar - a single self-contained jar with JavaFX,
# commonmark, PlantUML and mermaid inside it. Run it with: java -jar <jar> [file.md]
mvn clean package
