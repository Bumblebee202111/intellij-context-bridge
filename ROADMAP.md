# Development Roadmap

This project is structured into distinct, sequential phases. Each phase must be fully functional before moving to the next to ensure a stable foundation.

## Phase 1: The MVP (Context & Clipboard)
**Goal:** A working IntelliJ tool window that can extract context and copy it to the clipboard.
* [x] Initialize IntelliJ Platform Plugin Template (Kotlin).
* [x] Build basic Tool Window UI (File Tree with `Skeleton` / `Full` toggles).
* [x] Implement PSI Parser logic for `Skeleton` mode (extracting strictly Public API, KDoc, and required imports; stripping bodies/private members).
* [x] Implement basic payload generation (formatting selected files into structured Markdown).
* [x] Add "Copy to Clipboard" functionality.

## Phase 2: State Management & Deduplication
**Goal:** Prevent context bloat during long conversations.
* [x] Implement Session Tracker (start/clear conversation state).
* [x] Implement file hashing for files sent as `Full`.
* [x] Update payload generator: silently downgrade `Full` to `Skeleton` (or omit) if the file hash matches the current session state.
* [x] Inject the invisible LLM Protocol Prompt (instructing the AI to use `REQUEST_FULL: [filepath]`) into the payload.

## Phase 3: Diff Application
**Goal:** Safely parse AI responses and apply code back to the IDE.
* [x] Add UI to paste/receive AI Markdown responses.
* [x] Write parser to extract code blocks and match them to local file paths based on Markdown headers.
* [x] Integrate IntelliJ `DiffManager` to open side-by-side diffs for user approval instead of auto-overwriting files.

## Phase 4: The Automation Bridge (WebSocket)
**Goal:** Eliminate manual copy/pasting to the browser.
* [x] Embed a lightweight local WebSocket server (`localhost:PORT`) in the plugin.
* [x] Update UI with a "Send via WebSocket" action.
* [x] Write a companion browser Userscript (Tampermonkey/Violentmonkey) to receive the payload, inject it into the AI Studio web UI, and send the generated response back to the IDE.

## Phase 5: Polish & Project Configuration
**Goal:** Improve UX and handle edge cases.
* [x] Implement `.aicontext` parser to auto-load default context routing on project open.
* [x] Implement MIME/Type checking to exclude opaque binaries and support media attachments.
* [x] Refine UI/UX (prompt history stack, better error handling).

## Phase 6: Engineering Context (Git & Diagnostics)
**Goal:** Provide the AI with deterministic software engineering constraints.
* [ ] Add UI toggle to include active editor compiler errors/warnings in the payload.
* [ ] Add action to auto-select uncommitted/modified files based on `git status`.
* [ ] Optimize payload by sending Git diffs for modified files already in memory.

## Phase 7: Read-Only Tool Calling (Planned)
**Goal:** Allow the AI to query the codebase safely without autonomous write access.
* [ ] Define XML-based tool call schema for system directives.
* [ ] Implement IDE-side execution for safe, read-only queries (e.g., global search, find usages).
* [ ] Automate tool result transmission back to the web UI.