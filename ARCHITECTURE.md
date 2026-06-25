# System Architecture

The plugin consists of four decoupled layers. Implementation details for each layer are left to the developer, provided the inputs/outputs remain consistent.

## 1. UI Layer (Tool Window)
* **File Tree View:** Allows selection of files/directories.
* **Toggles:** Assigns context levels (`Skeleton` or `Full`) to selected items.
* **Prompt Input:** Text area for user instructions.
* **Action Buttons:** "Copy to Clipboard" / "Send via WebSocket".

## 2. Context Extraction Engine
* **PSI Parser (Program Structure Interface):**
  * *Input:* `PsiFile`
  * *Logic:* Walks the syntax tree. For `Skeleton` mode, extracts strictly the "Public API" of the file. It retains class/interface declarations, public/protected signatures, and comments (KDoc/JavaDoc). It explicitly strips method bodies, private signatures, internal logic, and any imports not directly used by the exposed public signatures.
  * *Fallback:* For non-code files, `Skeleton` mode simply outputs the file path/name.
  * *Output:* Minified code string or file path.
* **MIME/Type Checker:** Identifies binary files to exclude them, flags image files for manual drag-and-drop.

## 3. State & Memory Manager
* **Session Tracker:** Maintains the lifecycle of a single conversation.
* **Deduplication Engine:**
  * Hashes the contents of files sent as `Full`.
  * If a file is requested again as `Full` but the hash is unchanged, it silently downgrades the output to `Skeleton` (or omits it entirely) rather than duplicating the full text in the LLM's context window.

## 4. Transport & Application Layer
* **Payload Generator:** Compiles extracted context, system prompts, and user prompts into a structured Markdown format.
* **Bridge Mechanism:**
  * *Phase 1:* System Clipboard.
  * *Phase 2:* Local WebSocket Server (`localhost:PORT`) to communicate with a browser extension/userscript.
* **Diff Manager:** Parses incoming Markdown responses from the AI, matches code blocks to local file paths, and opens IntelliJ's native `DiffRequest` window for user approval.