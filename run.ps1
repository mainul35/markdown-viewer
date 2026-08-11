$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:MAVEN_HOME = "C:\Users\mainu\AppData\Local\Maven\apache-maven-3.9.16"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

mvn clean compile javafx:run
