# AI assistant panel — implementation plan

**The goal, as set out before any of it was built:** a collapsible chat panel that reads
the document in focus, explores the sources it refers to, and proposes a rewrite — shown
as a side-by-side diff and merged only on approval.

**What exists today** is the first two thirds of that: a panel that reads the document and
the sources you name, and answers questions about them. It proposes no rewrite and writes
nothing. Read this sentence as the description of the product; read the one above it as
where it was headed.

Branch: `feat/ai-assistant`, merged to `main` and shipped in `1.0.0-release` (2026-08-16).

**Read §10 before §1–§6.** Sections 1 to 6 are the plan as written before any of it
existed, kept because the reasoning is still worth having. Several parts were built
differently, and the headline — a proposed rewrite shown as a diff — was **not built at
all**. §10 records what shipped, what did not, and where the design changed under use.

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

**Keys.** Three ways in, all of them yours to choose: the **API key...** button in the
panel (a masked field, kept in memory for the session), an environment variable, or the
config file. The runtime key wins over both, so a session key can override a stale saved
one without editing anything.

Entering a key writes nothing to disk. Saving it is a separate tick-box in the same
dialog, because keeping a key for an hour and keeping it forever are different decisions.
When saved, only that one line of the config file is rewritten — the comments, the
allowlist and the other provider are left exactly as they were. The key is never echoed
into the status line, the transcript, or a log.

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

| Phase | Scope | State |
|---|---|---|
| 1 | Collapsible panel, provider config, allowlist, streaming chat that can see the open document | **Shipped** in `1.0.0-release` |
| 1b | Context gathering — paths, URLs and workspace files named in a *question*; whole-project scan; sources listed with what was skipped | **Shipped.** Not in the original plan in this form; see §10.2 |
| 2 | Whole-document rewrite, side-by-side diff, approve/reject | **Not started.** Still the largest unbuilt piece |
| 3 | Per-hunk approval, exclude lists | Not started |
| 3b | Provider and model configuration from Settings, host allowed from the panel | **Shipped.** Pulled forward from phase 3; see §10.3 |
| 4 | Conversation per document, cancel mid-stream | **Shipped.** Token accounting not done |

The estimate below held. Phase 1 plus the context work was about forty commits, and phase
2 remains roughly the same size again.

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

---

## 10. What shipped — and where this plan was wrong

Appended per the operational protocol in `project_plan.md` §3 rather than rewriting
§1–§6, so the original reasoning stays readable next to what came of it.

### 10.1 The headline feature was not built

**Overwrites Feature #1 (§1, §4, §6) — not implemented as of `ce29293`.**

§1 says the panel's primary product is a proposed rewrite and that "chat is how you steer
it, not what it produces". That is still unbuilt. What shipped is the thing §1 dismissed:
a conversation. It summarises a document and answers questions about it, reading sources
when you name them.

This matters beyond the plan, because the claim leaked. The project website was written
saying MDViewer reads the repository a document describes and tells you which paragraphs
have gone stale. It does not, and that copy was corrected. **The tool answers when asked;
it does not check documentation on its own.** Anything written about it — README, site,
release notes — has to say the second thing.

Phase 2 remains worth building, and §6's design still looks right: a whole-document reply,
diffed locally, applied through the existing edit path so Ctrl+Z reverts it in one step.

### 10.2 Context gathering grew well past "what the document links to"

**Overwrites Feature #5 (§5) and Question 3 (§8).** Commits `c9ad9e6`, `8828096`,
`6da6cc1`, `6ab0694`.

§5 planned to follow links found *in the document*. §8 answered "may it read files the
document does not reference?" with a flat **no**, on the grounds that following only what
the document points at makes the job well defined.

Use said otherwise, immediately. The first real question was "analyse the codebase at
`C:\...\vsd-auth-server`", which the document did not link to and the plan therefore
refused to read. What shipped reads:

