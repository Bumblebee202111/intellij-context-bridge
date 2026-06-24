# Core Requirements & Constraints

## 1. Context Levels
The system MUST support strictly two context extraction levels:
* **Skeleton:**
  * *For code files:* Extracts strictly the "Public API". It MUST include class/interface declarations, public/protected signatures, KDoc/JavaDoc, and only the imports required by those specific signatures. It MUST explicitly strip method bodies, private signatures, internal logic, and unused imports.
  * *For non-code files:* Acts as a simple path listing (outputs only the relative file path/name).
* **Full:** The complete raw text of the file.

## 2. Context Deduplication
The system MUST track the state of the current conversation to prevent context bloat.
* *Rule:* If a file is requested as `Full`, the system MUST hash its contents. If the user attempts to send the same file as `Full` in a subsequent prompt and the hash is unchanged, the system MUST silently downgrade the output to `Skeleton` (or omit it entirely).
* *Constraint:* The system MUST NOT generate chatty placeholders (e.g., `[File unchanged]`) in the payload.

## 3. The LLM Protocol Prompt
Every generated payload MUST invisibly include a strict system directive instructing the LLM on how to behave.
* *Mandatory Rule:* The AI must be instructed to output `REQUEST_FULL: [filepath]` and halt generation if it requires the body of a Skeleton file to proceed.

## 4. Project Configuration (`.aicontext`)
The plugin MUST support reading a local configuration file (e.g., `.aicontext`) at the project root.
* *Function:* Defines default system prompts, auto-selected directories (e.g., always load `docs/`), and project-specific banned patterns.

## 5. Diff-Based Application
The plugin MUST NOT silently overwrite local files. All incoming code from the AI MUST be routed through a visual side-by-side diff interface before being applied to the disk.

## 6. Non-Goals (Out of Scope)
* Direct integration with OpenAI/Anthropic/Google REST APIs.
* Autonomous agentic loops (the AI cannot execute terminal commands or trigger file reads without user intervention).