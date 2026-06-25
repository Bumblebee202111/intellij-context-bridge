# Development Roadmap

This project is structured into distinct, sequential phases. Each phase must be fully functional before moving to the next to ensure a stable foundation.

## Phase 1: The MVP (Context & Clipboard)
**Goal:** A working IntelliJ tool window that can extract context and copy it to the clipboard.
* [x] Initialize IntelliJ Platform Plugin Template (Kotlin).
* [ ] Build basic Tool Window UI (File Tree with `Skeleton` / `Full` toggles).
* [ ] Implement PSI Parser logic for `Skeleton` mode (extracting strictly Public API, KDoc, and required imports; stripping bodies/private members).
* [ ] Implement basic payload generation (formatting selected files into structured Markdown).
* [ ] Add "Copy to Clipboard" functionality.

## Phase 2: State Management & Deduplication
**Goal:** Prevent context bloat during long conversations.
* [ ] Implement Session Tracker (start/clear conversation state).
* [ ] Implement file hashing for files sent as `Full`.
* [ ] Update payload generator: silently downgrade `Full` to `Skeleton` (or omit) if the file hash matches the current session state.
* [ ] Inject the invisible LLM Protocol Prompt (instructing the AI to use `REQUEST_FULL: [filepath]`) into the payload.

## Phase 3: Diff Application
**Goal:** Safely parse AI responses and apply code back to the IDE.
* [ ] Add UI to paste/receive AI Markdown responses.
* [ ] Write parser to extract code blocks and match them to local file paths based on Markdown headers.
* [ ] Integrate IntelliJ `DiffManager` to open side-by-side diffs for user approval instead of auto-overwriting files.

## Phase 4: The Automation Bridge (WebSocket)
**Goal:** Eliminate manual copy/pasting to the browser.
* [ ] Embed a lightweight local WebSocket server (`localhost:PORT`) in the plugin.
* [ ] Update UI with a "Send via WebSocket" action.
* [ ] Write a companion browser Userscript (Tampermonkey/Violentmonkey) to receive the payload, inject it into the AI Studio web UI, and send the generated response back to the IDE.

## Phase 5: Polish & Project Configuration
**Goal:** Improve UX and handle edge cases.
* [ ] Implement `.aicontext` parser to auto-load default files, folders, and custom prompts on project open.
* [ ] Implement MIME/Type checking to ignore binaries and prompt user action for images.
* [ ] Refine UI/UX (saving prompt history, better error handling).