# IntelliJ Context Bridge

> ⚠️ **Disclaimer: Experimental & As-Is**
> This is a personal dogfooding project open-sourced for educational purposes and reference.
> * **No Pre-built Binaries:** You must build the plugin from source yourself.
> * **No Support:** You may open issues for discussion or to report UI breaks, but I do not guarantee updates or bug fixes.
> * **No Pull Requests:** I am not accepting PRs at this time.
> * **Web UI Automation:** The companion userscript interacts with Google AI Studio by automating the web UI. To ensure resilience, it relies on triggering native UI actions (like the "Copy" button) rather than brittle DOM scraping, but it will still naturally require updates whenever Google significantly alters their frontend layout. Use and adapt it at your discretion.

## Overview
A native IntelliJ/Android Studio plugin designed to connect the IDE with web-based LLM playgrounds (such as Google AI Studio). Inspired by the workflow of [CodeWebChat](https://github.com/robertpiosik/CodeWebChat), it acts as an optimized, stateful context manager. It compiles project context into LLM-friendly payloads and synchronizes them via the clipboard or a local WebSocket server, providing an alternative to direct API integrations.

*Note: This project is actively developed through dogfooding—using the plugin itself alongside advanced LLMs to write, review, and refine its own codebase.*

## Core Mechanics & Philosophy
1. **User-Directed Workflow:** The AI does not autonomously read or write files. The user explicitly selects context (via the file tree or inline `@` mentions), manually approves AI tool requests, and reviews generated code changes using IntelliJ's native `DiffManager` and Refactoring APIs.
2. **Token Efficiency:** Balances complete file contexts with an AST-based "Skeleton" mode (extracting signatures and structures for Kotlin, Java, JS, TS, and Vue) to conserve tokens without losing architectural awareness across JetBrains IDEs.
3. **Proactive Context Suggestions:** A background engine suggests relevant files based on Git changes, open editors, `@` mentions, and AST dependency/usage graphs while suppressing files already cached in the session.
4. **Intent-Based Interaction:** Differentiates between read-only analysis ("Ask") and code generation ("Edit") using a unified system prompt, XML micro-anchoring, and namespaced Markdown/CDATA tool blocks.
5. **Slash Commands & Dynamic Context:** Supports stackable macro workflows (e.g., `/commit`, `/review-bugs`, `/plan`, `/lore`) defined via Markdown frontmatter with local project shadowing and runtime variable interpolation (e.g., `{{vcs_diff}}`).
6. **Local Network Bridge & Polite Coexistence:** Connects to Google AI Studio over a local WebSocket via a companion userscript with multi-project binding, per-model parameter memory, and a two-way diagnostic bridge. Implicitly detects Bridge sessions via System Instructions so normal AI Studio chats remain untouched.
7. **State, Deduplication & Ephemeral Workflows:** Tracks conversation turns with full undo support, omits unchanged files from subsequent payloads, and runs ephemeral tasks (like AI Commit Generation) via state snapshots without polluting chat history.
8. **Native IDE Feel:** Built using standard IntelliJ UI components and non-blocking background coroutines so editor and tree interactions feel native.

## Development Setup Requirements
* IntelliJ Platform Plugin Template (Kotlin)
* Gradle

## Building & Installation

Because there are no pre-packaged releases, you must build the plugin locally using Gradle.

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Bumblebee202111/intellij-context-bridge.git
   cd intellij-context-bridge
   ```
2. **Build the plugin:**
   ```bash
   ./gradlew buildPlugin
   ```
3. **Install in IntelliJ/Android Studio:**
   * Go to <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>
   * Select the generated ZIP file located at `build/distributions/intellij-context-bridge-X.X.X.zip`.
4. **Install the Userscript:**
   * Install a userscript manager like Tampermonkey in your browser.
   * Add the script located at `userscripts/intellij-context-bridge.user.js`.