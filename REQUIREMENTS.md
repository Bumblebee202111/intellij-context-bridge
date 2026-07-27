# Core Requirements & Constraints

## 1. Context Levels & File Types
The system MUST support two primary context extraction levels for files:
* **Skeleton:**
  * *For code files:* Extracts the structural API. It MUST include class/interface declarations, signatures (including intra-module modifiers like `internal` or `package-private`), compile-time constants, documentation, and required imports. It MUST explicitly strip method bodies and private internal logic.
  * *For non-code files:* Indicates the file's presence in the context while omitting its raw contents.
* **Full:** The complete raw text or encoded media of the file.
* **Capability Constraints:** The system MUST cap specific file types (e.g., opaque binaries, empty directories) at the Skeleton level to prevent false context expectations.
* **Diagnostics:** The system MUST support an optional mechanism to append active IDE diagnostics (compiler errors/warnings) for selected files.

## 2. Context Deduplication & Session History
The system MUST track the state of the conversation to prevent context bloat and maintain synchronization with the LLM.
* *Turn Tracking:* State is maintained as a persistent timeline of user turns, allowing users to undo or delete specific payloads if they revert a turn in the web UI.
* *Deduplication:* The system MUST hash the extracted contents of requested files. If a file is requested at the same context level in a subsequent turn and its extracted hash is unchanged, the system MUST omit it entirely from the new payload.
* *Constraint:* The system MUST NOT generate chatty placeholders (e.g., `[File unchanged]`) in the payload for deduplicated files.

## 3. System Instructions & Intent Modes
The system MUST separate static behavioral directives from the dynamic project context.
* *Intent Modes:* The system MUST support distinct interaction modes (e.g., "Ask" for read-only analysis and "Edit" for code generation) to dynamically adjust the system instructions.
* *Edit Constraints:* In generation modes, the AI MUST be instructed to output code using a "Skeleton Patch" format (where unchanged signatures are retained as structural anchors) and MUST format code blocks with exact file path headers to facilitate IDE parsing.
* *Context Awareness:* The AI MUST be instructed to output `REQUEST_FULL: [filepath]` if it requires the body of a Skeleton file to proceed.

## 4. Proactive Context Suggestions
The system MUST provide an intelligent, reactive suggestion engine to reduce user cognitive load when selecting context.
* *Heuristics:* The engine MUST evaluate files based on Git modifications, active/open editor tabs, prompt text mentions, and 1st-degree incoming/outgoing PSI relationships.
* *Graph Exclusion:* The engine MUST explicitly exclude files already loaded into the context as `SKELETON` from acting as seeds for graph traversal to prevent peripheral noise.
* *Relevance Filtering:* Unbounded usage searches MUST be mathematically penalized (e.g., Inverse Document Frequency) to prevent ubiquitous utility classes from flooding the suggestions.
* *Performance Constraints:* The engine MUST run asynchronously, MUST be debounced to prevent index thrashing on every keystroke, and MUST cleanly abort via `ReadAction.nonBlocking` cancellation if the user interrupts it or the IDE begins indexing.

## 5. Project Configuration (`.aicontext`)
The plugin MUST support reading a local configuration file (e.g., `.aicontext`) at the project root.
* *Function:* Defines default context routing by auto-selecting directories and files (e.g., always load `.` as Skeleton, and specific instruction files like `AGENTS.md` as Full) upon session initialization.

## 6. Diff-Based Application
The plugin MUST NOT silently overwrite local files. All incoming code from the AI MUST be routed through a visual side-by-side diff interface before being applied to the disk.

## 7. Non-Goals (Out of Scope)
* Direct integration with OpenAI/Anthropic/Google REST APIs.
* Autonomous agentic loops (the AI cannot execute terminal commands or trigger file reads without explicit user intervention).