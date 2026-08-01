<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-context-bridge Changelog

*Note: As this is an experimental project primarily for personal use, this changelog is maintained informally. It tracks high-level capabilities rather than strict semantic versioning or granular commits.*

## [Unreleased]
### Core Capabilities Implemented
- **Context Management**: File Tree UI with computed visual states (Complete, Skeleton, None) and `.aicontext` auto-routing.
- **Smart Extraction**: AST-based Skeleton extractor for Kotlin/Java (retains signatures/docs, strips bodies) and capability checks for media/binaries.
- **Tool Calling & Gatekeeping**: Intercepts XML-based AI tool calls (e.g., `read_file`) and routes them to a "Pending AI Requests" UI group for 1-click manual approval.
- **Proactive Context Suggestions**: Background engine suggesting relevant files based on Git changes, active tabs, prompt mentions, and PSI graph traversal. Acts as a smart staging area that auto-suppresses files already in the deduplication cache.
- **Performance & Coroutines**: Heavy AST traversals and hashing run on yielding `readAction` coroutines, completely eliminating IDE typing freezes.
- **Intent-Based Modes**: Dynamic system instructions supporting "Ask" (read-only analysis) and "Edit" (code generation).
- **Session History & Deduplication**: Persistent timeline of user turns with true undo capabilities and automatic context deduplication to save tokens.
- **Mesh Networking Bridge**: Dynamic WebSocket server supporting multiple concurrent IDE instances, paired with a Tampermonkey userscript for automated AI Studio injection and extraction.
- **Diff Application**: Markdown parsing and native IntelliJ `DiffManager` integration for safe, visual code application.
- **Native UI**: Upgraded composer panel with IntelliJ native components (`EditorTextField`, `ActionToolbar`) for a seamless IDE feel.