- absolute paths and URLs named **in the question** — you typed it, in a message asking
  about it, which is consent;
- files named by name in the question, resolved against the open workspace — "does
  `PLAN.md` agree with this?" should not require typing a full path;
- relative paths found in the document, as originally planned;
- optionally, **every file in a project**, in as many passes as it takes.

The workspace is still the boundary for anything not typed out in full, so §8's fourth
answer holds. What changed is that the *question* is now a source of consent, not only the
document.

§5 also says the user "sees exactly what is about to be sent, as a list, and can drop
entries from it". Only half shipped: the list is shown **after** the answer, folded away,
and entries cannot be dropped. Reviewing a list of forty files before every question would
be worse than the problem it solves, but the "drop an entry" idea is still open.

### 10.3 One provider became nine, and the allowlist grew a way in

**Extends Feature #2 (§2) and Feature #3 (§3).** Commits `81cb941`, `79ab715`, `ce29293`.

§2 decided on two self-hosted endpoints. Nine now ship configured — OpenAI, Ollama local
and cloud, Groq, OpenRouter, Mistral, DeepSeek alongside the original two — all speaking
the same OpenAI-compatible shape, so this cost nothing but configuration.

The allowlist rule is unchanged and is the reason that was safe: a provider being listed
is not permission to send anything to it. What changed is that agreeing is now possible
from inside the app, through a dialog that names the host and says what goes there.
Refusing with no way forward taught people to make the file permissive in advance, which
is the opposite of what an allowlist is for.

**Settings → AI Providers** also configures address, model and key per provider — pulled
forward from phase 3 because a picker offering nine providers and no way to set one up is
half a feature.

Anthropic and Bedrock are still absent, now deliberately and with the reason recorded in
`ai.properties`: Anthropic is `/v1/messages` with a different envelope and an `x-api-key`
header, Bedrock signs with AWS SigV4. Neither is a base URL away.

### 10.4 Shapes that differ from §3 and §4

- **No `AiProvider` interface.** `ChatProvider` is one final class with a blocking
  `stream(...)` called from a background thread. An interface with one implementation is a
  guess about the second; add it when Anthropic actually arrives.
- **No `src/main/resources/ai/system-prompt.md`.** The prompts live in Java, next to the
  code that assembles them, because they are built from parts — evidence labels, the
  project map, budget notes — rather than being one editable block. The trade is real:
  changing wording needs a rebuild.
- **The prompt's job changed.** §4 was written for a rewriter: "return the complete revised
  document". What shipped instructs an answerer instead — cite the file behind each claim,
  distinguish a `DOCUMENT` stating intent from `CODE` stating behaviour, and say plainly
  what could not be checked. Every one of those rules exists because an answer got
  something wrong first.

### 10.5 What the plan did not anticipate at all

- **A context window is a hard wall, and overrunning it is silent.** An oversized request
  is not refused; it is truncated from the front, which removes the instructions and leaves
  the model holding files it no longer knows what to do with. Everything sent is bounded
  together now, not just the sources.
- **A budget is a choice about what to leave out.** Ordering by relevance let one package
  take 41% of it; ordering by folder gave a stylesheet the same standing as the schema.
  What shipped reserves part for breadth and spends the rest on relevance.
- **A project too large for one request needs a shared vocabulary between passes.** The
  project map — every file's declarations, extracted locally — is what lets a pass name
  something defined in a part it was never given.

### 10.6 Still open

1. **Phase 2**: whole-document rewrite, side-by-side diff, approve/reject.
2. **Token accounting.** Budgets are in characters, converted at a rule-of-thumb four per
   token. A real count would let the ceiling be the model's rather than a guess.
3. **Per-workspace exclude lists** (§9), never built.
4. **Dropping entries from the source list** before sending (§5).
5. **The harnesses.** The assistant's checks — 337 across fifteen JavaFX harnesses — live
   in a scratchpad, not in `src/test`. Two of them read a private checkout that exists on
   one machine.
