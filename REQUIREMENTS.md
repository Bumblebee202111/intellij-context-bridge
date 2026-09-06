# Core Requirements & Constraints

## 1. Context States & File Types
The system MUST support two primary context states for files:
* **Complete (Default):** The complete raw text or encoded media of the file.
* **Skeleton:**
  * *For code files:* Extracts the structural API. It MUST include class/interface declarations, signatures (including intra-module modifiers like `internal` or `package-private`), compile-time constants, documentation, and required imports. It MUST explicitly strip method bodies and private internal logic.
  * *For non-code files:* Indicates the file's presence in the context while omitting its raw contents.
* **Capability Constraints:** The system MUST cap specific file types (e.g., opaque binaries, empty directories) at the Skeleton state to prevent false context expectations.
* **Diagnostics:** The system MUST support an optional mechanism to append active IDE diagnostics (compiler errors/warnings) for selected files.

## 2. Context Deduplication & Session History
The system MUST track the state of the conversation to prevent context bloat and maintain synchronization with the LLM.
* *Turn Tracking:* State is maintained as a persistent timeline of user turns, allowing users to undo or delete specific payloads if they revert a turn in the web UI.
* *Deduplication:* The system MUST hash the extracted contents of requested files. If a file is requested at the same context state in a subsequent turn and its extracted hash is unchanged, the system MUST omit it entirely from the new payload.
* *Ephemeral Tasks:* Specialized workflows (e.g., background commit generation) MUST execute invisibly and explicitly bypass session history to prevent context bloat and temporal confusion.
* *Constraint:* The system MUST NOT generate chatty placeholders (e.g., `[File unchanged]`) in the payload for deduplicated files.

## 3. System Instructions & Intent Modes
The system MUST separate static behavioral directives from the dynamic project context.
* *Intent Modes:* The system MUST support distinct interaction modes (e.g., "Ask" for read-only analysis and "Edit" for code generation). This MUST be enforced via unified system instructions and XML micro-anchoring (e.g., `<user_prompt mode="...">`) to ensure strong LLM adherence.
* *Edit Constraints & Prompt De-weaponization:* In generation modes, all file modifications MUST be routed through strict tool-specific XML tags (e.g., `<propose_edit>`). The system prompt MUST "de-weaponize" these schemas by presenting them as standard markdown formatting rules rather than backend API tools. This prevents aggressive RLHF models from triggering native function-call UI interceptions that suppress Chain of Thought and break extraction.
* *Skeleton Patch Protocol:* The AI MUST be instructed to output code using a "Skeleton Patch" format. To prevent "Diff Anchor Paranoia", the system MUST strictly enforce that unchanged blocks retain ONLY their signatures/headers with zero internal lines.
* *Extraction Constraints:* The system MUST rely on automated native UI actions (e.g., simulated clipboard copy) for extraction rather than brittle DOM parsing to ensure LLM internal thoughts or raw HTML nodes are safely stripped.
* *Context Awareness & Tool Calling:* The AI MUST be provided with XML-based tool schemas (e.g., `<read_file>`, `<delete_file>`). The system MUST intercept these tool calls and present them in the UI for manual user approval or visual review.

## 4. Proactive Context Suggestions
The system MUST provide an intelligent, reactive suggestion engine to act as a staging area and reduce user cognitive load when selecting context.
* *Heuristics:* The engine MUST evaluate files based on Git modifications, active/open editor tabs, prompt text mentions, and 1st-degree incoming/outgoing PSI relationships.
* *Smart Omission:* The engine MUST explicitly exclude files that have already reached their maximum context capability or whose maximum state is already stored in the deduplication cache.
* *Relevance Filtering:* Unbounded usage searches MUST be mathematically penalized (e.g., Inverse Document Frequency) to prevent ubiquitous utility classes from flooding the suggestions.
* *Performance Constraints:* The engine MUST run asynchronously within IDE-managed Coroutines, MUST be debounced to prevent index thrashing, and MUST cleanly yield via suspending `readAction`s if the user interrupts it or types in the editor.

## 5. Project Configuration (`.contextbridge`)
The plugin MUST support reading local configuration directories (e.g., `.contextbridge/`) at the project root.
* *Context Routing:* Defines default context routing (`.aicontext`) by auto-selecting directories and files upon a fresh session initialization.
* *Command Shadowing:* Defines project-specific Slash Commands (`commands/*.md`) via Markdown with YAML frontmatter, allowing local macros to seamlessly shadow/override built-in plugin defaults.
* *Command Composition:* The system MUST support stacking multiple commands (e.g., `/analyze /plan`). It MUST NOT expand command bodies into the UI text area. Instead, it MUST dynamically combine them in the payload using explicit XML semantic boundaries (`<applied_commands>`) to prevent directive collisions.

## 6. Diff-Based Application
The plugin MUST NOT silently overwrite local files. All incoming code from the AI MUST be routed through a visual side-by-side diff interface before being applied to the disk, unless explicitly requested via a native IDE integration (e.g., Commit Message auto-fill). Destructive actions (like file deletion or renaming) MUST be routed through native IDE refactoring dialogs to ensure structural safety.

## 7. Non-Goals (Out of Scope)
* Direct integration with OpenAI/Anthropic/Google REST APIs.
* Autonomous agentic loops (the AI cannot execute terminal commands or trigger file reads without explicit, manual user intervention and approval).
* Autonomous model-invoked Skills (all workflow directives MUST be explicitly user-invoked via Commands to keep simple, preserve AI Studio RPD quotas and user direction).