# Development Roadmap

*Note: This project evolves organically through active dogfooding. This document serves as an idea backlog rather than a strict sequential plan.*

## Completed Milestones
- Core Context Tree UI, `.aicontext` Routing, & Session Deduplication
- Multi-Language AST Skeleton & Graph Extractors (Kotlin, Java, JS, TS, Vue) with Cross-IDE Support
- Proactive Context Suggestion Engine (Editor/Git/Mention Seeds, IDF Scoring, Per-Seed Caching)
- Inline `@` File/Folder Mentions & Stackable `/` Slash Commands with Dynamic Variables
- Unified Intent Architecture (`ASK` vs `EDIT`) & Resilient `ide:` XML/CDATA Tool Protocol
- Unified `AgentTool` Hierarchy, Response Viewer, Side-by-Side Diffs, & Native Refactoring
- Multi-Project WebSocket Bridge, Polite AI Studio Coexistence, Per-Model Memory, & Diagnostic Bridge
- Ephemeral AI Commit Generation Workflow with Snapshot Restoration

## Upcoming Focus

**Engineering Context (Git & Diagnostics)**
* [ ] UI toggle to include active editor compiler errors/warnings in payloads.
* [ ] Action to auto-select uncommitted/modified files from `git status`.
* [ ] Option to send Git diffs for modified files already cached in session history.

**Prompt & Extraction Resilience**
* [ ] Monitor AI Studio frontend updates to keep XML tool tags and DOM automation resilient.

## Future Explorations
**Read-Only Tool Calling**
* [ ] IDE-side execution and result transmission for safe read-only queries (e.g., search, find usages).