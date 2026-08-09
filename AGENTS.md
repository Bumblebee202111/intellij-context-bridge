# AI Context & Domain Knowledge

This document provides essential domain knowledge for developing the IntelliJ Context Bridge.

## 1. Architectural Inspiration
Originally inspired by CodeWebChat, we are building a user-directed context bridge utilizing a visual UI tree and WebSocket transport. You should draw general inspiration from the latest dominant coding agents (like Claude Code and Codex) to enhance our workflow, natively adapting their best practices to our existing architecture.

## 2. Tech Stack & Knowledge Verification
Assume the 2026 ecosystem. Always use the latest available versions for Kotlin 2.x, Ktor 3.x, and the IntelliJ Platform Gradle Plugin.
- All background work MUST use IntelliJ's platform-managed Coroutine scopes and yield via `readAction`.
- Always trigger a Google Search to fetch the latest JetBrains documentation before generating code for IntelliJ Platform APIs or UI components.

## 3. Google AI Studio Web UI
Our userscript automates the Google AI Studio web interface. Keep its modern Angular-based SPA structure in mind when modifying the script:
- **System Instructions:** Managed via a clickable card that opens an overlay dialog.
- **Model Selection:** Features model selectors to switch between highly capable models (e.g., Gemini 3.1 Pro Preview, Gemini 3.6 Flash).
- **Attachments:** Media and file uploads rely on simulated drag-and-drop events rather than standard inputs.
- **Chat Interface:** AI responses are rendered in distinct chat turn containers which house the native copy and action buttons we intercept.