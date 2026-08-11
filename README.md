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

- **Workspaces:** a workspace is a folder. Opening a file adopts its folder as a workspace;
  opening another file from anywhere inside that folder joins the same one, while a file
  from elsewhere starts a second workspace. **File → Open Folder...** adds one directly.

- **Workspace explorer** (left panel): a markdown-only file tree over every open workspace.
  It lists `.md`, `.markdown` and `.txt` files, and only those folders with markdown
  somewhere inside them — everything else (build output, images, dot-directories) is hidden,
  at the workspace root as well as further down. Directories are read only when first
  expanded, so adding a workspace containing `target/` or `node_modules/` does not stall the
  UI. Double-click a file to open it. The crosshair button in the panel header — or
  **View → Reveal Current File** — expands the tree down to the document you are editing and
  scrolls to it, which is the point of it when a workspace holds hundreds of files.
  **View → Hide Explorer** collapses the panel.

- **Two-level tabs:** workspace tabs on top, and under each one only the files belonging to
  that workspace. Many open files stay legible because they are grouped by origin instead of
  forming one long strip. Each document keeps its own editor, so undo history and caret
  position survive tab switches; a single shared editor/preview area is moved into whichever
  file tab is selected, so there is still only one WebView no matter how many files are open.
  At most **10 workspace tabs** and **20 file tabs per workspace** can be open at once; past
  that, opening is refused with a status-bar message rather than silently closing something
  you still had open.

- **Find and replace** in the raw editor: **Ctrl+F** to find, **Ctrl+H** to find and
  replace, both under the Edit menu. Enter and Shift+Enter step through matches, `Aa`
  toggles case sensitivity, Esc closes. Matching is plain text rather than regex, because
  Markdown is full of characters that are regex metacharacters and typing a literal `*` or
  `[` should find that character. Replace All is one edit, so it undoes in a single step.
  Invoking find from Full Preview drops back to Split so there is an editor to search.

- **Design:** a "drafting plate" direction, chosen for the job this tool actually does —
  long architecture documents read for an hour at a time. Cool vellum rather than warm
  cream, a blueprint-teal accent, Sitka for headings against Segoe UI body text, and a
  prose column held to a readable measure while code, tables and diagrams break out of it.
  Fenced code blocks are presented as labelled plates captioned with their own language
  tag; diagrams share the same plate family. All fonts are system faces — this is an
  offline app, so a webfont would simply fail to load.

- **Formatting from the preview:** a toolbar above the rendered document applies bold,
  italic, strikethrough, inline code, headings, lists, quotes, links and images without
  needing to remember the Markdown for them. The same actions are on the preview's
  right-click menu, which replaces WebKit's Copy-only one.

  Selecting text in the *preview* and pressing a button edits the *source*. Every rendered
  element carries the offsets of the Markdown it came from, and a selection reports which
  occurrence of that text it is within its element, so the second "hello" in a paragraph is
  the one that gets styled. A selection spanning existing formatting cannot be matched back
  and says so rather than guessing. With no preview selection the buttons fall back to the
  editor's own selection, which is what makes them useful in Split mode.

- **Images from the preview:** *Insert image* copies the chosen file into `assets/` beside
  the document and inserts a relative reference — copying rather than linking in place is
  what keeps the document portable. Click an image in the preview to select it, then set
  its width (25/75/50/100%) and position (left/centre/right). Markdown has no syntax for
  either, so a positioned or resized image is written as an `<img>` in an aligned paragraph
  — the form that also renders on GitHub. Returning it to full width and left alignment
  turns it back into plain `![](...)` Markdown, so the HTML never accumulates.

- **Three Editing Modes:**
  - Raw Mode: Plain text editor for Markdown
  - Split Preview: Side-by-side editor and live preview
  - Full Preview: Distraction-free rendered view

- **Dark / light theme:** toggled from the toolbar or **View → Dark Mode**. One switch
  themes the JavaFX chrome and the preview document together. The preview carries both
  palettes and flips a `data-theme` attribute, so switching costs no reload and keeps your
  scroll position. Diagram cards deliberately stay light in dark mode — PlantUML and mermaid
  bake dark strokes and text into their SVG, which would be invisible on a dark card.
  The theme is not persisted between runs; the app always starts in light mode.

- **Toolbar:** file actions live only in the File menu, since duplicate buttons added no
  quick access the menu did not already provide. The mode buttons stay (mode switching is
  frequent) and sit on the right, next to the theme switch.

- **Responsive window:** the window opens at 85% of the screen's *visual* bounds (the work
  area, excluding the taskbar) and is centred there, with a 900x600 floor that is itself
  clamped so it can never exceed a smaller screen. It scales from a 1024x600 netbook up to
  4K. Window position and size are not remembered between runs.

- **File Operations:**
  - Open/Save `.md`, `.markdown`, `.txt` files
  - UTF-8 encoding support
  - Unsaved changes detection

- **Live Preview:**
  - Real-time Markdown rendering, debounced so typing stays responsive
  - GitHub-style CSS theme
  - Support for tables, code blocks, blockquotes
  - Preview scroll position is preserved while editing, and each document remembers its
    own position — switching tabs or following a link and coming back resumes where you
    were rather than jumping to the top
  - Web links open in the system browser instead of hijacking the preview pane

- **Following links between documents:** a relative link to another Markdown file
  (`[rules](.claude/rules/code-style.md)`) opens that file as a new tab in the right
  workspace and reveals it in the explorer, expanding the folders on the way. This works
  for Markdown links and raw HTML `<a href>` alike. A file reached this way is surfaced in
  the tree even if the markdown-only filter would normally hide its folder — a document you
  deliberately opened is not the noise the filter exists to remove. Links to non-Markdown
  files are reported in the status bar rather than opened; the app never launches arbitrary
  local files.

- **Diagrams:**
  - ` ```mermaid ` fences render through the bundled mermaid build
  - ` ```plantuml ` (also `puml`, `uml`) fences render to inline SVG, on a background
    thread so large diagrams never freeze the UI
  - a bare `@startuml` … `@enduml` block renders without a fence too — those are
    PlantUML's own delimiters, so a fence adds nothing. The block has to be
    unbroken: a blank line inside one, or `*` bullet syntax such as `@startmindmap`,
    is read as Markdown and still needs a fence
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
│   │   │   │   ├── MainController.java       # UI + workspace/preview orchestration
│   │   │   │   ├── service/
│   │   │   │   │   ├── MarkdownService.java  # Markdown -> HTML, diagrams, image paths
│   │   │   │   │   └── DiagramService.java   # PlantUML -> SVG, off-thread + cached
│   │   │   │   └── ui/
│   │   │   │       ├── FileTreePanel.java    # Explorer + reveal-in-tree target
│   │   │   │       ├── PathTreeItem.java     # Lazily loaded file-tree node
│   │   │   │       ├── WorkspaceView.java    # A workspace and its file tabs
│   │   │   │       └── DocumentView.java     # One open document + its editor
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
