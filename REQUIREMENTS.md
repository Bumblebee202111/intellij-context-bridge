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
* *Ephemeral Tasks:* Specialized workflows (e.g., background commit generation) MUST execute invisibly, utilizing pre-execution snapshots to restore the user's prior model and parameter state, and bypass session history to prevent context bloat.
* *Constraint:* The system MUST NOT generate chatty placeholders (e.g., `[File unchanged]`) in the payload for deduplicated files.

## 3. System Instructions & Intent Modes
The system MUST separate static behavioral directives from the dynamic project context.
* *Intent Modes:* The system MUST support distinct interaction modes ("Ask" for read-only analysis and "Edit" for code generation), enforced via unified system instructions and XML micro-anchoring (`<user_prompt mode="...">`).
* *Implicit UI Detection:* The companion userscript MUST politely coexist with normal web UI usage by implicitly detecting the Context Bridge system instructions, disabling its custom UI and shortcut interception in standard chats.
* *Edit & Tool Formatting Constraints:* In generation modes, all file modifications MUST use namespaced XML tags (e.g., `<ide:propose_edit>`) wrapped inside standard Markdown code blocks to prevent native function-call UI interception. Code payloads MUST be wrapped in `<![CDATA[...]]>` sections to prevent XML escaping and auto-indentation drift.
* *Skeleton Patch Protocol:* The AI MUST output code edits using the "Skeleton Patch" format, where unchanged blocks retain ONLY their signatures or headers with zero internal lines.
* *Extraction Constraints:* The system MUST rely on automated native UI actions (e.g., simulated clipboard copy) rather than raw DOM scraping so internal LLM thoughts and HTML artifacts are stripped. A diagnostic bridge MUST verify DOM automation stability under browser throttling.
* *Context Awareness & Tool Gatekeeping:* The AI MUST be provided with XML-based tool schemas (e.g., `<ide:read_file>`, `<ide:delete_file>`). All tool calls MUST be intercepted for manual user approval or visual review, and the prompt MUST forbid redundantly reading files already provided in full.

## 4. Proactive Context Suggestions
The system MUST provide an intelligent, reactive suggestion engine to act as a staging area and reduce user cognitive load when selecting context.
* *Heuristics & Mentions:* The engine MUST evaluate files based on Git modifications, active/open editor tabs, explicit `@` file/folder mentions (auto-promoted to `Complete`), and 1st-degree incoming/outgoing PSI relationships.
* *Smart Omission:* The engine MUST explicitly exclude files that have already reached their maximum context capability or whose maximum state is already stored in the deduplication cache.
* *Relevance Filtering:* Unbounded usage searches MUST be mathematically penalized (e.g., Inverse Document Frequency) to prevent ubiquitous utility classes from flooding the suggestions.
* *Performance & Compatibility Constraints:* Graph analysis MUST run asynchronously off the EDT so editor typing and UI responsiveness are never blocked, and language-specific PSI extractors MUST remain optional to preserve cross-IDE compatibility.

## 5. Project Configuration (`.contextbridge`)
The plugin MUST support reading local configuration directories (e.g., `.contextbridge/`) at the project root.
* *Context Routing:* Defines default context routing (`.aicontext`) by auto-selecting directories and files upon a fresh session initialization.
* *Command Shadowing:* Defines project-specific Slash Commands (`commands/*.md`) via Markdown with YAML frontmatter, allowing local macros to seamlessly shadow/override built-in plugin defaults.
* *Command Composition:* The system MUST support stacking multiple commands (e.g., `/research /plan`). It MUST NOT expand command bodies into the UI text area. Instead, it MUST dynamically combine them in the payload using explicit XML semantic boundaries (`<applied_commands>`) to prevent directive collisions.
* *Dynamic Context Variables:* Commands MUST support runtime variable interpolation (e.g., `{{vcs_diff}}`) to pull live IDE environment state dynamically without bloating static chat prompts.
* *Command Design Constraints:* Command definitions MUST use brief, dense, positive instructions that trust frontier models' capabilities rather than conversational padding or micromanaging checklists. Review prompts MUST employ targeted lenses (e.g., bugs, simplification, architecture, product) prioritized by impact and ROI.

## 6. Diff-Based Application
The plugin MUST NOT silently overwrite local files. All incoming code from the AI MUST be routed through a visual side-by-side diff interface before being applied to the disk, unless explicitly requested via a native IDE integration (e.g., Commit Message auto-fill). Destructive actions (like file deletion or renaming) MUST be routed through native IDE refactoring dialogs to ensure structural safety.

## 7. Non-Goals (Out of Scope)
* Direct integration with OpenAI/Anthropic/Google REST APIs.
* Autonomous agentic loops (the AI cannot execute terminal commands or trigger file reads without explicit, manual user intervention and approval).
* Autonomous model-invoked Skills (all workflow directives MUST be explicitly user-invoked via Commands to keep simple, preserve AI Studio RPD quotas and user direction).