# Changelog

All notable changes to LiteLLM Integration are documented in this file.

## [Unreleased]

### Added

- Status-bar widget reporting inline-completion state: in progress, the last round-trip time, "no suggestion", or failed with the reason in the tooltip. Added because completion is otherwise silent, which makes a slow gateway indistinguishable from a broken one.
- Inline code completion: Copilot-style ghost text from the configured gateway, debounced 400 ms and cancelled when typing resumes, with its own model setting and an on/off toggle. Explicit invocation skips the debounce and works with the toggle off. Replies are sanitised — fences stripped, echoed lines and duplicate closing braces removed — since a bad suggestion here is inserted into the file on Tab.
- Commit-toolbar action for generating a commit message from the files currently included in the IntelliJ Commit tool window.
- Configurable HTTP API settings with secure API-key storage in IntelliJ Password Safe.
- Support for tracked changes and included unversioned files.
- Background generation progress, request/response diagnostics, and user-facing error notifications.
- Model dropdown populated from the gateway's `/models` endpoint, refreshed when the API URL or key changes and via a Refresh button. Selection only — models come from the gateway rather than being typed — while a saved model is retained if the list cannot be loaded.
- Support for every model a LiteLLM gateway serves, not just the Anthropic-format ones: requests now use non-streaming Chat Completions with `max_completion_tokens`, falling back to the Anthropic Messages format only when that route is absent.

### Fixed

- The API key is no longer read from Password Safe on the EDT for every keystroke. Inline completion's enabled-check now reads only non-secret settings, and the key is cached and invalidated on save. Password Safe reads trip the platform's slow-operation assertion (`SEVERE - SlowOperations - Plugin to blame`) and added credential-store latency to typing.

### Changed

- Renamed from **Custom Commit AI** to **LiteLLM Integration**. The plugin id, Kotlin package, settings storage file and Password Safe key are deliberately unchanged, so existing configuration — including the stored API key — survives the rename.

- The API URL setting is now an **API Base URL**: enter the gateway root and the plugin derives `/models`, `/chat/completions` and `/messages` from it, with a hint under the field showing what it will call. A trailing endpoint segment is stripped and `/v1` added when no path is given, so `https://host` alone is enough. Existing full-endpoint URLs are normalised in place on load, so nothing breaks and the field stops displaying a path the plugin ignores.
