# Google AI Studio Web UI

This document contains dense, factual observations regarding the native Google AI Studio web environment and its DOM architecture.

- **Security & Rendering:**
  - Enforces Trusted Types for all DOM mutations.
  - The native Markdown parser supports syntax highlighting for triple-backtick code blocks even when nested inside raw XML nodes.
  - Styles surfaces, text, borders, and semantic accents via `--color-v3-*` and `--v3-shadow-*` CSS custom properties that adapt across light (`body.light-theme`) and dark themes.
- **Architecture & Event Lifecycle:**
  - Built on Angular Material MDC. Dynamic elements like dropdowns (`<mat-select>`) mount asynchronously to a root `.cdk-overlay-container`.
  - Toggles (`<mat-slide-toggle>`) utilize an inner `button[role="switch"]` relying on `aria-checked` attributes for state.
  - Event propagation and submission shortcuts (`Ctrl+Enter`) are strictly bound to Angular's internal change detection lifecycle.
  - The Playground header (`ms-header`) uses a 3-column grid (`.toolbar-left`, `1fr` spacer, `.toolbar-right`), and the prompt composer (`ms-prompt-box > .prompt-box-container`) stacks a horizontal `.textarea-row` above `.buttons-row`.
- **Session Lifecycle & Inheritance:**
  - *Cold Start:* Navigating to the root URL initializes a "Playground" session with factory-default parameters.
  - *Inheritance:* Triggering a new prompt from within an existing chat creates an "Untitled prompt" that natively clones the predecessor's model, system instructions, tools, and temperature.
  - *Persistence:* Chat titles auto-generate after the first generation, updating URL pathnames dynamically.
- **Parameter & Tool Dynamics:**
  - *Model Selection:* The active model card (`.model-selector-card`) exposes the model identifier slug via `[data-test-id="model-name"]`. Clicking it mounts a `<ms-model-carousel>` dialog containing category filter chips (`[data-test-category-button]`, which persist the user's last-selected category) and model rows (`button[id="model-carousel-row-models/<slug>"]`).
  - *Model Swaps:* Changing the active model immediately resets all companion parameters (Temperature, Thinking Level, Tools) to that specific model's factory defaults.
  - *Conditional UI:* Controls such as the "Thinking Level" dropdown and top-level "Temperature" slider mount and unmount asynchronously based on the active model's generation and reasoning capabilities.
  - *System Instructions & Side Panel:* Managed via dialog overlays (`[data-test-system-instructions-card]`), which persist named instruction profiles directly in client storage. Explicitly closing `ms-right-side-panel` unmounts `<ms-run-settings>` (and the system instructions card) from the DOM, whereas responsive collapse (`<= 960px`) hides it via CSS.
- **Generation & Artifacts:**
  - *Run / Stop Lifecycle:* The primary submission button (`ms-run-button button`) mutates in-place during active generation to display a `.spin` (`progress_activity`) indicator and a `Stop` label, reverting once streaming finishes, errors, or is aborted.
  - *Thinking:* Submissions display an active progress state. If the model engages in non-trivial dynamic deliberation, an expandable "Thoughts" accordion is mounted; otherwise, the final text renders directly without a thought container.
  - *Turn Action Bar & Extraction:* Each turn (`ms-chat-turn > .chat-turn-container`) mounts a sticky top-right toolbar (`.actions-container > .actions.hover-or-edit`) that tracks the viewport while scrolling, offsets for sticky code-block headers (`.code-block-aligner`), and defaults to `opacity: 0; pointer-events: none` until hovered. Full-turn extraction is housed inside the turn's overflow menu (`ms-chat-turn-options`), providing "Copy as text" (`.copy-rendered-button`) and "Copy as markdown" (`.copy-markdown-button`).
  - *Function Calls:* Native tool invocations render interactive `<ms-function-call-chunk>` blocks within the generation stream.
- **Attachments:**
  - Media and file uploads bypass standard `<input type="file">` elements, relying entirely on global drag-and-drop event listeners.