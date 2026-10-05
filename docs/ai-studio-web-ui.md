# Google AI Studio Web UI

This document contains dense, factual observations regarding the native Google AI Studio web environment and its DOM architecture.

- **Security & Rendering:**
  - Enforces Trusted Types for all DOM mutations.
  - The native Markdown parser supports syntax highlighting for triple-backtick code blocks even when nested inside raw XML nodes.
- **Architecture & Event Lifecycle:**
  - Built on Angular Material MDC. Dynamic elements like dropdowns (`<mat-select>`) mount asynchronously to a root `.cdk-overlay-container`.
  - Toggles (`<mat-slide-toggle>`) utilize an inner `button[role="switch"]` relying on `aria-checked` attributes for state.
  - Event propagation and submission shortcuts (`Ctrl+Enter`) are strictly bound to Angular's internal change detection lifecycle.
- **Session Lifecycle & Inheritance:**
  - *Cold Start:* Navigating to the root URL initializes a "Playground" session with factory-default parameters.
  - *Inheritance:* Triggering a new prompt from within an existing chat creates an "Untitled prompt" that natively clones the predecessor's model, system instructions, tools, and temperature.
  - *Persistence:* Chat titles auto-generate after the first generation, updating URL pathnames dynamically.
- **Parameter & Tool Dynamics:**
  - *Model Swaps:* Changing the active model immediately resets all companion parameters (Temperature, Thinking Level, Tools) to that specific model's factory defaults.
  - *Conditional UI:* Controls such as the "Thinking Level" dropdown mount and unmount asynchronously based on the active model's reasoning capabilities.
  - *System Instructions:* Managed via dialog overlays (`[data-test-system-instructions-card]`), which persist named instruction profiles directly in client storage.
- **Generation & Artifacts:**
  - *Thinking:* Submissions display an active progress state. If the model engages in non-trivial dynamic deliberation, an expandable "Thoughts" accordion is mounted; otherwise, the final text renders directly without a thought container.
  - *Extraction:* Full-turn extraction is housed inside the turn's overflow menu (`ms-chat-turn-options`), which provides distinct "Copy as text" (`.copy-rendered-button`) and "Copy as markdown" (`.copy-markdown-button`) actions.
  - *Function Calls:* Native tool invocations render interactive `<ms-function-call-chunk>` blocks within the generation stream.
- **Attachments:**
  - Media and file uploads bypass standard `<input type="file">` elements, relying entirely on global drag-and-drop event listeners.
]]