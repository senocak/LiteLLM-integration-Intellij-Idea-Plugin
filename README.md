# LiteLLM Integration

IntelliJ IDEA plugin that connects the IDE to a LiteLLM-compatible gateway. It does two things:

- **Generate Commit Message** — a Commit tool window action that reads only the changes currently included for commit, sends their diff to the gateway, and fills in the commit-message field. It never commits automatically.
- **Inline code completion** — Copilot-style grey ghost text as you type, accepted with <kbd>Tab</kbd>.

## Configure

In **Settings | Tools | LiteLLM Integration**, enter the API URL, optional API key, and system prompt, then pick a model. The key is stored in IntelliJ's Password Safe rather than in the persistent settings XML.

### Model list

Once the URL and key are filled in, the **Model** dropdown loads itself from `GET {base}/models` — when the page opens, again shortly after you edit either field, and on demand via **Refresh**. That endpoint is key-scoped on a LiteLLM gateway, so the list is exactly what your key may reach; swapping the key swaps the list, which is how a single field covers separate claude and codex allowlists.

The dropdown is selection-only: a hand-typed id the key cannot serve would just fail later at generation time, so the gateway's own list is the only source of valid values. A failed load still never clears your saved model — it stays in the list and selected, because nothing else could put it back.

Changing the key does drop a model the new key cannot serve, and says so, since the allowlists do not overlap.

### API Base URL

Enter the **gateway root only** — `https://host` is enough. The plugin knows only that it is talking to a LiteLLM-compatible gateway, never which one, so it derives `/models`, `/chat/completions` and `/messages` itself. A hint under the field shows exactly what it will call.

A trailing `/messages`, `/chat/completions` or `/responses` is stripped, and `/v1` is appended only when no path is given at all:

| you enter | base becomes |
| --- | --- |
| `https://host` | `https://host/v1` |
| `https://host/v1` | `https://host/v1` |
| `https://host/v1/messages` | `https://host/v1` |
| `https://host/llm/v1` | `https://host/llm/v1` — sub-path left alone |

Normalisation runs on load and on save, so a setting from when this field held a full endpoint URL is migrated in place the first time you open the IDE; the field then shows the base the plugin actually uses rather than a path it ignores.

### Request

The client posts non-streaming Chat Completions, which on a LiteLLM gateway serves every model — claude, gpt, glm, kimi and deepseek alike:

```json
{
  "model": "your-model",
  "max_completion_tokens": 2048,
  "stream": false,
  "messages": [
    { "role": "system", "content": "..." },
    { "role": "user", "content": "Generate a commit message..." }
  ]
}
```

Two details are not interchangeable with the Anthropic body: the cap must be `max_completion_tokens`, because some backends reject `max_tokens` outright, and the system prompt must be a *message* — a top-level `system` property is refused as an unpermitted extra input.

If `{base}/chat/completions` does not exist (404/405), the client retries once against `{base}/messages` with the Anthropic body and `anthropic-version: 2023-06-01`, so a plain Anthropic endpoint still works. Any other status is reported as-is rather than papered over, and the resolved dialect is remembered per gateway for the session. Responses are read from `choices[0].message.content` or Anthropic `content[]` blocks. `x-api-key` and `Authorization: Bearer` are sent only when a key is configured.

## Inline code completion

Pause while typing and the plugin asks the gateway to continue the code at your caret, rendering the reply as grey ghost text; <kbd>Tab</kbd> accepts, <kbd>Esc</kbd> dismisses. Invoking inline completion explicitly (⌥\ by default) skips the wait and works even with the checkbox off.

| Setting | Effect |
| --- | --- |
| **Completion Model** | Model used for suggestions, chosen from the same auto-loaded list |
| **Suggest completions while typing** | Off = suggestions only on explicit invocation |

### Status bar

A **LiteLLM** widget in the status bar reports what completion is doing, because the feature is otherwise silent and "slow" and "broken" look identical from the outside:

| Shows | Meaning |
| --- | --- |
| `LiteLLM: off` | no base URL or completion model configured |
| `LiteLLM: idle` | ready, nothing requested yet |
| `LiteLLM: thinking…` | a request is in flight |
| `LiteLLM: 1.4s` | last suggestion, with its round-trip time |
| `LiteLLM: 1.4s (none)` | the model answered but had nothing to suggest |
| `LiteLLM: failed` | hover for the reason |

The timing is the diagnostic: if it reads 2s, the gateway is slow, not the plugin.

### Pick a fast model

This one setting decides whether the feature is usable. Measured against this gateway with the real completion prompt:

| Model | Latency | Note |
| --- | --- | --- |
| `gpt-5.4-mini` | **~0.5s** | best choice |
| `gpt-5.3-codex` | ~1.5s | |
| `gpt-5.4` | ~1.6s | |
| `gpt-5.6-terra` | ~2.5s | |
| `gpt-5.1-codex-mini` | ~3.7s | **unusable** — spends the entire token budget reasoning and returns nothing |

Add the 400 ms debounce on top. It will not match Copilot's ~200ms in any case: that runs a small model at the edge, this runs a general model across a corporate gateway.

Requests fire only after a 400 ms pause and typing again cancels the one in flight, so holding a key down costs nothing. What gets sent is the code around the caret — roughly 60 lines before and 20 after, capped by character count — plus the file name and language. The Git diff is *not* included; suggestions follow the code you are writing, not your uncommitted changes.

Replies are sanitised before display, because the prompt alone does not stop models from wrapping output in fences, restating the line you are on, or re-closing a brace the file already closes — and unlike a chat answer, a bad suggestion here lands in your file on <kbd>Tab</kbd>. Failures are logged and never notified; a flaky gateway must not produce a balloon per keystroke.

## Build and run

```bash
bash ./gradlew buildPlugin
bash ./gradlew runIde
```

The project targets IntelliJ IDEA 2025.3.5 and Java 21. The action is registered in `ChangesView.CommitToolbar`, receives the active commit-message control, and reads `COMMIT_WORKFLOW_UI.includedChanges` plus `includedUnversionedFiles` at click time. This means a new message is generated from exactly the files currently checked for commit, never from unrelated working-tree changes.

### Screenshot
<img src="img1.png" alt="Screenshot 1"/>
<img src="img2.png" alt="Screenshot 2"/>