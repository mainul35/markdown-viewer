# Project Context: MDViewer Desktop Application

## 📌 Overview

**Project Name:** MDViewer

**Description:** A professional standalone desktop application for reading and writing Markdown files, ensuring cross-platform compatibility via JavaFX WebView.

## 🛠 Technical Stack

- **Language:** Java (JDK 21+)
- **Framework:** JavaFX
- **Rendering Engine:** `javafx.scene.web.WebView` / `WebEngine` with the support of markdown-editor (`C:\Users\mainu\Documents\codes\markdown-editor`)
- **Core Architecture:** Hybrid Desktop-Web bridge.
- **Development Environment:** OpenCode + Qwen 3.5 (397B Model).

## 🎯 Functional Requirements

### 1. File System Integration

- **Open/Save:** Handle `.md`, `.markdown`, and `.txt` files.
- **OS Context Menu:** Must be registerable as a default handler for "Open with MDViewer" via OS right-click.

### 2. Editing Modes (The Triad)

- **Raw Mode:** Dedicated text editor for direct Markdown manipulation.
- **Split Preview:** Side-by-side Raw Editor and Live HTML Preview with real-time sync.
- **Full Preview:** Distraction-free rendered document view.

### 3. GUI Toolbar & Rich Text Editing

- **Toolbar Integration:** Integration of a pre-developed rich text toolbar.
- **Interactive Editing:** Formatting (Bold, Italic, H1-H6, etc.) via button clicks.
- **Bridge Logic:** Java → JS bridge (`JSObject`) for syntax injection and instant rendering.

## 🏗 Architectural Design

**Rendering Pipeline:** `Local .md File` → `Markdown Parser (Java)` → `HTML/CSS String` → `JavaFX WebView Render`.

**UI Layout:**

- **Top:** Main Menu & Rich Text Toolbar.
- **Center:** Dynamic container (`SplitPane`/`StackPane`) for the three Editing Modes.
- **Footer:** Status bar (Encoding, Word Count, Mode).

## 🗺 Development Roadmap

- **Phase 1: Foundation & Shell** (Project setup, Main Window, Basic Open/Save) ✅ COMPLETED 2026-08-11
- **Phase 2: The Markdown Engine** (Parser implementation, CSS Theme/GitHub Style) ✅ COMPLETED 2026-08-11
- **Phase 3: Mode Logic & Workspace** (Raw Editor, Split View sync, Full Preview toggle) ✅ COMPLETED 2026-08-11
- **Phase 4: Toolbar Bridge & GUI Editor** (Toolbar integration, JS bridge logic) 🔄 IN PROGRESS — toolbar restructured; rich-text bridge not started
- **Phase 5: OS Deployment & Polishing** (Registry/Context menu setup, UX polish) 🔄 IN PROGRESS — standalone jar done; OS context menu not started

---

## ⚙️ Agent Operational Protocol (STRICT)

### 1. Action-First Execution

- **Stop Over-Documenting:** Do not create unnecessary markdown files or design documents unless explicitly requested.
- **Instant Action:** Prefer providing direct code implementations and immediate fixes over theoretical explanations. Move from "Plan" to "Code" as quickly as possible.

### 2. Confirmation & Commit Loop

- **Validation Phase:** After implementing a feature/fix, the agent must prompt the user to verify functionality.
- **Immediate Commitment:** Once the user confirms the feature is working correctly, the agent must treat this as a "Commit Point." Any state change or file update at this stage is considered final for that feature.

### 3. Memory & Plan Management

- **Status Checks:** Before starting any new task, the agent **must** visit the `project_plan.md` (or equivalent memory plan file) to synchronize current status vs. total completion percentage.
- **Dynamic Adjustments:** If a new requirement is introduced mid-project:
    1. The agent must provide a **logical justification** for how this change affects existing architecture.
    2. After clarification, the agent will append the new actionable steps to the plan file rather than rewriting the entire document.
- **Overwrite Traceability:** When overwriting previous plans or logic:
    - The agent must include a comment in the code/plan specifying the **Feature Number** and the **Commit Hash Code** (or version reference) that was overwritten.
    - *Format:* `// Overwrites Feature #[X] - Commit [Hash]`

