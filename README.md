# IntelliJ Context Bridge

## Overview
A native IntelliJ/Android Studio plugin designed to connect the IDE with web-based LLM playgrounds (such as Google AI Studio). Inspired by the workflow of [CodeWebChat](https://github.com/robertpiosik/CodeWebChat), it acts as an optimized, stateful context manager. It compiles project context into LLM-friendly payloads and synchronizes them via the clipboard or a local WebSocket server, providing an alternative to direct API integrations.

*Note: This project is actively developed through dogfooding—using the plugin itself alongside advanced LLMs to write, review, and refine its own codebase.*

## Core Mechanics & Philosophy
1. **User-Directed Workflow:** The AI does not autonomously read or write files. The user explicitly selects the context to share and safely reviews or applies generated code changes using IntelliJ's native `DiffManager`.
2. **Token Efficiency:** Prioritizes a "Skeleton" mode (AST-based extraction of signatures, class structures, and documentation) over full-text extraction to conserve tokens and maintain LLM focus.
3. **Intent-Based Interaction:** Differentiates between read-only analysis ("Ask") and code generation ("Edit"), dynamically swapping system instructions to guide the AI's output format.
4. **Local Network Bridge:** Operates via a companion browser userscript that communicates with the IDE over a local WebSocket, securely transferring prompts and retrieving responses.
5. **State & Deduplication:** Tracks conversation turns and file states, automatically omitting unchanged files from subsequent payloads to prevent context window bloat.
6. **Diagnostic Awareness:** (Planned) Supports injecting active IDE compiler errors and warnings directly into the payload, providing deterministic constraints for the AI to resolve.

## Development Setup Requirements
* IntelliJ Platform Plugin Template (Kotlin)
* Gradle

![Build](https://github.com/Bumblebee202111/intellij-context-bridge/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [group](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml), [name](./src/main/resources/META-INF/plugin.xml), and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin [description](./src/main/resources/META-INF/plugin.xml) (see [Tips][docs:plugin-description]) and this README to describe what your plugin does.
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

This Fancy IntelliJ Platform Plugin is going to be your implementation of the brilliant ideas that you have.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "intellij-context-bridge"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/Bumblebee202111/intellij-context-bridge/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
