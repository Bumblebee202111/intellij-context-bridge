# AI Context & Domain Knowledge

This document provides essential domain knowledge for developing the IntelliJ Context Bridge.

## 1. Architectural Inspiration
Originally inspired by CodeWebChat, we are building a user-directed context bridge utilizing a visual UI tree and WebSocket transport. Draw general inspiration from the latest/2026 dominant coding agents (like Claude Code and Codex) to enhance our workflow, natively adapting their best practices (such as slash commands) to our existing architecture.

## 2. Tech Stack & Knowledge Verification
Assume the 2026 ecosystem. Always use the latest available versions for Kotlin 2.x, Ktor 3.x, and the IntelliJ Platform Gradle Plugin.
- All background work MUST use IntelliJ's platform-managed Coroutine scopes and yield via `readAction`.
- Bind event listeners (e.g., `MessageBus`, `EditorFactory`) to a UI-bound `Disposable` (like Tool Window Content), never the `Project`, to prevent memory leaks.
- Use thread-safe collections (`CopyOnWriteArrayList`, `ConcurrentHashMap`) for state and WebSocket listener registries.
- Always trigger a Google Search to fetch the latest JetBrains documentation before generating code for IntelliJ Platform APIs or UI components.

## 3. Google AI Studio Web UI
Our userscript automates the Google AI Studio web interface. Keep its modern Angular-based SPA structure and security constraints in mind when modifying the script:
- **DOM Security:** The environment enforces Trusted Types, blocking raw `innerHTML` assignments. Safe native DOM APIs (e.g., `createElement`, `textContent`) are required.
- **Event Handling:** Native submission shortcuts (e.g., `Ctrl+Enter`) must be intercepted during the Capture Phase to safely wrap payloads before Angular's internal state triggers.
- **System Instructions:** Managed via a clickable card that opens an overlay dialog.
- **Model Selection:** Features model selectors to switch between highly capable models (e.g., Gemini 3.1 Pro Preview, Gemini 3.6 Flash).
- **Extraction:** Automate native UI actions (e.g., clicking "Copy" to extract sanitized Markdown) rather than parsing volatile DOM elements.
- **Session Binding:** Trust the chat title as the persistent anchor. Use the URL pathname strictly as a transient delta-checker for renames vs. navigations.
- **Transient States:** Use state machines to temporarily override UI settings (Model, Thinking Level) for ephemeral tasks, restoring defaults afterward.
- **Attachments:** Media and file uploads rely on simulated drag-and-drop events rather than standard inputs.
- **Chat Interface:** AI responses are rendered in distinct chat turn containers which house the native copy and action buttons we intercept.