# Core Requirements & Constraints

## 1. Context Levels & File Types
The system MUST support strictly two context extraction levels for text/code files:
* **Skeleton:**
  * *For code files:* Extracts strictly the "Public API". It MUST include class/interface declarations, public/protected signatures, KDoc/JavaDoc, and only the imports required by those specific signatures. It MUST explicitly strip method bodies, private signatures, internal logic, and unused imports.
  * *For non-code files:* Acts as a simple path listing (outputs only the relative file path/name).
* **Full:** The complete raw text of the file.
* **Diagnostics:** The system MUST support an optional toggle to append active IDE diagnostics (compiler errors/warnings) for the selected files into the payload.
* **Media & Binaries:** The system MUST gracefully handle supported media files (e.g., via attachments) and strictly exclude opaque binaries from text payloads to prevent encoding errors.

## 2. Universal Context Deduplication
The system MUST track the state of the current conversation to prevent context bloat.
* *Rule:* The system MUST hash the extracted contents of requested files. If a file is requested at the same context level in a subsequent prompt and its extracted hash is unchanged, the system MUST omit it entirely from the new payload.
* *Constraint:* The system MUST NOT generate chatty placeholders (e.g., `[File unchanged]`) in the payload.

## 3. The LLM Protocol Prompt
Every generated payload MUST invisibly include a strict system directive instructing the LLM on how to behave.
* *Mandatory Rules:* The AI must be instructed to output `REQUEST_FULL: [filepath]` and halt generation if it requires the body of a Skeleton file. It MUST also be instructed to format output code blocks with exact file path headers to facilitate IDE parsing.

## 4. Project Configuration (`.aicontext`)
The plugin MUST support reading a local configuration file (e.g., `.aicontext`) at the project root.
* *Function:* Defines default context routing by auto-selecting directories and files (e.g., always load `.` as Skeleton, and specific instruction files like `AGENTS.md` as Full) upon session initialization.

## 5. Diff-Based Application
The plugin MUST NOT silently overwrite local files. All incoming code from the AI MUST be routed through a visual side-by-side diff interface before being applied to the disk.

## 6. Non-Goals (Out of Scope)
* Direct integration with OpenAI/Anthropic/Google REST APIs.
* Autonomous agentic loops (the AI cannot execute terminal commands or trigger file reads without user intervention).