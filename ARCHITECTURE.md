# System Architecture

The plugin consists of four decoupled layers. Implementation details for each layer are left to the developer, provided the underlying inputs and outputs remain consistent.

## 1. UI Layer (Tool Window)
* **Tabbed Interface:** Organizes the workflow into distinct functional panels (e.g., Context Composer, Diff Receiver, and Session History).
* **File Tree View:** Allows selection of files/directories with computed visual state icons (e.g., `Full`, `Skeleton`, `Mixed`). Supports quick toggling, intuitive keyboard navigation, and double-click to open.
* **Intent Selection:** Allows users to specify the desired AI behavior (e.g., Ask/Analyze vs. Edit/Generate) to tailor the underlying system instructions.
* **Prompt Input:** Text area for user instructions, featuring a navigable prompt history stack and quick-undo capabilities.

## 2. Context Extraction Engine
* **PSI Parser (Program Structure Interface):**
  * *Input:* `PsiFile`
  * *Logic:* Walks the syntax tree. For `Skeleton` mode, it extracts the structural overview of the file (such as public signatures and class structures) while stripping out internal logic, bodies, and unused imports to save tokens.
  * *Fallback:* For non-code files, `Skeleton` mode safely indicates the file's presence while omitting its raw contents.
  * *Output:* Minified code string or structural representation.
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
* **Payload Generator:** Compiles extracted context, user prompts, and dynamically scoped system instructions (based on the selected intent) into a structured JSON object.
* **Bridge Mechanism:**
  * *Server:* A local WebSocket server embedded in the IDE, utilizing dynamic port binding to support multiple concurrent IDE instances (Mesh Networking).
  * *Client:* A browser userscript that maintains connections to active IDEs. It automatically routes payloads to the targeted AI Studio tab, injects system instructions and prompts, simulates media attachments, and intercepts native UI copy events on specific model turns to securely return code.
* **Diff Manager:** Parses incoming Markdown responses from the AI, matches code blocks to local file paths via headers, and opens IntelliJ's native side-by-side `DiffRequest` window for user review and application.