# MDViewer

Professional Markdown desktop application built with JavaFX.

## Prerequisites

- **JDK 21 or higher** (Download from https://adoptium.net/)
- **Maven 3.6+** (Download from https://maven.apache.org/)

## Installation

1. Install JDK 21 from https://adoptium.net/temurin/releases/?version=21
2. Verify Java installation:
   ```bash
   java -version
   javac -version
   ```
3. Install Maven (if not already installed):
   ```bash
   choco install maven
   ```
   Or download from https://maven.apache.org/download.cgi

## Building the Project

```bash
mvn clean compile
```

## Running the Application

### Option 1: Maven
```bash
mvn javafx:run
```

### Option 2: After building JAR
```bash
mvn package
java -jar target/mdviewer-1.0.0.jar
```

### Option 3: Open file directly
```bash
mvn javafx:run -- file.md
```

## Features

- **Three Editing Modes:**
  - Raw Mode: Plain text editor for Markdown
  - Split Preview: Side-by-side editor and live preview
  - Full Preview: Distraction-free rendered view

- **File Operations:**
  - Open/Save `.md`, `.markdown`, `.txt` files
  - UTF-8 encoding support
  - Unsaved changes detection

- **Live Preview:**
  - Real-time Markdown rendering
  - GitHub-style CSS theme
  - Support for tables, code blocks, blockquotes

## Project Structure

```
MDViewer/
├── src/
│   ├── main/
│   │   ├── java/com/mdviewer/
│   │   │   ├── MainApp.java          # Application entry point
│   │   │   ├── MainController.java   # Main controller logic
│   │   │   └── module-info.java      # Java module configuration
│   │   └── resources/
│   │       ├── fxml/main.fxml        # UI layout
│   │       └── css/styles.css        # Application styles
├── pom.xml                           # Maven build configuration
└── project_plan.md                   # Development roadmap
```

## Development Roadmap

- ✅ **Phase 1:** Foundation & Shell (Project setup, Main Window, Basic Open/Save)
- ⏳ **Phase 2:** The Markdown Engine (Parser implementation, CSS Theme)
- ⏳ **Phase 3:** Mode Logic & Workspace (Raw Editor, Split View sync, Full Preview toggle)
- ⏳ **Phase 4:** Toolbar Bridge & GUI Editor (Toolbar integration, JS bridge logic)
- ⏳ **Phase 5:** OS Deployment & Polishing (Registry/Context menu setup, UX polish)

## License

MIT
