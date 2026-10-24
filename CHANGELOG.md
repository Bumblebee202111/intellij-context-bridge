<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-context-bridge Changelog

*Note: As this is an experimental project primarily for personal use, this changelog is maintained informally. It tracks high-level capabilities rather than strict semantic versioning or granular commits.*

## [Unreleased]
### Core Capabilities Implemented
- **Explicit `@` Context Mentions**: Inline `@` file/folder auto-completion in the prompt composer with automatic promotion to `Complete` context.
- **Granular Read Actions & Graph Caching**: Off-EDT tree rendering, yielding `readAction` coroutines, and per-seed PSI graph caching by `modificationStamp`.
- **Cross-IDE & Multi-Language Support**: Optional plugin dependencies and AST Skeleton/Graph extractors for Kotlin, Java, JavaScript, TypeScript, and Vue.
- **Polite Userscript & Per-Model Memory**: Implicit Bridge chat detection via System Instructions, persistent per-model parameter memory, and exact state snapshot restoration.
- **Diagnostic Bridge**: Two-way IDE-to-userscript telemetry to verify background DOM automation health under browser throttling.
- **Slash Commands & Meta-Commands**: Stackable `/plan`, `/lore`, `/commit`, `/cmd`, and targeted `/review-*` macros with `.contextbridge/commands/` shadowing and `{{vcs_diff}}` interpolation.
- **Resilient Tool Protocol**: Unified `AgentTool` hierarchy using namespaced `ide:` XML blocks inside Markdown code blocks and `<![CDATA[...]]>` payloads, paired with the Skeleton Patch protocol.
- **Interactive Response Viewer**: Unified response panel for reviewing and executing `read_file`, `propose_edit` (via native `DiffManager`), `rename_file`, `delete_file`, and `fill_commit_message` tools.
- **Ephemeral Commit Workflow**: One-click AI commit message generation from VCS panels with transient model configuration, chat cleanup, and downward Swing input injection.
- **Proactive Context Suggestions**: Background suggestion staging tree driven by active tabs, Git changes, `@` mentions, and IDF-weighted PSI usage searches with smart cache omission.
- **Session History & Deduplication**: Persistent turn timeline with undo support and hash-based payload deduplication.
- **Multi-Project WebSocket Mesh**: Dynamic local port binding and hybrid title/pathname tab binding with automated native "Copy as Markdown" response extraction.