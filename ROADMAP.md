# Development Roadmap

*Note: This project evolves organically through active dogfooding. This document serves as an idea backlog rather than a strict sequential plan.*

## Completed Milestones
- Core Context Extraction & UI
- Session History, True Undo, & Deduplication
- Diff-Based Code Application
- Multi-IDE Automation Bridge (WebSocket Mesh & Userscript)
- Native UI Overhaul & Proactive Context Suggestions
- Intent Architecture (Unified Prompt & XML Micro-anchoring)
- Prompt Adherence (Strict Ask vs. Edit boundaries & refined Skeleton Patch Protocol)
- Slash Commands & Shadow Registry
- Multi-Project Binding & Memory Safety (Observer Pattern & UI-bound Disposables)
- Zero-Click Sync & Automated Native UI Extraction
- Ephemeral Workflows (Native AI Commit Generation & State Machines)
- Agentic File CRUD Tools (Create, Edit, Rename, Delete) & Native Refactoring Integration
- Command Engine Refinement (Stacked invocations, Token insertion, Mode elevation, `<applied_commands>` boundaries)
- Tool-Specific XML Tags (Bypassing RLHF triggers & strict XML parsing)

## Upcoming Focus

**Engineering Context (Git & Diagnostics)**
* [ ] Add UI toggle to include active editor compiler errors/warnings in the payload.
* [ ] Add action to auto-select uncommitted/modified files based on `git status`.
* [ ] Optimize payload by sending Git diffs for modified files already in memory.

## Future Explorations
**Read-Only Tool Calling**
* [ ] Define XML-based tool call schema for system directives.
* [ ] Implement IDE-side execution for safe, read-only queries (e.g., global search, find usages).
* [ ] Automate tool result transmission back to the web UI.