# Changelog

All notable changes to Auto Commit Generator are documented in this file.

## [Unreleased]

### Added

- Commit-toolbar action for generating a commit message from the files currently included in the IntelliJ Commit tool window.
- Configurable HTTP API settings with secure API-key storage in IntelliJ Password Safe.
- Support for tracked changes and included unversioned files.
- Background generation progress, request/response diagnostics, and user-facing error notifications.
- Model dropdown populated from the gateway's `/models` endpoint, refreshed when the API URL or key changes and via a Refresh button. Selection only — models come from the gateway rather than being typed — while a saved model is retained if the list cannot be loaded.
- Support for every model a LiteLLM gateway serves, not just the Anthropic-format ones: requests now use non-streaming Chat Completions with `max_completion_tokens`, falling back to the Anthropic Messages format only when that route is absent.

### Changed

- The API URL setting is now an **API Base URL**: enter the gateway root and the plugin derives `/models`, `/chat/completions` and `/messages` from it, with a hint under the field showing what it will call. A trailing endpoint segment is stripped and `/v1` added when no path is given, so `https://host` alone is enough. Existing full-endpoint URLs are normalised in place on load, so nothing breaks and the field stops displaying a path the plugin ignores.
