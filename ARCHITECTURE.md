# System Architecture

The plugin consists of four decoupled layers. Implementation details for each layer are left to the developer, provided the underlying inputs and outputs remain consistent.

## 1. UI Layer (Tool Window)
* **Native Interface:** Built using standard IntelliJ platform components (`EditorTextField` for prompts, `ActionToolbar` for actions, `AsyncProcessIcon` for background tasks) to ensure a seamless, first-class IDE experience.
* **Tabbed Interface:** Organizes the workflow into distinct functional panels (Context Composer, Diff Receiver, and Session History).
* **Multi-Tree File View:** Managed by a dedicated `ContextTreeManager`. Renders a primary project tree, a dynamic "Suggested Context" staging area, and a temporary "Pending AI Requests" group for manual tool-call approval. Supports quick toggling, intuitive keyboard navigation, and sub-tree compaction.
* **Intent Selection:** Segmented controls allow users to quickly specify the desired AI behavior (e.g., Ask vs. Edit) to tailor the underlying system instructions and constraints.

## 2. Context Extraction & Suggestion Engine
* **PSI Parser (Program Structure Interface):**
  * *Input:* `PsiFile`
  * *Logic:* Walks the syntax tree. For `Skeleton` mode, it extracts the structural overview of the file (public signatures, properties, class structures) while stripping out internal logic and unused imports.
  * *Fallback:* For non-code files, `Skeleton` mode safely indicates the file's presence while omitting its raw contents.
* **Context Suggestion Engine:**
  * *Heuristic Seeds:* Monitors active editors, background tabs, Git modifications, and regex-matched prompt mentions to identify the user's current working set.
  * *Graph Traversal:* Performs unbounded background searches for outgoing dependencies and incoming usages based on the seed files.
  * *Mathematical Scoring:* Utilizes Inverse Document Frequency (IDF) scoring to mathematically penalize ubiquitous utility classes (e.g., `Logger`) from polluting the suggestions.
    * *Smart Omission:* Actively suppresses suggestions if the file has already reached its maximum context capability or if its maximum state is already safely stored in the deduplication cache.
  * *Power Efficiency:* Strictly executes inside IDE-managed Coroutines using yielding `readAction` blocks and suspension delays to ensure the IDE never stutters, typing is never blocked, and battery life is preserved.
* **Capability Checker:** Evaluates file extensions and MIME types to prevent encoding errors from opaque binaries, while identifying supported media files to encode as Base64 attachments.

## 3. State & Memory Manager
* **Session Tracker:** Maintains the lifecycle of the conversation using a persistent timeline of user turns, enabling true undo capabilities and deep auditing.
* **Project Configurator:** Parses local configuration files (e.g., `.aicontext`) on load to automatically route specific files and directories to their preferred context states.
* **State Engine:** Tracks context states dynamically via a fast, thread-safe in-memory cache, aggregating directory states bottom-up based on their children.
* **Universal Deduplication Engine:**
  * Hashes the extracted text or metadata of a file, utilizing native modification stamps for instantaneous cache retrieval.
  * If a file is requested again at the exact same context state and its hash is unchanged, it is typically omitted from the payload to prevent context bloat.
  * The deduplication cache is dynamically folded from the session history, ensuring perfect synchronization even if past turns are deleted.

## 4. Transport & Application Layer
* **Payload Generator:** Compiles extracted context, user prompts, and context-aware system instructions into a structured JSON object. Files are included in their entirety by default, while files reduced to their AST signatures are explicitly marked with a `(Skeleton)` tag in the markdown headers.
* **Bridge Mechanism:**
  * *Server:* A local WebSocket server embedded in the IDE, utilizing dynamic port binding to support multiple concurrent IDE instances (Mesh Networking).
  * *Client:* A browser userscript that maintains connections to active IDEs. It routes payloads to the targeted AI Studio tab, injects system instructions, simulates media attachments, and intercepts native UI copy events to securely return code.
* **Tool Call & Diff Manager:** Parses incoming Markdown responses from the AI. It extracts XML-based tool calls (e.g., `read_file`) to route back to the UI for manual approval, while matching code blocks to local file paths to open IntelliJ's native side-by-side `DiffRequest` window for user review.