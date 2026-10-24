---
name: "lore"
description: "Update project documentation."
mode: "EDIT"
context-action: "ADDITIVE"
include-files:
  - "AGENTS.md"
  - "ARCHITECTURE.md"
  - "REQUIREMENTS.md"
  - "README.md"
  - "CHANGELOG.md"
  - "ROADMAP.md"
  - "docs/ai-studio-web-ui.md"
---
Update project documentation. Do not over-document: skip minor edits, bug fixes, or implementation details just because they were discussed in this session. Only edit files whose scope genuinely changed, and keep additions brief and proportional to their impact.
- AGENTS.md: Brief AI context, tech stack baseline, and critical platform rules.
- ARCHITECTURE.md: High-level system layers, component roles, and data flow. Keep general and flexible.
- REQUIREMENTS.md: Core behavioral specifications, hard constraints, and non-goals. Keep general and flexible.
- README.md: High-level project overview and philosophy.
- ROADMAP.md: Ultra-brief, lazy, flexible idea backlog. Keep general.
- CHANGELOG.md: Ultra-brief, minimalist capability tracking.
- docs/ai-studio-web-ui.md: Dense, factual AI Studio DOM and Angular lifecycle observations.