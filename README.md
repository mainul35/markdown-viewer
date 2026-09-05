# MDViewer

![1](assets/1.png)

![2](assets/2.png)

![3](assets/3.png)

Professional Markdown desktop application built with JavaFX.

## Download

**Latest release:** [MDViewer 1.1.0](https://github.com/mainul35/markdown-viewer/releases/latest)

There is one download per platform. Each bundles JavaFX, which ships compiled libraries
per operating system and architecture, so they are **not interchangeable**.

| Your machine | Download |
|---|---|
| Windows | `mdviewer-1.1.0-win.zip` |
| Linux, Intel or AMD 64-bit | `mdviewer-1.1.0-linux.zip` |
| Linux on ARM (Raspberry Pi) | `mdviewer-1.1.0-linux-aarch64.zip` |
| macOS, Apple silicon (M1 and later) | `mdviewer-1.1.0-mac-aarch64.zip` |
| macOS, Intel | `mdviewer-1.1.0-mac.zip` |

Unzip it and run the launcher inside:

```bash
./run.sh          # Linux and macOS
run.bat           # Windows
```

The launcher is the recommended way in. It selects the X11 backend on Linux — JavaFX has
no Wayland backend and may otherwise never show a window — and quiets two JVM warnings
that come from JavaFX rather than from this application. `java -jar mdviewer-1.1.0.jar`
still works if you prefer.

> Requires JDK 21 or newer. See the installation notes below.

Taking the wrong download fails at startup with `Error initializing QuantumRenderer: no
suitable pipeline found`, which names graphics pipelines rather than the cause: that jar
has no renderer built for your system. **Release 1.0.0 shipped a single jar carrying the
Windows libraries to everybody**, so if it never started for you on Linux or macOS, that
was the packaging and 1.1.0 is the fix.

Earlier tags — `1.0.0-EA`, `1.0.0-beta-1` and `1.0.0-release` — are kept for reference.

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
java -jar target/mdviewer-1.1.0.jar
```

### Option 3: Open file directly
```bash
java -jar target/mdviewer-1.1.0.jar file.md
```

<div align="left">

## Distribution

</div>

`mvn clean package` (or `package.bat` / `package.ps1`) produces **one self-contained jar**:
`target/mdviewer-1.1.0.jar`, roughly 58 MB. Everything it needs is inside it — JavaFX
(including its native libraries), commonmark, PlantUML with its C4 standard library, and the
mermaid bundle. The target machine needs only a JRE 21; no JavaFX SDK, no Graphviz, and no
internet connection.

### Building for each platform

The jar carries JavaFX's native libraries, and which ones is decided by `javafx.platform`.
Left alone it follows the machine doing the build — which is how 1.0.0 shipped Windows
libraries to every user. Name it instead:

```bash
mvn clean package -DskipTests -Djavafx.platform=linux          # also: win, mac,
mvn clean package -DskipTests -Djavafx.platform=linux-aarch64  # mac-aarch64
```

Each produces `target/mdviewer-1.1.0-<platform>.jar`. **Cross-building works** — these are
ordinary downloads, so every platform builds on any machine, and a release does not need
five computers. Each run overwrites `target/`, so copy the jar out between builds.

A plain `mvn package` with no `-D` still produces `mdviewer-1.1.0.jar` built for this
machine, which is what you want while working on it and never what you want for a release.

A release download also carries `dist-scripts/run.sh` or `dist-scripts/run.bat`. Those are
the **launchers that ship to users** — not to be confused with `run.bat` and `run.ps1` in
the repository root, which run the application from source during development.

<div align="left">

Two consequences of that packaging worth knowing:

</div>

- **The jar is platform-specific.** It embeds JavaFX's native libraries for one operating
  system and architecture. Build one per platform with `-Djavafx.platform` as above; you do
  not need one machine per platform, only one flag per build.
- **`Launcher` is the manifest main class, not `MainApp`.** The JVM refuses to start a main
  class that extends `javafx.application.Application` when JavaFX is on the classpath, which
  is exactly the situation inside a shaded jar. `Launcher` is a plain class that calls into
  `MainApp`, which sidesteps that check. Do not "simplify" the manifest to point at `MainApp`.

**Close the app before packaging.** A running MDViewer holds `target/mdviewer-1.1.0.jar`
open, and Windows will not let the build overwrite or delete it. Maven reports that as
`Failed to clean project` or `Could not create modular JAR file`, neither of which
mentions the real cause, so `package.bat`/`package.ps1` check for it first and say so
plainly. Note the app runs as **javaw.exe**, so it does not appear if you go looking for
`java` in Task Manager.

`run.bat` deliberately does not `clean` — running from source has no business deleting
the packaged jar, and doing so made the script fail whenever the app happened to be open.

The startup warning `Unsupported JavaFX configuration: classes were loaded from 'unnamed
module'` is expected and harmless — it is JavaFX noting that it is running from the classpath
rather than the module path.

## Features

- **Touch, on a tablet:** the gestures a touchscreen has and a mouse does not. X11 reports
  a touchscreen as an ordinary pointer, so JavaFX never sees a touch and never synthesises
  a scroll or offers a right click — without these, a drag selected text and nothing moved,
  and every context menu in the application was unreachable.

  | Gesture | What it does |
  |---|---|
  | Drag | Scrolls the document |
  | Press and hold | Opens the context menu a right click would |
  | Double tap, hold, drag | Selects across lines |
  | One tap in the file tree | Opens that file |

  **Settings → Display Size** — Tablet, Regular or Extra Large — scales type, row heights,
  hit targets, menus, dialogs and the rendered document together. **Settings → Touch Mode**
  turns the gestures on. Both default themselves on a machine that has a touchscreen and no
  keyboard attached: a tablet in a keyboard case is a laptop, and a keyboard thrown over
  half the screen is worse than none.

  An on-screen keyboard is asked for when you tap into any text field, and the window moves
  above it. Neither happens by itself, because JavaFX takes no part in the input-method
  protocol and the compositor is therefore never told a text field has focus. The command is
  configurable in `~/.mdviewer/ui.properties` — `keyboardShowCommand` defaults to
  `vkbd --show`, `keyboardHeightFraction` says how much of the screen to leave clear, and
  `keyboardSummon=never` switches the whole thing off.

- **Paste a screenshot into a document:** Ctrl+V, or **Edit → Paste Image**, writes the
  picture into an `assets/` folder beside the file and inserts a reference to it. An unsaved
  document offers to save itself first, since there is nowhere to put the file until it has
  a home.

- **Welcome screen:** with nothing open, the window offers New document, Open file, Open
  folder and the folders you had open last, rather than an empty grey rectangle. The file
  tree hides with it — two empty panels say less than one screen that offers somewhere to
  start — and comes back when something is open.

- **Assistant** (right-hand panel, **View → Show Assistant**, or the tab down the right
  edge): a conversation about the document in front of you. It answers from that document,
  and from sources it is given rather than from memory.

  It reads what a question names: an absolute path, a URL, or a file named by name
  anywhere in the open workspace. What it read is listed under the answer, folded away,
  along with anything it had to skip and why.

  **Scan whole project** reads every file instead. A real project does not fit in a
  context window at any budget, so it reads in passes and answers from the findings —
  about ten passes for a 138-file Java project. Every pass carries a map of the whole
  project's declarations, extracted locally, which is what lets a pass say where something
  is defined even though it was given a different part of the project. Progress and the
  pass count are shown, and Stop scan answers from what has been read so far.

  Each document keeps its own conversation, its own draft question and its own scan, so
  two files can be asked about at once and neither waits for the other. Answers are
  rendered as Markdown in the preview's own style.

  **Nothing is sent to a host you have not agreed to.** `allowedHosts` in
  `~/.mdviewer/ai.properties` is a refusal, not a warning: a mistyped base URL cannot
  quietly ship a private document to a stranger. Agreeing is a dialog that names the host
  and says exactly what goes there — your question, the open document, and every file a
  scan reads. **Settings → AI Providers** lists the configured providers, chooses which
  appear in the panel, allows their hosts, and sets each one's address, model and key. Nine
  are configured out of the box (LiteLLM, Open WebUI, OpenAI, Ollama local and cloud, Groq,
  OpenRouter, Mistral, DeepSeek), all speaking the OpenAI chat-completions shape. An API
  key is kept for the session unless you ask for it to be saved.

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

- **Recent workspaces:** **File → Recent Workspaces** lists the folders opened before,
  most recent first, and remembers them across runs in `~/.mdviewer/workspaces.txt`.
  A folder already open is shown greyed rather than hidden, so the list does not look
  like it forgot; one that has since been deleted is dropped instead of offered and then
  failed. The label is the folder name plus its parent, because several checkouts of the
  same project is the normal case and the name alone is ambiguous exactly when the list
  is most useful.

  The file is one path per line — no JSON library is bundled, a list of paths does not
  justify taking one, and a line-per-path file is something you can read and edit in any
  editor. Everything about it is best effort: a history that cannot be read or written is
  a missing convenience, never a reason to fail an open.

  Each row carries a **✕** that forgets just that folder, because clearing the whole list
  is a poor trade when one scratch directory is sitting among the projects you use. The
  menu stays open while you do it, so several can go in one pass.

- **Workspace refresh:** the explorer caches directory listings, so a file written outside
  the app — by a build, a `git pull`, or another editor — would otherwise stay invisible
  until you collapsed and re-expanded its folder. **View → Refresh Workspaces** (F5) and
  *Refresh workspaces* on the explorer's right-click menu re-read it on demand, and
  **Settings → Auto-refresh Workspaces** does the same every 5 minutes. The toggle is on by
  default and is a toggle because a workspace on a network share is exactly where the scan
  stops being free.

  The refresh is a merge, not a reload: folders that are still there keep their identity, so
  your expanded folders stay expanded and your selection stays selected. Only directories
  already read are re-read, so refreshing never forces the lazy tree to load anything.

- **Undo/Redo:** **Ctrl+Z** and **Shift+Ctrl+Z** (Edit menu). The editor, preview formatting,
  table edits, and image operations all share one undo stack, so you can undo a formatting
  change, then a text edit, then an image resize in sequence.

- **Two-level tabs:** workspace tabs on top, and under each one only the files belonging to
  that workspace. Many open files stay legible because they are grouped by origin instead of
  forming one long strip. Each document keeps its own editor, so undo history and caret
  position survive tab switches; a single shared editor/preview area is moved into whichever
  file tab is selected, so there is still only one WebView no matter how many files are open.
  At most **10 workspace tabs** and **20 file tabs per workspace** can be open at once; past
  that, opening is refused with a status-bar message rather than silently closing something
  you still had open.

- **Find and replace:** **Ctrl+F** to find, **Ctrl+H** to find and replace (Edit menu).
  Enter and Shift+Enter step through matches, `Aa` toggles case sensitivity, Esc closes.
  Matching is plain text rather than regex, because Markdown is full of characters that are
  regex metacharacters. Replace All is one edit, so it undoes in a single step. Invoking
  find from Full Preview drops back to Split so there is an editor to search.

  Ctrl+H is intercepted before the editor sees it, because ASCII 0x08 *is* Ctrl+H — left
  alone, the keystroke reaches a focused text area as a typed backspace and deletes a
  character instead of opening the bar.

  A match is highlighted in amber while the bar is open. A match is shown by selecting it,
  so it shares `-fx-highlight-fill` with ordinary selection — and that is deliberately a
  pale tint, since it sits under text being read for an hour at a time. The editor is
  marked while searching so the two can be coloured separately: amber rather than the teal
  accent, so a hit is never mistaken for something you selected yourself.

- **Design:** A "drafting plate" aesthetic for long architecture documents. Cool vellum
  background, blueprint-teal accents, Sitka for headings against Segoe UI body text. Tables
  wrap text instead of scrolling sideways. Fenced code blocks are labelled plates; diagrams
  share the same style. All fonts are system faces — no webfonts, so it works offline.

- **Formatting toolbar:** Bold, italic, strikethrough, inline code, headings, lists, quotes,
  links, and images — all from the preview. Same actions on the preview's right-click menu.
  Select text in the preview, click a button, and it edits the source. The toolbar falls back
  to the editor's selection when the preview has none, so it works in Split mode too.

- **Code blocks:** Inline code (backticks) auto-upgrades to fenced blocks for multi-line
  selections. Syntax highlighting via bundled highlight.js. Right-click a code block to set
  its language — only the fence line changes, never the code.

- **In-place editing:** Double-click a paragraph, heading, list, or quote to edit its
  Markdown source in a popup editor. Ctrl+Enter commits, Escape cancels. Tables edit
  cell-by-cell; diagrams are skipped.

- **Table editor:** Double-click any cell to edit. The cell shows its Markdown source (so
  `` `code` `` stays `` `code` ``). Committing re-aligns all pipes automatically. Paste
  handles `|` and newlines correctly.

- **Table inserter:** Toolbar button opens a grid picker or exact size input (up to 100×30).
  Inserts aligned GFM tables with the first header cell selected.

- **Print & PDF export:** Print button opens the system dialog. Use "Microsoft Print to PDF"
  for PDF output. Tables split across pages with repeating headers. Images and diagrams never
  break mid-figure. Code wraps, dark mode prints light, filename defaults to the document's
  first heading.

- **Images from the preview:** *Insert image* copies the chosen file into `assets/` beside
  the document and inserts a relative reference — copying rather than linking in place is
  what keeps the document portable. Right-click an image in the preview for its own menu:
  resize (75/100/125/150%), position (left/centre/right), crop, caption, replace, copy path
  and remove. Markdown has no syntax for size, position or captions, so a styled image is
  written as an `<img>` in an aligned paragraph — or a `<figure>` when it has a caption —
  the forms that also render on GitHub. Returning it to 100% width, left alignment and no
  caption turns it back into plain `![](...)` Markdown, so the HTML never accumulates.

- **Files from the explorer:** right-click any row for New file, New folder, Rename and
  Delete, plus Open on a file. Deletions go to the recycle bin. Renaming moves any open
  document with the file, so the next save does not write it back under the old name. A
  workspace root offers creation only — renaming or binning it would leave the workspace
  pointing at nothing; use **File → Close Workspace** instead.

- **Three editing modes:** Raw (editor only), Split (side-by-side), Full Preview (rendered
  view only). Switch via toolbar buttons or View menu.

- **Dark/Light theme:** Toolbar toggle or View menu. The JavaFX chrome, the preview document
  and the assistant transcript switch together. Preview keeps both palettes loaded, so theme
  changes are instant with no scroll jump. Diagrams stay light in dark mode (PlantUML/Mermaid
  bake dark strokes into SVG). Theme resets on restart (always starts light).

- **Responsive window:** Opens at 85% of screen's work area (excludes taskbar), centered.
  Minimum 900×600, scales to 4K. Position/size not persisted between runs.

- **File operations:** Open/save `.md`, `.markdown`, `.txt` files. UTF-8 encoding. Unsaved
  changes detected and prompted on close.

- **Live preview:** Real-time rendering with 200ms debounce. GitHub-style CSS. Scroll
  position preserved per-document — switch tabs or follow links, come back to where you were.
  Web links open in system browser.

- **Follow links between documents:** Relative links to Markdown files open in a new tab and
  reveal in the explorer. Works for both Markdown links and raw HTML `<a href>`. Links to
  non-Markdown files shown in status bar (app does not launch arbitrary local files).

- **Diagrams:**
  - **Mermaid:** Fenced ` ```mermaid ` blocks render via bundled mermaid 10.9.3.
    Supported: flowchart, sequenceDiagram, classDiagram, stateDiagram-v2, erDiagram,
    gantt, mindmap, gitGraph, quadrantChart, requirementDiagram.
    **Not supported:** Beta types (block-beta, sankey-beta, xychart-beta, pie, journey,
    timeline). Mermaid 11.x incompatible with JavaFX WebKit.
  - **PlantUML:** Fenced ` ```plantuml ` (or `puml`, `uml`) renders to inline SVG on a
    background thread. Bare `@startuml`...`@enduml` blocks render without fences.
    C4-PlantUML `!include` URLs rewritten to bundled C4 library (works offline).
    Uses pure-Java smetana engine — no Graphviz needed.
  - Diagrams cached by source — editing surrounding prose does not re-render.

- **Images:**
  - Relative paths resolve against the document's directory (Markdown and HTML `<img>`).
  - Absolute `http(s):`, `data:`, `file:` URLs left untouched.
  - Insert image copies file to `assets/` beside the document (keeps docs portable).
  - Right-click images: resize (75/100/125/150%), position (left/center/right), crop,
    caption, replace, copy path, remove. Styled images written as `<img>` in aligned
    paragraphs or `<figure>` with captions — renders on GitHub too.

## Project Structure

```
MDViewer/
├── src/main/
│   ├── java/com/mdviewer/
│   │   ├── MainApp.java           # JavaFX Application entry point
│   │   ├── Launcher.java          # Entry point for shaded JAR (avoids JavaFX startup issues)
│   │   ├── MainController.java    # UI orchestration, workspace & preview management
│   │   ├── service/
│   │   │   ├── MarkdownService.java   # Markdown → HTML, diagram extraction, image paths
│   │   │   ├── DiagramService.java    # PlantUML → SVG (background thread, cached)
│   │   │   ├── SourceEdits.java       # Markdown source transformations (formatting, tables)
│   │   │   ├── ImageRef.java          # Image path handling & markup generation
│   │   │   └── Trash.java             # Cross-platform recycle bin support
│   │   ├── ai/
│   │   │   ├── AiPanel.java           # The assistant panel: transcript, composer, per-document threads
│   │   │   ├── AiConfig.java          # ai.properties: endpoints, keys, allowedHosts
│   │   │   ├── ChatProvider.java      # OpenAI-compatible streaming, by hand (no JSON library)
│   │   │   ├── ContextGatherer.java   # Turns paths, URLs and file names into text to send
│   │   │   ├── ProjectScanner.java    # Reads a whole project in passes and folds the findings
│   │   │   ├── ProjectIndex.java      # Declarations per file, carried into every pass
│   │   │   └── ProviderSettings.java  # Settings → AI Providers
│   │   └── ui/
│   │       ├── FileTreePanel.java   # Workspace explorer (markdown-only tree)
│   │       ├── PathTreeItem.java    # Lazy-loading tree node
│   │       ├── WorkspaceView.java   # Workspace tab + document tabs management
│   │       ├── DocumentView.java    # Single document (editor + metadata)
│   │       ├── WelcomeView.java     # First screen when nothing is open
│   │       ├── PreviewToolbar.java  # Formatting toolbar actions
│   │       ├── FindBar.java         # Find & replace bar
│   │       ├── ChartDialog.java     # Chart insertion, and the type picker beside it
│   │       ├── TableSizePicker.java # Table size grid
│   │       ├── AccountBar.java      # Cloud sign-in state, along the explorer's foot
│   │       ├── SyncIndicator.java   # What sync is doing, and what it last did
│   │       ├── TouchScroll.java     # Drag to scroll; the touch-mode setting
│   │       ├── LongPress.java       # Press and hold as a right click
│   │       ├── DisplaySize.java     # Tablet / Regular / Extra Large
│   │       ├── VirtualKeyboard.java # Summons the on-screen keyboard; device detection
│   │       ├── WindowStyling.java   # Puts the theme and size on dialogs, which are own windows
│   │       ├── UiSettings.java      # ui.properties: display size, touch, keyboard
│   │       ├── ClipboardImage.java  # Clipboard picture to PNG, by three routes
│   │       └── CropDialog.java      # Image crop dialog
│   │   └── sync/                    # Cloud sync: client, session, scanner, state machine
│   └── resources/
│       ├── fxml/main.fxml         # Main UI layout
│       ├── css/styles.css         # Application + preview styles, and the display sizes
│       ├── css/menus-tablet.css   # Menu popups at each size - a popup is its own window,
│       ├── css/menus-large.css    #   so it takes a stylesheet rather than a style class
│       ├── css/mdchart.css        # Chart rendering
│       └── js/mermaid.min.js      # Bundled mermaid renderer
├── pom.xml                        # Maven build configuration
├── package.bat / package.ps1      # Build standalone shaded JAR
├── run.bat / run.ps1              # Run from source (development)
├── dist-scripts/run.sh            # Launchers that ship inside a release download -
├── dist-scripts/run.bat           #   not the same thing as run.bat above
└── screenshots/                   # Application screenshots for documentation
```

## Third-party components

| Component | Version | Licence | Why it is pinned |
|---|---|---|---|
| JavaFX | 21 | GPLv2 + Classpath Exception | UI toolkit and WebView |
| commonmark-java | 0.21.0 | BSD-2-Clause | Markdown parsing (+ GFM tables) |
| PlantUML | 1.2026.6 (`plantuml-mit`) | MIT | The MIT-licensed build is used deliberately, to match this project's licence |
| highlight.js | 11.11.1 (`org.webjars:highlightjs`) | BSD-3-Clause | Syntax highlighting for fenced code. Only the script is used — the themes are replaced by the preview's own token colours |
| mermaid | 10.9.3 | MIT | **Do not upgrade to 11.x** — it uses JavaScript syntax that JavaFX's WebKit 615.1 cannot parse, and mermaid silently stops loading |

## Development Roadmap

- ✅ **Phase 1:** Foundation & Shell (Project setup, Main Window, Basic Open/Save)
- ✅ **Phase 2:** The Markdown Engine (Parser implementation, CSS Theme)
- ✅ **Phase 3:** Mode Logic & Workspace (Raw Editor, Split View sync, Full Preview toggle)
- ✅ **Phase 4:** Toolbar Bridge & GUI Editor (Toolbar integration, JS bridge logic, formatting actions)
- ✅ **Phase 5:** OS Deployment & Polishing (Workspace management, file operations, find/replace, dark mode, diagram rendering)
- ✅ **Phase 6:** Assistant (reads the document and the sources it names; whole-project scan; per-document conversations) — shipped in `1.0.0-release`
- ✅ **Phase 7:** Touch (drag to scroll, press and hold for context menus, one tap to open a file, display sizes, on-screen keyboard, per-platform downloads) — shipped in `1.1.0`
- ⏳ **Assistant phase 2:** Propose a whole-document rewrite and show it as a side-by-side diff, merged only on approval. Nothing in the assistant writes to a document yet.
- ⏳ **Session persistence:** Open workspaces, open documents, theme and window geometry are forgotten on exit. Recent workspaces already persist; the rest do not.
- ⏳ **Native Packaging:** Bundle JRE for true double-click execution without requiring Java installation
- ⏳ **OS context menu:** "Open with MDViewer" via the Windows registry

## License

MIT

