# IntelliJ Context Bridge

> ⚠️ **Disclaimer: Experimental & As-Is**
> This is a personal dogfooding project open-sourced for educational purposes and reference.
> * **No Pre-built Binaries:** You must build the plugin from source yourself.
> * **No Support:** You may open issues for discussion or to report UI breaks, but I do not guarantee updates or bug fixes.
> * **No Pull Requests:** I am not accepting PRs at this time.
> * **Web UI Automation:** The companion userscript interacts with Google AI Studio by automating the web UI (DOM manipulation) rather than using an official API. This means it will naturally break whenever Google updates their frontend layout. Use and adapt it at your discretion.

## Overview
A native IntelliJ/Android Studio plugin designed to connect the IDE with web-based LLM playgrounds (such as Google AI Studio). Inspired by the workflow of [CodeWebChat](https://github.com/robertpiosik/CodeWebChat), it acts as an optimized, stateful context manager. It compiles project context into LLM-friendly payloads and synchronizes them via the clipboard or a local WebSocket server, providing an alternative to direct API integrations.

*Note: This project is actively developed through dogfooding—using the plugin itself alongside advanced LLMs to write, review, and refine its own codebase.*

## Core Mechanics & Philosophy
1. **User-Directed Workflow:** The AI does not autonomously read or write files. The user explicitly selects the context to share, manually approves XML-based AI tool requests, and safely reviews generated code changes using IntelliJ's native `DiffManager`.
2. **Token Efficiency:** Balances complete file contexts with a specialized "Skeleton" modifier (AST-based extraction of signatures and structures) for peripheral dependencies. This conserves tokens and maintains LLM focus without losing architectural awareness.
3. **Proactive Context Suggestions:** A lightweight, background engine intelligently suggests relevant files based on Git changes, active editor tabs, prompt mentions, and deep AST graph traversal, actively suppressing files already cached in the AI's memory.
4. **Intent-Based Interaction:** Differentiates between read-only analysis ("Ask") and code generation ("Edit"), dynamically swapping system instructions to guide the AI's output format.
5. **Local Network Bridge:** Operates via a companion browser userscript that communicates with the IDE over a local WebSocket, securely transferring prompts and retrieving responses.
6. **State & Deduplication:** Tracks conversation turns and file states, automatically omitting unchanged files from subsequent payloads to prevent context window bloat.
7. **Native IDE Feel:** Built using standard IntelliJ UI components to ensure keyboard shortcuts, editor behaviors, and layout scaling feel identical to native IDE features, backed by yielding Coroutines to prevent typing freezes.
8. **Diagnostic Awareness:** (Planned) Supports injecting active IDE compiler errors and warnings directly into the payload, providing deterministic constraints for the AI to resolve.

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