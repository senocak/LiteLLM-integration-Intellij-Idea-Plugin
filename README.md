# Custom Commit AI

Minimal IntelliJ IDEA plugin that adds a small **Generate Commit Message** action to the Commit tool window. It reads only the changes currently included for commit, sends their textual diff to the configured HTTP endpoint, and replaces the commit-message field with the response. It never commits automatically.

## Configure

In **Settings | Tools | Custom Commit AI**, enter the API URL, optional API key, and system prompt, then pick a model. The key is stored in IntelliJ's Password Safe rather than in the persistent settings XML.

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

## Build and run

```bash
bash ./gradlew buildPlugin
bash ./gradlew runIde
```

The project targets IntelliJ IDEA 2025.3.5 and Java 21. The action is registered in `ChangesView.CommitToolbar`, receives the active commit-message control, and reads `COMMIT_WORKFLOW_UI.includedChanges` plus `includedUnversionedFiles` at click time. This means a new message is generated from exactly the files currently checked for commit, never from unrelated working-tree changes.

### Screenshot
<img src="img1.png" alt="Screenshot 1"/>
<img src="img2.png" alt="Screenshot 2"/>