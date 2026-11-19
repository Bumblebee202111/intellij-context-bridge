# Antigravity MCP Integration

Context Bridge exposes a local, stateless **MCP `2026-07-28`** Streamable HTTP endpoint on the embedded Ktor server (`127.0.0.1:37373/mcp`) so Google Antigravity (2.0 App / IDE) can push discovered files directly into the IDE's `Staged Context` tree section.

## 1. Endpoint & Tool Contract

- **URL:** `http://127.0.0.1:37373/mcp` (starts automatically on project open; falls back across `37373..37382` if port `37373` is occupied)
- **Transport:** Stateless JSON-RPC over `POST /mcp` (`server/discover`, `tools/list`, `tools/call`, `ping`); `GET` and `DELETE` return `405 Method Not Allowed`.
- **Exposed Tool:** `stage_context_files`
  - `paths` (`string[]`, required): Absolute (or project-relative) file paths to stage.
  - `project` (`string`, optional): Open project name or base path override when multiple projects are open in the same IDE process. Defaults to the sole open project or the most recently focused project window.
  - `clearStaged` (`boolean`, optional): Replaces previously staged files instead of appending when `true`.
- **Behavior:**
  - Resolves paths (refreshing VFS off the read lock when needed), bypasses `.gitignore` for explicitly staged files, selects them at `COMPLETE`, and adds them to `Staged Context`.
  - Files outside the current project automatically mount their enclosing repo/project root (e.g., `.git`, `.idea`, `.vscode`, or build root) in `External Context` and merge with existing external roots.

## 2. Antigravity MCP Configuration

Add the server to `~/.gemini/config/mcp_config.json` (shared across Antigravity 2.0 App, IDE, and CLI):

```json
{
  "mcpServers": {
    "context-bridge": {
      "serverUrl": "http://127.0.0.1:37373/mcp"
    }
  }
}
```

Keep Antigravity's default **`Ask`** permission for `stage_context_files` so you can review and approve file batches before they are pushed to the IDE.

## 3. Global `/scout` Skill

Save the following as `~/.gemini/config/skills/scout/SKILL.md`:

```markdown
---
name: scout
description: Locate relevant files across the workspace and send their paths via stage_context_files. Use when searching, finding, or cross-referencing files for a task.
---
Locate the files relevant to the user's task and pass their absolute paths to `stage_context_files`.

- Do not modify workspace source files.
- Pass original file paths by default. Only slice as a rare fallback for extreme read-only reference files that are mostly irrelevant and would seriously bloat context—never slice normal files.
- When slicing, write 1000-line aligned chunks (`L1-1000`, `L1001-2000`) to a temporary directory while preserving the `<root>/<relative-dir>/` path structure, and pass those slice paths instead.
```