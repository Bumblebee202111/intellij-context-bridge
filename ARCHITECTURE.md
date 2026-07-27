# System Architecture

The plugin consists of four decoupled layers. Implementation details for each layer are left to the developer, provided the underlying inputs and outputs remain consistent.

## 1. UI Layer (Tool Window)
* **Native Interface:** Built using standard IntelliJ platform components (`EditorTextField` for prompts, `ActionToolbar` for actions, `AsyncProcessIcon` for background tasks) to ensure a seamless, first-class IDE experience.
* **Tabbed Interface:** Organizes the workflow into distinct functional panels (Context Composer, Diff Receiver, and Session History).
* **Dual File Tree View:** Managed by a dedicated `ContextTreeManager`. Renders a primary project tree and a dynamic, auto-compacting "Suggested Context" tree. Supports quick toggling, intuitive keyboard navigation, and double-click to open.
* **Intent Selection:** Segmented controls allow users to quickly specify the desired AI behavior (e.g., Ask vs. Edit) to tailor the underlying system instructions.

## 2. Context Extraction & Suggestion Engine
* **PSI Parser (Program Structure Interface):**
  * *Input:* `PsiFile`
  * *Logic:* Walks the syntax tree. For `Skeleton` mode, it extracts the structural overview of the file (public signatures, properties, class structures) while stripping out internal logic and unused imports.
  * *Fallback:* For non-code files, `Skeleton` mode safely indicates the file's presence while omitting its raw contents.
* **Context Suggestion Engine:**
  * *Heuristic Seeds:* Monitors active editors, background tabs, Git modifications, and regex-matched prompt mentions to identify the user's current working set.
  * *Graph Traversal:* Performs unbounded background searches for outgoing dependencies and incoming usages based on the seed files.
  * *Mathematical Scoring:* Utilizes Inverse Document Frequency (IDF) scoring to mathematically penalize ubiquitous utility classes (e.g., `Logger`) from polluting the suggestions.
  * *Power Efficiency:* Strictly executes inside debounced, cancellable `ReadAction.nonBlocking` blocks to ensure the IDE never stutters and battery life is preserved.
* **Capability Checker:** Evaluates file extensions and MIME types to prevent encoding errors from opaque binaries, while identifying supported media files to encode as Base64 attachments.

## 3. State & Memory Manager
* **Session Tracker:** Maintains the lifecycle of the conversation using a persistent timeline of user turns, enabling true undo capabilities and deep auditing.
* **Project Configurator:** Parses local configuration files (e.g., `.aicontext`) on load to automatically route specific files and directories to their preferred context levels.
* **State Engine:** Tracks context levels dynamically via a fast, thread-safe in-memory cache, aggregating directory states bottom-up based on their children.
* **Universal Deduplication Engine:**
  * Hashes the extracted text or metadata of a file.
  * If a file is requested again at the exact same context level and its hash is unchanged, it is typically omitted from the payload to prevent context bloat.
  * The deduplication cache is dynamically folded from the session history, ensuring perfect synchronization even if past turns are deleted.

## 4. Transport & Application Layer
* **Payload Generator:** Compiles extracted context, user prompts, and dynamically scoped system instructions into a structured JSON object.
* **Bridge Mechanism:**
  * *Server:* A local WebSocket server embedded in the IDE, utilizing dynamic port binding to support multiple concurrent IDE instances (Mesh Networking).
  * *Client:* A browser userscript that maintains connections to active IDEs. It routes payloads to the targeted AI Studio tab, injects system instructions, simulates media attachments, and intercepts native UI copy events to securely return code.
* **Diff Manager:** Parses incoming Markdown responses from the AI, matches code blocks to local file paths, and opens IntelliJ's native side-by-side `DiffRequest` window for user review and application.