## 🤖 Instructions for Qwen 3.5 (AI Model)

1. **Modular Code:** Use Controller/View/Service layers.
2. **WebView Constraints:** Ensure JS/CSS is compatible with JavaFX WebKit.
3. **State Management:** Track the active mode (Raw/Split/Full) globally to avoid render conflicts.
4. **Performance:** Implement efficient updates for large files to prevent UI freezing.
5. **Documentation:** Only provide inline comments for complex bridge logic; avoid external documentation bloat.

---

## 📋 Delivered Beyond the Original Plan (2026-08-11)

Appended per Protocol §3 rather than rewriting the sections above.

### Fixes
- **Preview rendered nothing.** `WebEngine.loadContent(html, "text/html; charset=UTF-8")` is rejected by JavaFX's WebKit — the load ends `CANCELLED` and the pane stays blank forever. Use the single-argument overload.
- **Raw / Full Preview modes did nothing.** Moving the `SplitPane` divider to 0.0/1.0 does not collapse a pane; the children have to be swapped.
- **Preview reloaded the whole page per keystroke,** losing scroll position. Now a shell page loads once and content is pushed into the live DOM, debounced 200 ms.
- **Window was fixed at 1200x800** — 98% of the usable height on a 1536x816 work area. Now 85% of the screen's visual bounds, centred, with a clamped 900x600 floor.
- **`project_plan.md` itself was stored backslash-escaped** and rendered as literal `#` and `**`. Unescaped; word content verified identical.

### Features
- **Diagrams:** `mermaid` and `plantuml` fences render. PlantUML runs locally (bundled C4 stdlib, pure-Java `smetana` layout) so no Graphviz, no network, and no diagram source leaves the machine.
- **Images:** relative paths resolve against the document's own folder, for Markdown and raw HTML `<img>` alike.
- **Dark / light theme** across the JavaFX chrome and the preview document.
- **Workspace explorer + two-level tabs:** workspace tabs on top, that workspace's file tabs beneath, markdown-only lazy file tree, reveal-in-tree target button.
- **Cross-document link navigation:** relative links to other Markdown files open in the viewer and reveal in the tree, expanding the folders on the way — including through folders the markdown-only filter hides, such as `.claude/rules/`.
- **Standalone distribution:** one self-contained ~57 MB jar (JavaFX natives, commonmark, PlantUML, mermaid inside).

### Constraints discovered — do not regress these
- **mermaid must stay on 10.9.3.** 11.x uses JavaScript that JavaFX's WebKit 615.1 cannot parse; it fails with `SyntaxError` and silently never loads.
- **`Launcher`, not `MainApp`, is the jar's main class.** The JVM refuses to start an `Application` subclass when JavaFX is on the classpath.
- **The shaded jar is platform-specific** — it embeds the JavaFX natives of the OS it was built on.

---

## 🔜 Next Actionable Steps

1. **Persist session state** (Phase 5). Open workspaces, open documents, the active theme and the window geometry are all forgotten on exit. One `java.util.prefs.Preferences` store should cover all four; it writes to the Windows registry, so confirm before adding.
2. **Watch the filesystem** (Phase 3 follow-up). The explorer caches directory listings, so files created outside MDViewer only appear after collapsing and re-expanding the folder. A `WatchService` per workspace root would fix it.
3. **Promote the test harnesses into the repo** (Phase 5). ~140 checks across six JavaFX harnesses currently live in a temporary scratchpad and are lost between sessions. Moving them to `src/test` needs absolute paths replaced with fixtures.
4. **Rich-text toolbar bridge** (Phase 4, original scope). Integrate the `markdown-editor` toolbar and the Java → JS `JSObject` bridge for formatting buttons.
5. **OS context-menu registration** (Phase 5, original scope). "Open with MDViewer" via the Windows registry.
6. **Scroll sync in Split mode** (Phase 3 follow-up). Content sync is live; editor and preview scroll positions are still independent.

