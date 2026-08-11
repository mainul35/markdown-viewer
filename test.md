# Test Markdown File

## Overview

This is a **test** file to verify the _MDViewer_ application.

### Features

- Bold text
- Italic text
- `Inline code`

### Code Block

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

### Table

| Column 1 | Column 2 | Column 3 |
|----------|----------|----------|
| A        | B        | C        |
| D        | E        | F        |

### Blockquote

> This is a blockquote.
> Multiple lines supported.

### Links

[Visit GitHub](https://github.com)

### Mermaid Diagram

```mermaid
graph LR
  A[Markdown] --> B[MarkdownService]
  B --> C[WebView preview]
  B --> D[DiagramService]
  D --> C
```

### PlantUML Diagram

```plantuml
@startuml
actor User
participant MDViewer
participant PlantUML
User -> MDViewer: open .md
MDViewer -> PlantUML: render diagram
PlantUML --> MDViewer: SVG
MDViewer --> User: preview
@enduml
```
