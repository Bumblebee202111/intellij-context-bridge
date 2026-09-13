# AI Context & Domain Knowledge

This document provides essential domain knowledge for developing the IntelliJ Context Bridge.

## 1. Architectural Inspiration
Originally inspired by CodeWebChat, we are building a user-directed context bridge utilizing a visual UI tree and WebSocket transport. Draw general inspiration from the latest dominant 2026 coding agents (like Claude Code and Codex) to enhance our workflow. Embrace structured XML tool schemas for refrained agentic actions (Create, Read, Update, Delete) while maintaining a human-in-the-loop philosophy via visual diffs and explicit intent modes.

## 2. Tech Stack & Post-2025 Constraints
Assume the 2026 ecosystem. Always use the latest available versions for Kotlin 2.x, Ktor 3.x, and the IntelliJ Platform Gradle Plugin 2.x.
- **Coroutines:** All background work MUST use platform-managed Coroutine scopes and yield via suspending `readAction`.
- **Memory:** Bind event listeners (e.g., `MessageBus`) to a UI-bound `Disposable` (like Tool Window Content), never the `Project`.
- **Swing Resolution:** `DataContext` resolves *upward*. To find nested child components, MUST use downward traversal (e.g., `UIUtil.findComponentOfType`).
- Always trigger a Google Search to fetch the latest JetBrains documentation before generating code for IntelliJ Platform APIs or UI components.

## 3. Web UI Automation
Automates Google AI Studio via a companion userscript.
👉 **See `docs/ai-studio-web-ui.md` for comprehensive DOM topology, Trusted Types constraints, and Angular event lifecycle facts.**