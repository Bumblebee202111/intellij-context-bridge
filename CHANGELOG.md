<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-context-bridge Changelog

*Note: As this is an experimental project primarily for personal use, this changelog is maintained informally. It tracks high-level capabilities rather than strict semantic versioning or granular commits.*

## [Unreleased]
### Core Capabilities Implemented
- **Context Management**: File Tree UI with computed visual states (Complete, Skeleton, None) and `.aicontext` auto-routing.
- **Smart Extraction**: AST-based Skeleton extractor for Kotlin/Java (retains signatures/docs, strips bodies) and capability checks for media/binaries.
- **Tool Calling & Gatekeeping**: Intercepts XML-based AI tool calls (e.g., `read_file`) and routes them to a "Pending AI Requests" UI group for 1-click manual approval.
- **Proactive Context Suggestions**: Background engine suggesting relevant files based on Git changes, active tabs, prompt mentions, and PSI graph traversal. Acts as a smart staging area that auto-suppresses files already in the deduplication cache.
- **Performance & Stability**: Heavy AST traversals run on yielding `readAction` coroutines. Event listeners are strictly tied to UI-bound disposables, backed by thread-safe registries to prevent memory leaks and event storms.
- **Intent-Based Modes**: Unified system instructions supporting "Ask" (read-only analysis) and "Edit" (code generation) via XML micro-anchoring (`<user_prompt mode="...">`) for strong LLM adherence.
- **Session History & Deduplication**: Persistent timeline of user turns with true undo capabilities and automatic context deduplication to save tokens.
- **Mesh Networking & Routing**: Dynamic WebSocket server supporting multiple concurrent IDE instances, utilizing a hybrid bi-directional binding system (trusting chat titles persistently, pathnames transiently) to route payloads accurately.
- **Zero-Click Sync**: Companion userscript automates native UI actions (e.g., "Copy as Markdown") to reliably extract sanitized AI responses, completely bypassing brittle DOM parsing and internal LLM thoughts.
- **Agentic CRUD Tools**: Parses XML-based tool calls (`propose_edit`, `delete_file`, `rename_file`) and routes them to visual diffs or native IDE refactoring APIs.
- **Ephemeral Workflows**: Native AI Commit Generation via `VcsDataKeys` utilizing transient LLM state machines (auto-configuring models/settings, generating, and auto-scrubbing the chat history).
- **Diff Application**: Native IntelliJ `DiffManager` integration for safe, visual code application with streamlined multi-diff execution (auto-advance).
- **Native UI**: Upgraded composer panel with IntelliJ native components (`EditorTextField`, `ActionToolbar`, `TreeSpeedSearch`) for a seamless IDE feel.
- **Slash Commands**: Markdown-based macro system (`/plan`, `/lore`) with project-level shadowing (`.contextbridge/commands/`) and Web UI text-expansion sync.
- **Web UI Robustness**: Trusted Types compliance and Capture-Phase interception for native AI Studio shortcuts (`Ctrl+Enter`).
- **Command Engine Refinement**: Stacked slash commands with UI token insertion, reactive mode elevation, and `<applied_commands>` XML semantic boundaries.
- **Tool-Specific XML Tags & Prompt Resilience**: Migrated to specific tags (e.g., `<propose_edit>`) and "de-weaponized" their schema definitions in the system prompt to successfully bypass aggressive RLHF native function-calling triggers in AI Studio.
- **Code Block Jailbreak & Tool Namespacing**: Introduced `ide:` XML namespace and strict Markdown code block wrappers for tool calls to reliably bypass AI Studio's native function-calling UI interception, especially when Grounding is enabled.
- **Skeleton Patch Protocol Refinement**: Cured LLM "Diff Anchor Paranoia" by strictly enforcing that unchanged blocks retain ONLY their signatures/headers with zero internal lines, backed by concrete few-shot examples.
- **UX Improvement**: Removed auto-collapse behavior in the Context Tree to preserve user expansion state and visual feedback during rapid context toggling.