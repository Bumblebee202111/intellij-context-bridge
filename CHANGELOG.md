<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# intellij-context-bridge Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- **Phase 1 MVP:**
  - 3-state File Tree UI (`None`, `Skeleton`, `Full`) with recursive folder toggling.
  - PSI-based Skeleton Extractor for Kotlin and Java (extracts public API, safely strips bodies/private members using AST offsets).
  - Hybrid XML-Markdown Payload Generator for optimal LLM context parsing.
  - "Copy to Clipboard" action for seamless AI Studio Web UI integration.

### Removed
- Default template sample files and dummy actions.