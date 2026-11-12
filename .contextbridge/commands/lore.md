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
Update project documentation to accurately reflect the current state of the project.
Keep documentation concise and high-signal: refine or consolidate existing text rather than merely appending, remove outdated information, and avoid bloating high-level docs with low-level implementation details.
- AGENTS.md: Brief AI context, tech stack baseline, and critical platform rules.
- ARCHITECTURE.md: High-level system layers, component roles, and data flow. Keep general and flexible.
- REQUIREMENTS.md: Core behavioral specifications, hard constraints, and non-goals. Keep general and flexible.
- README.md: High-level project overview and philosophy.
- ROADMAP.md: Ultra-brief, lazy, flexible idea backlog. Keep general.
- CHANGELOG.md: Ultra-brief, minimalist capability tracking.
- docs/ai-studio-web-ui.md: Dense, factual AI Studio DOM and Angular lifecycle observations.