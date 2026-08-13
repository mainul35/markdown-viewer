# AI assistant panel — implementation plan

A collapsible chat panel that reads the document in focus, explores the sources it
refers to, and proposes a rewrite — shown as a side-by-side diff and merged only on
approval.

Branch: `feat/ai-assistant`. Nothing here is built yet; this is the plan for it.

---

## 1. What it is for

The complaint this answers is specific and worth keeping in front of the design: an
assistant asked to analyse a codebase produces **too much text, most of it unreadable
high-level language**. So the goal is not "add a chat window". It is:

> Rewrite this document so it is shorter, in-topic, and *correct against the sources it
> cites* — then show me exactly what changed before anything is saved.

Two consequences fall straight out of that:

- **The output is a diff, never a wall of prose.** The panel's primary product is a
  proposed new version of the open file. Chat is how you steer it, not what it produces.
- **Claims must be checked against sources.** A document that links to
  `docs/ROADMAP.md` or names `src/main/java/...` is making claims that can be verified by
  reading those files. That is the difference between "reads well" and "is true", and it
  is the whole reason the assistant is allowed to touch the filesystem at all.

---

## 2. Where the data goes — decide this first

This feature sends **the open document, and the contents of files it explores**, to a
model endpoint. That is a materially different act from anything else in this app, which
is otherwise entirely offline. It deserves to be designed rather than defaulted.

The documents this tool is used on include infrastructure notes carrying internal LAN
addresses, Tailscale IPs, SSH key paths and admin credentials — the sort of content this
project already refuses to send to an external PlantUML renderer, and the same reasoning
applies with more force here.

**Proposed default: local endpoints only.** A remote host must be enabled explicitly, and
the panel must say which host it is talking to, in the panel, at all times. Concretely:

- `http://localhost:*` / `127.0.0.1` / a Tailscale address is allowed with no ceremony.
- Anything else requires a per-endpoint opt-in recorded in the config file, and the
  panel header shows the host name whenever it is not local.
- A "never send" list of glob patterns per workspace, so a folder can be excluded from
  exploration entirely.

This is the one decision I would not make unilaterally, because getting it wrong is not
recoverable — see §8.

---

## 3. Provider abstraction

"OpenLLM compliant" is read here as **OpenAI-compatible `/v1/chat/completions`**, which
is the de-facto interface. One HTTP client covers Ollama, LM Studio, vLLM, llama.cpp,
LocalAI, OpenRouter and OpenAI itself; only the base URL, model name and auth header
differ.

```
AiProvider (interface)
  stream(List<Message> messages, Consumer<String> onToken) -> CompletableFuture<String>

OpenAiCompatibleProvider   base URL + model + optional bearer token, SSE streaming
AnthropicProvider          /v1/messages, different envelope   (phase 3, only if wanted)
```

`java.net.http.HttpClient` is in the JDK, so **no new dependency for HTTP**. Responses are
SSE (`data: {...}` lines); a small hand-rolled reader is enough and avoids pulling in a
JSON library for a handful of fields — the same call already made for the workspace
history file. If request/response shapes grow, revisit and take a real JSON dependency
rather than growing a parser.

Config lives in `~/.mdviewer/ai.properties`:

```properties
provider.default          = ollama
ollama.baseUrl            = http://localhost:11434/v1
ollama.model              = qwen2.5-coder:14b
ollama.apiKey             =
openrouter.baseUrl        = https://openrouter.ai/api/v1
openrouter.model          = ...
openrouter.apiKey         = ${env:OPENROUTER_API_KEY}
openrouter.allowRemote    = true
```

**Keys are read from the file or from an environment variable; the app never prompts for
one and never writes one it was not given.** The file is created with owner-only
permissions where the filesystem supports it.

---

## 4. The system prompt

Held in `src/main/resources/ai/system-prompt.md` so it can be edited without a rebuild
being needed to try a change. In outline:

