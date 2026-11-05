# No Cloud AI

Local-first AI for JetBrains IDEs, built for WebStorm.

Everything runs on your machine: the plugin talks to an **Ollama** or **llama.cpp** server
over plain HTTP. There is no cloud backend, no telemetry, no account, and no API key
requirement.

```
Settings > Tools > No Cloud AI
```

| Feature | What it does |
|---|---|
| **Chat** | Streamed conversations with your local model, with Markdown rendering. |
| **Agent** | The model can read, search, create and edit files, run shell commands and start run configurations. |
| **Inline completion** | Ghost-text suggestions while you type, using fill-in-the-middle prompts. |
| **`@` mentions** | Type `@` in the chat input to pull a project file into the conversation. |

---

## Requirements

* A local inference server:
  * [Ollama](https://ollama.com) - `ollama serve` (default port `11434`), or
  * [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` (default port `8080`).
* JDK 21 (the build toolchain provisions it automatically).
* The plugin is compiled against **WebStorm 2026.1** and also runs in any other JetBrains
  IDE of build 261+ that has the JavaScript plugin optional.

## Build

```bash
./gradlew buildPlugin       # -> build/distributions/no-cloud-ai-<version>.zip
./gradlew test              # unit + wire-format tests
./gradlew verifyPlugin      # JetBrains plugin verifier against WebStorm
./gradlew runIde            # launch a sandbox IDE with the plugin installed
```

### Running the tests against a real server

The unit tests replay canned SSE and NDJSON bodies from a local HTTP server. To run
the same checks against a real llama.cpp or Ollama instance:

```bash
NO_CLOUD_AI_LIVE_URL=http://127.0.0.1:8069 ./gradlew test --tests '*LiveServerTest*'
```

That suite checks model discovery, streamed chat, the recovery of a tool call that the
server returned as text, a full agent turn including the tool result, and a
fill-in-the-middle completion. Set `NO_CLOUD_AI_LIVE_MODEL` if the server reports more
than one model.

## Install

1. Build the zip (or grab a release).
2. In the IDE: `Settings > Plugins > ⚙ > Install Plugin from Disk...` and pick the zip.
3. Open `Settings > Tools > No Cloud AI` and configure a backend.

## Configuration

### Ollama

| Field | Default | Notes |
|---|---|---|
| URL | `http://localhost:11434` | Any reachable Ollama server. |
| Model | *(empty)* | Use **Refresh models** to list what the server has (`GET /api/tags`). |
| API key | *(empty)* | Only needed behind an authenticating proxy. |

```bash
ollama pull qwen2.5-coder:7b
```

The plugin uses Ollama's **native** API (`/api/chat`, NDJSON streaming), not its
OpenAI-compatible shim, because the native endpoint is the one that exposes
`/api/tags` for model discovery.

### llama.cpp

| Field | Default | Notes |
|---|---|---|
| URL | `http://localhost:8080` | Point this at your running `llama-server`. |
| Model | *(empty)* | Use **Refresh models** to list `/v1/models`. |
| API key | *(empty)* | Rarely needed. |
| Disable model thinking | off | Sends `chat_template_kwargs.enable_thinking = false`. |

```bash
llama-server -m qwen3-coder-8b-q4_k_m.gguf --port 8080 --jinja
```

`--jinja` is required for **tool calling** - without it `llama-server` cannot serve the
`tools` array and the Agent tab will not be able to run tools.

> Verified against `llama-server` build `b1261` with `Qwen2.5-Coder-7B-Instruct-Q4_K_M`.

### Generation

| Field | Default |
|---|---|
| Temperature | `0.7` |
| Max tokens | `4096` |
| Context size | `8192` |
| Max tool iterations | `20` |

---

## Agent tools

The agent decides which tools to call. Every tool returns errors as data rather than
throwing, so the model can recover from a mistake.

| Tool | Purpose |
|---|---|
| `read_file` | Read a file with line numbers; supports `offset` / `limit`. |
| `write_file` | Create or overwrite a file. |
| `edit_file` | Exact string replacement; refuses ambiguous matches unless `replace_all` is set. |
| `list_directory` | List the entries of a directory. |
| `search_in_files` | Regex search across the project. |
| `run_terminal_command` | Run a shell command with a timeout and captured output. |
| `run_configuration` | Start an IntelliJ run configuration by name. |
| `todo_write` | Keep a task list for the run. |

Tool schemas are generated from the Kotlin argument classes, so the JSON Schema the
model sees can never drift from the implementation.

---

## Architecture

```
dev.nocloud.ai
├── core/        Chat/tool models, ProviderConfig
├── http/        Ktor client factory, line-oriented streaming reader
├── provider/    LlmProvider + OllamaProvider + LlamaCppProvider
├── settings/    PersistentStateComponent, PasswordSafe credentials, settings UI
├── chat/        ChatSurface (Swing), markdown rendering, @-mentions
├── agent/       AgentRunner (tool-calling loop), tools, system prompts
├── completion/  inline.completion.provider, FIM templates, output trimming
└── toolwindow/  tool window factory
```

### Why a hand-written agent loop

The agent is a plain loop rather than a framework:

```
request -> model -> tool calls -> execute -> feed results back -> request -> ...
```

That is deliberate. Agent frameworks tend to switch HTTP endpoints depending on the
model's advertised capabilities, which breaks against plain local servers - llama.cpp
does not implement the OpenAI *Responses* API, and Ollama does not implement the
OpenAI *tools* shape in the same way. Keeping the loop explicit means one code path
works for both backends.

### Streaming

Both servers stream, but differently: Ollama sends NDJSON, llama.cpp sends SSE. The
plugin reads the response body as UTF-8 lines and interprets each line per backend,
so the UI code is identical for both. Deltas are queued and flushed to the EDT by a
30 ms Swing timer, so a fast model cannot flood the UI thread.

### Tool calls that arrive as text

This is the part that differs the most from a cloud provider, and it is worth
knowing about.

A cloud API returns a structured `tool_calls` array. `llama-server` frequently does
**not**: it applies the model's chat template and hands back whatever the model wrote.
With `Qwen2.5-Coder-7B-Instruct` the tool call arrives as ordinary assistant text:

````
```json
{"name": "read_file", "arguments": {"path": "src/app.ts"}}
```
````

The plugin therefore recovers tool calls from the text
(`provider/ToolCallExtractor.kt`). It understands a JSON code fence, a bare JSON
object, `<tool_call>` tags and the `<function name="..." arguments='...'/>` form,
and it accepts the arguments as an object or as a JSON string.

While the answer is streaming, the tail of the text is held back
(`provider/ToolCallStreamFilter.kt`) so that the markup never flashes up in the chat.
A structured `tool_calls` array is still used when the server does send one - the
text based recovery is only a fallback.

### Inline completion

The `inline.completion.provider` extension point is used, with a 250 ms debounce for
typing and no debounce for a manual trigger. The plugin assembles the fill-in-the-middle
prompt itself (see `FimTemplate`) and sends it as a plain prompt:

* llama.cpp: `POST /v1/completions`
* Ollama: `POST /api/generate` with `raw: true`

Using `raw: true` matters for Ollama: without it the server wraps the prompt in the
model's chat template and the FIM markers end up in the wrong place.

---

## Known limitations

* **Tool calling depends on the model.** Small local models often answer a tool request
  with prose instead of a call. The plugin recovers the common text formats, but a model
  that ignores the tool prompt simply will not use the agent.
* **No code completion for the `NONE` FIM template.** It sends the raw prefix, which
  works for plain completion models but not for fill-in-the-middle ones.
* **The tool window is Swing.** No web view, no rich diff rendering.
* **The agent keeps its whole history.** Long sessions grow the request until the
  context window is full; there is no history compression yet.

---

## Credits

The design of this plugin - in particular the provider model, the FIM prompt handling
and the inline completion trimming pipeline - was modelled on
[ProxyAI](https://github.com/carlrobertoh/ProxyAI) by Carl-Robert Linnupuu, which is
licensed under the Apache License 2.0. No ProxyAI source code is copied here, but the
attribution is kept because the architecture follows it closely.

```
Copyright 2024 Carl-Robert Linnupuu
Licensed under the Apache License, Version 2.0
```

This project is also licensed under the Apache License 2.0 - see [LICENSE](LICENSE).
