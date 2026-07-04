<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-context-bridge Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- **Phase 1: MVP**
  - 3-state File Tree UI (`None`, `Skeleton`, `Full`) with recursive folder toggling.
  - PSI-based Skeleton Extractor for Kotlin and Java (extracts public API, safely strips bodies/private members).
  - Hybrid XML-Markdown Payload Generator.
- **Phase 2: State & Deduplication**
  - Session tracker with prompt history stack (navigate via `Ctrl+Up/Down`).
  - Universal Deduplication Engine (hashes extracted text and silently omits unchanged files from payloads).
  - Injected strict LLM protocol prompt for `REQUEST_FULL` and file path headers.
- **Phase 3: Diff Application**
  - Dual-tab UI (Composer vs. Diff Receiver).
  - Markdown parser to extract AI code blocks and match them to local file paths.
  - IntelliJ `DiffManager` integration for safe, visual side-by-side code insertion/replacement.
- **Phase 4: WebSocket Bridge**
  - Embedded Ktor WebSocket server (`localhost:37373`).
  - Tampermonkey userscript with Floating Action Button (FAB) for AI Studio.
  - Automated prompt injection, drag-and-drop simulation for media, and native clipboard interception for pristine Markdown extraction.
- **Phase 5: Polish & Configuration**
  - `.aicontext` parser for auto-loading default project context on startup.
  - Smart MIME/Type checker (excludes opaque binaries, encodes supported media to Base64 JSON payloads).

### Removed
- Default template sample files and dummy actions.