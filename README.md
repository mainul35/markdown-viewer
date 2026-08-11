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

### Option 2: Standalone jar
```bash
mvn clean package
java -jar target/mdviewer-1.0.0.jar
```

### Option 3: Open file directly
```bash
java -jar target/mdviewer-1.0.0.jar file.md
```

## Distribution

`mvn clean package` (or `package.bat` / `package.ps1`) produces **one self-contained jar**:
`target/mdviewer-1.0.0.jar`, roughly 57 MB. Everything it needs is inside it — JavaFX
(including its native `.dll`s), commonmark, PlantUML with its C4 standard library, and the
mermaid bundle. The target machine needs only a JRE 21; no JavaFX SDK, no Graphviz, and no
internet connection.

Two consequences of that packaging worth knowing:

- **The jar is platform-specific.** It embeds the JavaFX natives for the OS it was built on,
  so a jar built on Windows runs on Windows. Build on each OS you intend to ship to.
- **`Launcher` is the manifest main class, not `MainApp`.** The JVM refuses to start a main
  class that extends `javafx.application.Application` when JavaFX is on the classpath, which
  is exactly the situation inside a shaded jar. `Launcher` is a plain class that calls into
  `MainApp`, which sidesteps that check. Do not "simplify" the manifest to point at `MainApp`.

The startup warning `Unsupported JavaFX configuration: classes were loaded from 'unnamed
module'` is expected and harmless — it is JavaFX noting that it is running from the classpath
rather than the module path.

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
  - Real-time Markdown rendering, debounced so typing stays responsive
  - GitHub-style CSS theme
  - Support for tables, code blocks, blockquotes
  - Preview scroll position is preserved while editing
  - Links open in the system browser instead of hijacking the preview pane

- **Diagrams:**
  - ` ```mermaid ` fences render through the bundled mermaid build
  - ` ```plantuml ` (also `puml`, `uml`) fences render to inline SVG, on a background
    thread so large diagrams never freeze the UI
  - C4-PlantUML diagrams work offline: `!include` URLs pointing at the C4-PlantUML GitHub
    repo are rewritten to the C4 standard library bundled inside the PlantUML jar
  - No Graphviz install needed — PlantUML's pure-Java `smetana` layout engine is used
  - Rendered diagrams are cached by source, so editing prose does not re-render them

- **Images:**
  - Relative image paths resolve against the directory of the file being viewed, for both
    Markdown `![](...)` images and raw HTML `<img>` tags
  - Absolute `http(s):`, `data:` and `file:` URLs are left untouched

## Project Structure

```
MDViewer/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── com/mdviewer/
│   │   │   │   ├── MainApp.java              # JavaFX Application
│   │   │   │   ├── Launcher.java             # Entry point for the shaded jar
│   │   │   │   ├── MainController.java       # UI + preview orchestration
│   │   │   │   └── service/
│   │   │   │       ├── MarkdownService.java  # Markdown -> HTML, diagrams, image paths
│   │   │   │       └── DiagramService.java   # PlantUML -> SVG, off-thread + cached
│   │   │   └── module-info.java              # Java module configuration
│   │   └── resources/
│   │       ├── fxml/main.fxml                # UI layout
│   │       ├── css/styles.css                # Application styles
│   │       └── js/mermaid.min.js             # Bundled mermaid renderer
├── pom.xml                                   # Maven build configuration
├── package.bat / package.ps1                 # Build the standalone jar
├── run.bat / run.ps1                         # Run from source
└── project_plan.md                           # Development roadmap
```

## Third-party components

| Component | Version | Licence | Why it is pinned |
|---|---|---|---|
| JavaFX | 21 | GPLv2 + Classpath Exception | UI toolkit and WebView |
| commonmark-java | 0.21.0 | BSD-2-Clause | Markdown parsing (+ GFM tables) |
| PlantUML | 1.2026.6 (`plantuml-mit`) | MIT | The MIT-licensed build is used deliberately, to match this project's licence |
| mermaid | 10.9.3 | MIT | **Do not upgrade to 11.x** — it uses JavaScript syntax that JavaFX's WebKit 615.1 cannot parse, and mermaid silently stops loading |

## Development Roadmap

- ✅ **Phase 1:** Foundation & Shell (Project setup, Main Window, Basic Open/Save)
- ✅ **Phase 2:** The Markdown Engine (Parser implementation, CSS Theme)
- ✅ **Phase 3:** Mode Logic & Workspace (Raw Editor, Split View sync, Full Preview toggle)
- ⏳ **Phase 4:** Toolbar Bridge & GUI Editor (Toolbar integration, JS bridge logic)
- ⏳ **Phase 5:** OS Deployment & Polishing (Registry/Context menu setup, UX polish)

## License

MIT
