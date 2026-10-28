# System Architecture

The plugin consists of five decoupled layers. Implementation details for each layer are left to the developer, provided the underlying inputs and outputs remain consistent.

## 1. UI Layer (Tool Window)
* **Native Interface:** Built using standard IntelliJ platform components (`EditorTextField` for prompts, `ActionToolbar` for actions, `AsyncProcessIcon` for background tasks) to ensure a seamless, first-class IDE experience.
* **Tabbed Interface:** Organizes the workflow into distinct functional panels (Context Composer, Response Viewer, and Session History).
* **Multi-Tree File View:** Renders a primary project tree (plus optional `[External Context]` roots attached via file chooser or OS drag-and-drop), a dynamic "Suggested Context" staging area, and a temporary "Pending AI Requests" group for manual tool-call approval. Resolves VFS metadata off the EDT, repaints in-place unless VFS topology changes, and preserves user expansion and selection state.
* **Dual-Prefix Composer (`/` & `@`):** Supports inline auto-completion for both `/` slash commands and `@` file/directory mentions, automatically promoting `@`-mentioned paths to `Complete` context.
* **Intent Selection:** Segmented controls allow users to quickly specify the desired AI behavior (e.g., Ask vs. Edit) to tailor the underlying system instructions and constraints.

## 2. Context Extraction & Suggestion Engine
* **PSI Parser (Program Structure Interface):** Walks the syntax tree to extract structural APIs (public signatures, properties, class structures) for `Skeleton` mode while stripping internal logic and unused imports. Uses dynamic Extension Points (`skeletonExtractor` and `graphExtractor`) with optional plugin dependencies to support Kotlin, Java, JS, TS, and Vue across different JetBrains IDEs. Non-code files safely indicate presence while omitting raw contents.
* **Context Suggestion Engine:**
  * *Heuristic Seeds:* Monitors active editors, background tabs, Git modifications, and `Complete` context files (including `@`-mentioned files) to identify the user's current working set.
  * *Graph Traversal & Caching:* Searches outgoing dependencies and incoming usages from seed files in the background, caching per-seed graph results to avoid redundant PSI traversals.
  * *Mathematical Scoring:* Utilizes Inverse Document Frequency (IDF) scoring to penalize ubiquitous utility classes from polluting suggestions.
  * *Smart Omission:* Suppresses suggestions if a file has already reached its maximum context capability or its maximum state is already in the deduplication cache.
  * *Concurrency:* Executes asynchronously within IDE-managed Coroutines and yielding `readAction` blocks so the UI and editor never stutter.
* **Capability Checker:** Evaluates file extensions and MIME types to prevent encoding errors from opaque binaries, while identifying supported media files to encode as Base64 attachments.

## 3. State & Memory Manager
* **Session Tracker:** Maintains the lifecycle of the conversation using a persistent timeline of user turns, enabling true undo capabilities and deep auditing.
* **Project Configurator:** Parses local configuration files (e.g., `.aicontext`) on load to automatically route specific files and directories to their preferred context states. Defaults to restoring the exact context from the last session turn if one exists.
* **State Engine:** Tracks context states dynamically via a fast, thread-safe in-memory cache, aggregating directory states bottom-up based on their children. Strictly ties event listeners to UI-bound disposables to prevent memory leaks.
* **Universal Deduplication Engine:**
  * Hashes the extracted text or metadata of a file, utilizing native modification stamps for fast cache retrieval.
  * If a file is requested again at the exact same context state and its hash is unchanged, it is omitted from the payload to prevent context bloat.
  * The deduplication cache is dynamically folded from the session history, ensuring synchronization even if past turns are deleted.

## 4. Transport & Application Layer
* **Payload Generator:** Compiles extracted context, unified system instructions, and user prompts into a structured JSON object. Files are included in their entirety by default, while files reduced to their AST signatures are marked with a `(Skeleton)` tag. The user's intent (Ask vs. Edit) is anchored at the end of the context using a `<user_prompt mode="...">` wrapper.
* **Bridge Mechanism:**
  * *Server:* A local WebSocket server embedded in the IDE, utilizing dynamic port binding to support multiple concurrent IDE instances. Uses a hybrid bi-directional binding system (trusting chat titles persistently, pathnames transiently) to route payloads accurately across multiple projects, alongside a two-way diagnostic pipeline to test web UI automation health.
  * *Client:* A browser userscript that maintains connections to active IDEs. Automates the AI Studio web UI (system instruction cards, model selectors, drag-and-drop attachments), intercepts shortcuts, and manages transient state machines via state snapshots. Politely coexists with normal AI Studio usage by implicitly detecting Bridge Chats via active System Instructions, and maintains per-model persistent parameter memory.
* **Tool Call & Action Orchestrator:** Relies on automated native UI actions (e.g., "Copy as Markdown") to extract sanitized AI responses. Parses tool calls into a unified `AgentTool` hierarchy (read requests, file mutations, and commit message filling) and routes them through the Response Viewer panel for side-by-side diffing, IDE refactoring, or commit message injection.

## 5. Command Engine
* **Slash Commands:** A user-invoked macro system for workflow state transitions (e.g., `/plan`, `/commit`, `/review-bugs`, `/cmd`). Commands can be stacked via UI tokens and are dynamically interpolated into `<applied_commands>` XML blocks before the user's manual request.
* **Dynamic Context Interpolation:** Supports template variables (e.g., `{{vcs_diff}}`) inside command definitions, enabling macros to pull active IDE runtime state on demand.
* **Shadow Registry:** Commands are defined via Markdown files with YAML frontmatter. A background service loads built-in defaults and allows project-specific overrides via a local `.contextbridge/commands/` directory.