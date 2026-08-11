

\# Project Context: MDViewer Desktop Application



\## 📌 Overview

\*\*Project Name:\*\* MDViewer  

\*\*Description:\*\* A professional standalone desktop application for reading and writing Markdown files, ensuring cross-platform compatibility via JavaFX WebView.



\## 🛠 Technical Stack

\- \*\*Language:\*\* Java (JDK 21+)

\- \*\*Framework:\*\* JavaFX

\- \*\*Rendering Engine:\*\* `javafx.scene.web.WebView` / `WebEngine` with the support of markdown-editor (C:\\Users\\mainu\\Documents\\codes\\markdown-editor)

\- \*\*Core Architecture:\*\* Hybrid Desktop-Web bridge.

\- \*\*Development Environment:\*\* OpenCode + Qwen 3.5 (397B Model).



\## 🎯 Functional Requirements



\### 1. File System Integration

\- \*\*Open/Save:\*\* Handle `.md`, `.markdown`, and `.txt` files.

\- \*\*OS Context Menu:\*\* Must be registerable as a default handler for "Open with MDViewer" via OS right-click.



\### 2. Editing Modes (The Triad)

\- \*\*Raw Mode:\*\* Dedicated text editor for direct Markdown manipulation.

\- \*\*Split Preview:\*\* Side-by-side Raw Editor and Live HTML Preview with real-time sync.

\- \*\*Full Preview:\*\* Distraction-free rendered document view.



\### 3. GUI Toolbar \& Rich Text Editing

\- \*\*Toolbar Integration:\*\* Integration of a pre-developed rich text toolbar.

\- \*\*Interactive Editing:\*\* Formatting (Bold, Italic, H1-H6, etc.) via button clicks.

\- \*\*Bridge Logic:\*\* Java $\\rightarrow$ JS bridge (`JSObject`) for syntax injection and instant rendering.



\## 🏗 Architectural Design

\*\*Rendering Pipeline:\*\* `Local .md File` $\\rightarrow$ `Markdown Parser (Java)` $\\rightarrow$ `HTML/CSS String` $\\rightarrow$ `JavaFX WebView Render`.



\*\*UI Layout:\*\*

\- \*\*Top:\*\* Main Menu \& Rich Text Toolbar.

\- \*\*Center:\*\* Dynamic container (`SplitPane`/`StackPane`) for the three Editing Modes.

\- \*\*Footer:\*\* Status bar (Encoding, Word Count, Mode).



\## 🗺 Development Roadmap

\- \*\*Phase 1: Foundation \& Shell\*\* (Project setup, Main Window, Basic Open/Save) ✅ COMPLETED 2026-08-11

\- \*\*Phase 2: The Markdown Engine\*\* (Parser implementation, CSS Theme/GitHub Style) ✅ COMPLETED 2026-08-11

\- \*\*Phase 3: Mode Logic \& Workspace\*\* (Raw Editor, Split View sync, Full Preview toggle) 🔄 IN PROGRESS

\- \*\*Phase 4: Toolbar Bridge \& GUI Editor\*\* (Toolbar integration, JS bridge logic)

\- \*\*Phase 5: OS Deployment \& Polishing\*\* (Registry/Context menu setup, UX polish)



\---



\## ⚙️ Agent Operational Protocol (STRICT)



\### 1. Action-First Execution

\- \*\*Stop Over-Documenting:\*\* Do not create unnecessary markdown files or design documents unless explicitly requested. 

\- \*\*Instant Action:\*\* Prefer providing direct code implementations and immediate fixes over theoretical explanations. Move from "Plan" to "Code" as quickly as possible.



\### 2. Confirmation \& Commit Loop

\- \*\*Validation Phase:\*\* After implementing a feature/fix, the agent must prompt the user to verify functionality.

\- \*\*Immediate Commitment:\*\* Once the user confirms the feature is working correctly, the agent must treat this as a "Commit Point." Any state change or file update at this stage is considered final for that feature.



\### 3. Memory \& Plan Management

\- \*\*Status Checks:\*\* Before starting any new task, the agent \*\*must\*\* visit the `project\_plan.md` (or equivalent memory plan file) to synchronize current status vs. total completion percentage.

\- \*\*Dynamic Adjustments:\*\* If a new requirement is introduced mid-project:

&#x20;   1. The agent must provide a \*\*logical justification\*\* for how this change affects existing architecture.

&#x20;   2. After clarification, the agent will append the new actionable steps to the plan file rather than rewriting the entire document.

\- \*\*Overwrite Traceability:\*\* When overwriting previous plans or logic:

&#x20;   - The agent must include a comment in the code/plan specifying the \*\*Feature Number\*\* and the \*\*Commit Hash Code\*\* (or version reference) that was overwritten. 

&#x20;   - \*Format:\* `// Overwrites Feature #\[X] - Commit \[Hash]`



\## 🤖 Instructions for Qwen 3.5 (AI Model)

1\. \*\*Modular Code:\*\* Use Controller/View/Service layers.

2\. \*\*WebView Constraints:\*\* Ensure JS/CSS is compatible with JavaFX WebKit.

3\. \*\*State Management:\*\* Track the active mode (Raw/Split/Full) globally to avoid render conflicts.

4\. \*\*Performance:\*\* Implement efficient updates for large files to prevent UI freezing.

5\. \*\*Documentation:\*\* Only provide inline comments for complex bridge logic; avoid external documentation bloat.

