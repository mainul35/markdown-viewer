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

## 2. Where the data goes — decided

This feature sends **the open document, and the contents of files it explores**, to a
model endpoint. That is a materially different act from anything else in this app, which
is otherwise entirely offline, and the documents it is aimed at include infrastructure
notes carrying internal addresses and SSH key paths.

**Decided: two endpoints, both self-hosted.**

| Host | What it is | Probed |
|---|---|---|
| `litellm.mainul35.dev` | LiteLLM `v1.85.0` on **homelabai**, port 4000, OpenAI-compatible at `/v1/*` | `/health/liveliness` → 200; `/v1/models` → 401, needs a key |
| `ai.mainul35.dev` | Open WebUI on **proxy-vm**, port 8880 | `/api/models` → 401, needs a bearer token |

**homelabai also runs Ollama on 11434**, with `qwen3-coder:30b`,
`qwen3-coder-planner`, `glm-4.7-flash`, `gemma4:31b` and others already pulled. That
matters for §2: LiteLLM routed at a local Ollama model keeps a document on your own
hardware end to end, which is the configuration to prefer for the private documents this
tool is pointed at.

So the rule is not "localhost only" but **an allowlist of hosts, defaulting to loopback
plus those two**. Anything not on the list is refused outright rather than warned about,
and the panel shows the host it is talking to at all times.

One thing worth stating once: LiteLLM is a *proxy*. Whether a request stays on your
infrastructure depends on which model it routes to — a local backend keeps it there, a
hosted one does not. The app cannot see past the proxy, so that boundary is set in
LiteLLM's own config, not here.

**Keys are never handled by the app.** They are read from `~/.mdviewer/ai.properties` or
an environment variable, put there by you. The app never prompts for one, never writes
one, and never logs one.

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

Config lives in `~/.mdviewer/ai.properties`, shipped with these defaults:

```properties
provider.default    = litellm

litellm.baseUrl     = https://litellm.mainul35.dev/v1
litellm.model       = qwen3-coder:30b
litellm.apiKey      = ${env:LITELLM_API_KEY}

openwebui.baseUrl   = https://ai.mainul35.dev/api
openwebui.model     = qwen3-coder:30b
openwebui.apiKey    = ${env:OPENWEBUI_API_KEY}

# Hosts this app is permitted to send document content to. Anything not listed is
# refused before a request is built.
allowedHosts        = localhost, 127.0.0.1, litellm.mainul35.dev, ai.mainul35.dev
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

## 8. Questions — answered

1. **Which endpoints?** `litellm.mainul35.dev` and `ai.mainul35.dev`, both self-hosted.
   LiteLLM is the primary target: it is OpenAI-compatible at `/v1/chat/completions`, so
   it needs no special-casing at all. Open WebUI speaks a near-identical shape under
   `/api/`.
2. **Trust boundary?** An allowlist rather than localhost-only, since both chosen hosts
   are remote. Loopback plus those two by default; anything else refused (§2).
3. **May it read files the document does not reference?** No. It follows only what the
   document points at — which is what makes "check the claims against their sources" a
   well-defined job rather than open-ended exploration.
4. **What if a referenced file is outside every open workspace?** It is **not read**.
   The panel lists it as unavailable and asks you to add its workspace or paste the
   relevant content. Silently reading it would put the app outside the boundary the
   workspace list defines; silently ignoring it would let the model "verify" a claim
   against a source it never saw, which is worse than not checking at all.

## 9. Risks

| Risk | Handling |
|---|---|
| Document content reaching a third party unintentionally | Local-only default; host shown in the panel; per-workspace exclude list (§2) |
| API key ending up in the repo | Key read from `~/.mdviewer/ai.properties` or an env var, never written by the app, never in the workspace |
| Model returns a mangled document | Whole-document reply, diffed locally; nothing applies without approval; approval goes through the undo stack |
| Model invents paths or commands | System prompt forbids it, and the diff makes an invention visible as an added line |
| A large workspace blows the context window | Per-file and total caps, both shown before sending |
| Streaming on the FX thread | HTTP on a background thread; tokens appended via `Platform.runLater`, the pattern the workspace sync already uses |