- You are editing one Markdown document. Return the **complete revised document**, nothing
  else — no preamble, no explanation, no fences around the whole thing.
- Prefer deleting to adding. Prefer the concrete noun to the abstract one. Do not
  introduce section headings the document did not ask for.
- Every factual claim must be checkable against a source you were given. If a claim in
  the document contradicts a source, correct it. If you cannot check it, leave it alone
  rather than rewriting it into something that sounds better.
- Never invent file paths, commands, versions or API names.

The "return the whole document" shape is deliberate: patch formats are a rich source of
model error, and a whole-document reply diffs perfectly well locally.

---

## 5. Context gathering

The model gets: the document, plus the contents of what the document points at.

1. Parse the open document (the existing commonmark parser already gives link and code
   nodes with source spans).
2. Collect relative links and inline paths that resolve to real files inside the
   workspace.
3. Read them, capped — per file and in total — with the cap and the list of files shown
   in the panel *before* sending.
4. Never follow a path outside the workspace root, and never follow one matching the
   workspace's exclude list.

The user sees exactly what is about to be sent, as a list, and can drop entries from it.

---

## 6. The diff and approval

- A `SplitPane` of two read-only editors, current on the left, proposed on the right.
- Line diff computed locally (Myers; ~100 lines, no dependency), with changed lines
  marked in the gutter and paired scrolling.
- **Approve** applies the change through the same `applyEdit` path as every other edit,
  so it lands on the editor's undo stack and Ctrl+Z reverts it in one step.
- **Reject** discards. **Approve selected hunks** is a phase-2 refinement; whole-document
  approve is enough to be useful and much less to get wrong.

Nothing is written to disk by the assistant. It proposes; the existing save path saves.

---

## 7. Phases

| Phase | Scope | Ships something useful? |
|---|---|---|
| 1 | Collapsible panel, provider config, streaming chat against a local endpoint, no file writes | Yes — a chat that can see the document |
| 2 | Context gathering from links, whole-document rewrite, side-by-side diff, approve/reject | Yes — the actual feature |
| 3 | Per-hunk approval, remote providers with explicit opt-in, exclude lists | Refinement |
| 4 | Conversation history per document, cancel mid-stream, token accounting | Polish |

Phase 1 and 2 are each roughly the size of everything done in this session so far. This
is not a one-sitting feature and should not be attempted as one.

---

## 8. Open questions — these change the design, not just the details

1. **Local-only by default, with explicit opt-in for remote?** (§2) My recommendation is
   yes. The alternative — any configured endpoint, no ceremony — is simpler and is what
   most tools do, but it means one mis-set base URL sends a homelab document with SSH
   details to a third party, and there is no taking that back.
2. **Which endpoint first?** Ollama on `localhost:11434` is the obvious phase-1 target and
   needs no key at all. `ai.mainul35.dev` already runs Open WebUI on this network, which
   may already expose an OpenAI-compatible endpoint worth pointing at.
3. **Should the assistant read files the document does not reference?** Free exploration
   of the workspace is more capable and much harder to reason about. Recommendation: no in
   phase 2 — follow only what the document points at, which is also what makes "check the
   claims against their sources" a well-defined job.

---

## 9. Risks

| Risk | Handling |
|---|---|
| Document content reaching a third party unintentionally | Local-only default; host shown in the panel; per-workspace exclude list (§2) |
| API key ending up in the repo | Key read from `~/.mdviewer/ai.properties` or an env var, never written by the app, never in the workspace |
| Model returns a mangled document | Whole-document reply, diffed locally; nothing applies without approval; approval goes through the undo stack |
| Model invents paths or commands | System prompt forbids it, and the diff makes an invention visible as an added line |
| A large workspace blows the context window | Per-file and total caps, both shown before sending |
| Streaming on the FX thread | HTTP on a background thread; tokens appended via `Platform.runLater`, the pattern the workspace sync already uses |
