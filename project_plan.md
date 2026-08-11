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
- **Phase 4: Toolbar Bridge & GUI Editor** (Toolbar integration, JS bridge logic) ✅ COMPLETED 2026-08-11 — formatting toolbar over the preview, selection mapped back to source via CommonMark source spans
- **Phase 5: OS Deployment & Polishing** (Registry/Context menu setup, UX polish) 🔄 IN PROGRESS — standalone jar and UX polish done; OS context-menu registration and session persistence not started

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
- **GUI formatting from the preview:** toolbar and context menu apply bold/italic/strike/code, headings, lists, quotes, links and images by editing the Markdown source. Selections are mapped back through `data-md-*` offsets stamped from CommonMark source spans, disambiguated by occurrence index.
- **Image insert, position and resize:** files are copied into `assets/` beside the document; alignment and width are written as an `<img>` in an aligned paragraph, reverting to plain Markdown when set back to full width and left.
- **Cross-document link navigation:** relative links to other Markdown files open in the viewer and reveal in the tree, expanding the folders on the way — including through folders the markdown-only filter hides, such as `.claude/rules/`.
- **Standalone distribution:** one self-contained ~57 MB jar (JavaFX natives, commonmark, PlantUML, mermaid inside).

### Constraints discovered — do not regress these
- **mermaid must stay on 10.9.3.** 11.x uses JavaScript that JavaFX's WebKit 615.1 cannot parse; it fails with `SyntaxError` and silently never loads.
- **`Launcher`, not `MainApp`, is the jar's main class.** The JVM refuses to start an `Application` subclass when JavaFX is on the classpath.
- **The shaded jar is platform-specific** — it embeds the JavaFX natives of the OS it was built on.

---

## 📋 Second Delivery Round (2026-08-11)

### Editing from the preview (Phase 4)
- **Formatting toolbar and context menu over the preview.** Bold, italic, strikethrough, inline code, H1–H3, lists, quote, link, image. A selection in the rendered HTML is mapped back to Markdown through `data-md-*` offsets stamped from CommonMark source spans, disambiguated by which occurrence of the selected text it is — Markdown only adds characters around text, so occurrence order survives rendering.
- **Block operations anchor to the block, not the innermost inline.** Headings, lists and quotes rewrite whole lines; anchoring them to a `<strong>` scoped a multi-line selection to that run and the action silently failed.
- **Bullet, number and quote are mutually exclusive line styles.** Applying one clears any existing marker instead of nesting `- > text`; removing strips a single level so un-quoting a nested quote steps out rather than flattening.
- **Text alignment** wraps the block in `<div align="…">` with blank lines inside the wrapper, which is what keeps CommonMark parsing the content as Markdown.
- **Image context menu:** resize (75/100/125/150%), position, crop, caption, replace, copy path, remove.
- **Crop** writes the dragged region to a new file beside the original rather than overwriting it.

### Explorer and navigation
- **Cross-document link navigation** with reveal-in-tree, including through folders the markdown-only filter hides.
- **File operations from the explorer:** new file, new folder, rename, delete. Deletion goes to the recycle bin; renaming moves any open document with the file so the next save does not recreate it under the old name.
- **Per-document scroll memory** — switching tabs or following a link and returning resumes in place.
- **Find and replace** in the raw editor (Ctrl+F / Ctrl+H), plain-text matching, Replace All as one undo step.

### Design
- **"Drafting plate" direction** for preview and editor chrome: cool vellum, blueprint-teal accent, Sitka headings against Segoe UI body, prose held to a readable measure while code, tables and diagrams break out of it. Fenced blocks render as labelled plates captioned with their own language tag.

### Further constraints — do not regress these
- **Raw HTML must carry source offsets in its own opening tag, not a wrapper.** An alignment wrapper is deliberately one half of a pair (`<div align>` … `</div>` as separate blocks); wrapping closes it immediately and the content it should contain falls outside. Without offsets at all, a styled image can only ever be styled once.
- **JavaFX will not show a context menu that has no items.** A menu populated from its own `onShowing` handler never opens, because the show is skipped before the handler runs. Build the items before showing.
- **A right-click does not move a TreeView's selection.** A menu built from the selected row acts on whatever was selected previously; build it from the row under the pointer.
- **`java.awt.headless` must stay unset.** Headless mode disables `Desktop`, which is what moves deleted files to the recycle bin. PlantUML renders the same either way.
- **Markdown link destinations containing spaces need angle brackets.** `![a](assets/Screen shot.png)` is not an image at all and renders as literal text; screenshot filenames routinely contain spaces.

---

## 🔜 Next Actionable Steps

1. **Verify crop by hand.** It is the one feature no harness covers: the dialog is modal and the harnesses cannot drive it. Everything else in this round is asserted; crop has only ever been compile-checked.
2. **Promote the test harnesses into the repo** (Phase 5). 284 checks across twelve JavaFX harnesses live in a temporary scratchpad and are lost between sessions. Moving them to `src/test` needs absolute paths replaced with fixtures. This is now the largest single risk to the project: the safety net does not survive the session that built it.
3. **Persist session state** (Phase 5). Open workspaces, open documents, the active theme and the window geometry are all forgotten on exit. One `java.util.prefs.Preferences` store should cover all four; it writes to the Windows registry, so confirm before adding.
4. **Watch the filesystem** (Phase 3 follow-up). The explorer caches directory listings, so files created outside MDViewer only appear after collapsing and re-expanding the folder — the in-app operations refresh explicitly, but external changes do not. A `WatchService` per workspace root would fix it.
5. **Drag-to-resize image handles.** Width is set from presets; dragging a corner in the preview would be closer to the markdown-editor package's behaviour, and crop currently offers drag-to-draw only, with no handles to adjust a region after drawing it.
6. **OS context-menu registration** (Phase 5, original scope). "Open with MDViewer" via the Windows registry.
7. **Scroll sync in Split mode** (Phase 3 follow-up). Content sync is live; editor and preview scroll positions are still independent.
8. **Heading anchors.** A link of the form `file.md#section` opens the file and ignores the fragment, because CommonMark emits no heading ids.

