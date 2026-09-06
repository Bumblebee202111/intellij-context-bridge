# AI Context & Domain Knowledge

This document provides essential domain knowledge for developing the IntelliJ Context Bridge.

## 1. Architectural Inspiration
Originally inspired by CodeWebChat, we are building a user-directed context bridge utilizing a visual UI tree and WebSocket transport. Draw general inspiration from the latest dominant 2026 coding agents (like Claude Code and Codex) to enhance our workflow. Embrace structured XML tool schemas for refrained agentic actions (Create, Read, Update, Delete) while maintaining a human-in-the-loop philosophy via visual diffs and explicit intent modes.

## 2. Tech Stack & Knowledge Verification
Assume the 2026 ecosystem. Always use the latest available versions for Kotlin 2.x, Ktor 3.x, and the IntelliJ Platform Gradle Plugin.
- All background work MUST use IntelliJ's platform-managed Coroutine scopes and yield via `readAction`.
- Bind event listeners (e.g., `MessageBus`, `EditorFactory`) to a UI-bound `Disposable` (like Tool Window Content), never the `Project`, to prevent memory leaks.
- Use thread-safe collections (`CopyOnWriteArrayList`, `ConcurrentHashMap`) for state and WebSocket listener registries.
- Always trigger a Google Search to fetch the latest JetBrains documentation before generating code for IntelliJ Platform APIs or UI components.

## 3. Google AI Studio Web UI
Keep the platform's modern Angular-based SPA structure and security constraints in mind when building web automation:
- **DOM Security:** The environment enforces Trusted Types, blocking raw `innerHTML` assignments. Safe native DOM APIs are required.
- **Markdown Rendering:** Its internal Markdown parser natively supports syntax highlighting for code blocks (using triple backticks) even when they are nested inside raw XML tags.
- **Event Handling:** Relies heavily on Angular's internal event propagation for submission shortcuts (e.g., `Ctrl+Enter`).
- **System Instructions:** Managed via a clickable card that opens a `mat-dialog-container` overlay.
- **Generation & Extraction Surface:** Upon submission, the UI displays a temporary, universal "Thinking" indicator. For reasoning models, this is dynamically replaced by an explicit, expandable "Thoughts" block. The final response is rendered in a distinct chat turn container housing a native "Copy" button, which yields clean, sanitized Markdown. (Note: Native UI interception blocks, like function calls, bypass this extraction).
- **Native Function Calls:** Supports Function Calling. Intercepts native control tokens (often triggered by standard "tool" schemas) and renders an interactive `<ms-function-call-chunk>`, halting standard text generation.
- **Session Identity:** New sessions default to generic titles like "Playground" or "Untitled prompt". After the first generation, the title auto-updates and becomes the most stable persistent identifier for the conversation. URL pathnames also update dynamically, but their structure varies depending on whether it is a shared link or based on the entry point (e.g., `/prompts` vs. `/app/prompts`).
- **Parameter Controls:** Features specific UI components for Model selection (e.g., **Gemini 3.1 Pro Preview** as the primary capability driver, or Gemini 3.7 Flash for speed and independent quota), Thinking Level, and Temperature. Note that changing the selected model automatically resets other parameters to their default states.
- **Attachments:** Media and file uploads are processed via global drag-and-drop event listeners rather than standard `<input type="file">` elements.