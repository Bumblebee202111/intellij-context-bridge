---
name: "aicontext"
description: "Create or update .aicontext with shared and global project files."
mode: "EDIT"
context-action: "ADDITIVE"
include-files:
  - ".aicontext"
---
Create or refine `.aicontext` at the project root to configure default context states for new sessions.

### Schema & Mechanics
```json
{
  "skeleton": ["."],
  "complete": ["README.md"]
}
```
- Paths (files or directories relative to the project root) in `skeleton` are applied first, then overridden by `complete`.
- `skeleton` extracts public API signatures for code files and omits raw contents for non-code files. `complete` includes full file contents.

### Strategy
Set `skeleton` to `["."]` for project-wide structural and API visibility, and populate `complete` with all shared, global, or foundational files and directories. Favor broad inclusion over omission so new sessions start with complete cross-cutting context without extra `<ide:read_file>` round-trips.