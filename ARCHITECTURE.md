# System Architecture

The plugin consists of four decoupled layers. Implementation details for each layer are left to the developer, provided the inputs/outputs remain consistent.

## 1. UI Layer (Tool Window)
* **Tabbed Interface:** Separates the context composer from the diff application viewer.
* **File Tree View:** Allows selection of files/directories with 3-state toggles (`Skeleton`, `Full`, `Unselected`).
* **Prompt Input:** Text area for user instructions, featuring a navigable prompt history stack.
* **Action Buttons:** Trigger payload generation for Clipboard or WebSocket transport.

## 2. Context Extraction Engine
* **PSI Parser (Program Structure Interface):**
  * *Input:* `PsiFile`
  * *Logic:* Walks the syntax tree. For `Skeleton` mode, extracts strictly the "Public API" of the file. It explicitly strips method bodies, private signatures, internal logic, and unused imports.
  * *Fallback:* For non-code files, `Skeleton` mode simply outputs the file path/name.
  * *Output:* Minified code string or file path.
* **MIME/Type Checker:** Excludes opaque binaries from text extraction to prevent encoding errors. Identifies supported media files and encodes them into Base64 strings for attachment.

## 3. State & Memory Manager
* **Session Tracker:** Maintains the lifecycle of a single conversation and prompt history.
* **Project Configurator:** Parses local `.aicontext` files on load to automatically route default files and directories to their preferred context levels.
* **Universal Deduplication Engine:**
  * Hashes the *extracted* text of a file.
  * If a file is requested again at the exact same context level and its extracted hash is unchanged, it silently omits the file entirely from the payload to prevent context bloat.

## 4. Transport & Application Layer
* **Payload Generator:** Compiles extracted context, system prompts,, active compiler diagnostics, and user prompts into a structured JSON object containing Markdown text and Base64 media attachments.
* **Bridge Mechanism:**
  * *Server:* A local WebSocket server (`localhost:PORT`) embedded in the IDE.
  * *Client:* A browser userscript that receives JSON payloads, injects text, simulates drag-and-drop for media attachments, and intercepts native UI copy events to securely retrieve AI responses.
* **Diff Manager:** Parses incoming Markdown responses from the AI, matches code blocks to local file paths via headers, and opens IntelliJ's native side-by-side `DiffRequest` window for user approval.