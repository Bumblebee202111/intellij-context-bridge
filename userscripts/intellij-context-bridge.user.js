// ==UserScript==
// @name         IntelliJ Context Bridge (AI Studio)
// @namespace    http://tampermonkey.net/
// @version      0.1
// @description  Bridge for AI Studio and IntelliJ Context Bridge
// @match        https://aistudio.google.com/*
// @run-at       document-start
// @grant        unsafeWindow
// ==/UserScript==

(function () {
    'use strict';

    if (window.top !== window.self) return;

    const win = unsafeWindow || window;

    win.__cbActive = false;
    win.__cbInterceptedText = null;
    win.__cbCurrentMode = 'ASK';
    win.__cbCommands = [];
    win.__cbIsBound = false;
    win.__cbIsBridgeChat = false;
    win.__cbProjectName = 'IntelliJ';

    const FLASH_MODEL_ID = 'gemini-3.8-flash';
    const PORTS = Array.from({length: 10}, (_, i) => 37373 + i);
    const activeSockets = new Map();
    const tabId = Math.random().toString(36).substring(2, 10);
    let lastActivePort = null;

    let statusPill;
    let toastTimeout = null;
    let isToastActive = false;

    // --- CLIPBOARD INTERCEPTORS ---

    const origExec = win.document.execCommand;
    win.document.execCommand = function (command, showUI, value) {
        if (win.__cbActive && command.toLowerCase() === 'copy') {
            const activeEl = win.document.activeElement;
            let text = activeEl && (activeEl.tagName === 'TEXTAREA' || activeEl.tagName === 'INPUT')
                ? activeEl.value.substring(activeEl.selectionStart, activeEl.selectionEnd)
                : win.getSelection().toString();
            if (text) win.__cbInterceptedText = text;
        }
        return origExec.apply(this, arguments);
    };

    if (win.navigator && win.navigator.clipboard) {
        const origWriteText = win.navigator.clipboard.writeText.bind(win.navigator.clipboard);
        win.navigator.clipboard.writeText = async function (text) {
            if (win.__cbActive) {
                win.__cbInterceptedText = text;
            }
            return origWriteText(text);
        };
    }

    // --- UI HELPERS ---

    function initCustomStyles() {
        if (document.getElementById('cb-custom-styles')) return;
        const style = document.createElement('style');
        style.id = 'cb-custom-styles';
        style.textContent = `
            body.cb-is-bridge-chat .chat-turn-container.model.cb-latest-turn .actions.hover-or-edit {
                opacity: 1 !important;
                pointer-events: auto !important;
            }
        `;
        document.head.appendChild(style);
    }

    function ensureStatusPillMounted() {
        if (!statusPill) return;
        const toolbarRight = document.querySelector('ms-header .toolbar-right');
        if (toolbarRight) {
            if (statusPill.parentElement !== toolbarRight) {
                statusPill.style.position = 'static';
                statusPill.style.bottom = '';
                statusPill.style.right = '';
                statusPill.style.marginRight = '4px';
                statusPill.style.boxShadow = 'none';
                toolbarRight.insertBefore(statusPill, toolbarRight.firstChild);
            }
        } else if (statusPill.parentElement !== document.body) {
            statusPill.style.position = 'fixed';
            statusPill.style.bottom = '24px';
            statusPill.style.right = '24px';
            statusPill.style.marginRight = '0';
            statusPill.style.boxShadow = 'var(--v3-shadow-lg)';
            document.body.appendChild(statusPill);
        }
    }

    function initStatusPill() {
        statusPill = document.createElement('div');
        statusPill.id = 'cb-status-pill';
        statusPill.style.cssText = `
            z-index: 999999; display: inline-flex; align-items: center;
            background-color: var(--color-v3-surface-container); color: var(--color-v3-text);
            border: 1px solid var(--color-v3-outline-var); border-radius: 14px;
            height: 28px; padding: 0 12px; font-family: Inter, sans-serif; font-size: 12px; font-weight: 500;
            line-height: 1; white-space: nowrap; flex-shrink: 0;
            transition: opacity 0.3s ease, border-color 0.2s ease;
            pointer-events: none; opacity: 0;
        `;
        ensureStatusPillMounted();
        updateStatusPill();
    }

    function renderStatusPill(text, color) {
        if (!statusPill) return;
        ensureStatusPillMounted();
        statusPill.textContent = text;
        statusPill.style.borderColor = color || 'var(--color-v3-outline-var)';
        statusPill.style.opacity = '1';
    }

    function showToast(text, color, duration = 3000) {
        if (!statusPill) return;
        isToastActive = true;
        renderStatusPill(text, color);
        clearTimeout(toastTimeout);
        toastTimeout = null;
        if (duration > 0) {
            toastTimeout = setTimeout(() => {
                toastTimeout = null;
                isToastActive = false;
                updateStatusPill();
            }, duration);
        }
    }

    function clearIndefiniteToast() {
        if (!toastTimeout) {
            isToastActive = false;
            updateStatusPill();
        }
    }

    function updateStatusPill() {
        if (isToastActive) return;
        let connected = Array.from(activeSockets.values()).some(ws => ws.readyState === WebSocket.OPEN);
        let modeText = win.__cbIsBridgeChat ? '⚡ Bridge' : '💬 Normal';

        if (connected && win.__cbIsBound) {
            renderStatusPill(`🎯 ${win.__cbProjectName} (${modeText})`, 'var(--color-v3-accent-4)');
        } else if (connected && !win.__cbIsBound) {
            renderStatusPill(`🔗 Standby (${modeText})`, 'var(--color-v3-outline-var)');
        } else {
            renderStatusPill(`🔌 Disconnected (${modeText})`, 'var(--color-v3-accent-3)');
        }
    }

    function applyBoundStateToButton(btn) {
        if (win.__cbIsBound) {
            btn.textContent = `✨ Send to ${win.__cbProjectName}`;
            btn.style.background = 'var(--color-v3-surface-container)';
            btn.style.color = 'var(--color-v3-text)';
            btn.style.borderColor = 'var(--color-v3-outline)';
            btn.style.opacity = '1';
            btn.style.pointerEvents = 'auto';
        } else {
            btn.textContent = '🚫 IDE Not Bound';
            btn.style.background = 'transparent';
            btn.style.color = 'var(--color-v3-text-disable)';
            btn.style.borderColor = 'var(--color-v3-outline-var)';
            btn.style.opacity = '1';
            btn.style.pointerEvents = 'none';
        }
    }

    function updateUiElements() {
        document.querySelectorAll('.cb-send-to-ide-btn').forEach(applyBoundStateToButton);
    }

    function sendToIde(text) {
        if (!win.__cbIsBound) return;

        if (lastActivePort && activeSockets.has(lastActivePort) && activeSockets.get(lastActivePort).readyState === WebSocket.OPEN) {
            activeSockets.get(lastActivePort).send(text);
        } else {
            activeSockets.forEach(ws => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(text);
                }
            });
        }
    }

    // --- MAIN INITIALIZATION ---

    document.addEventListener('DOMContentLoaded', () => {
        let isProcessing = false;
        initCustomStyles();
        initStatusPill();

        // --- AIStudioDOM NAMESPACE ---
        const AIStudioDOM = {
            Utils: {
                async waitForElement(selector, timeout = 15000) {
                    return new Promise((resolve) => {
                        if (document.querySelector(selector)) return resolve(document.querySelector(selector));
                        const observer = new MutationObserver(() => {
                            const el = document.querySelector(selector);
                            if (el) {
                                observer.disconnect();
                                resolve(el);
                            }
                        });
                        observer.observe(document.body, {childList: true, subtree: true});
                        setTimeout(() => {
                            observer.disconnect();
                            resolve(null);
                        }, timeout);
                    });
                },
                base64ToFile(base64Data, mimeType, filename) {
                    const byteString = atob(base64Data);
                    const ab = new ArrayBuffer(byteString.length);
                    const ia = new Uint8Array(ab);
                    for (let i = 0; i < byteString.length; i++) {
                        ia[i] = byteString.charCodeAt(i);
                    }
                    const blob = new Blob([ab], {type: mimeType});
                    return new File([blob], filename, {type: mimeType});
                },
                simulateFileDrop(files) {
                    const dropZone = document.querySelector('[msglobalfiledragdrop]') || document.body;
                    const dataTransfer = new DataTransfer();
                    files.forEach(file => dataTransfer.items.add(file));

                    ['dragenter', 'dragover', 'drop'].forEach(eventType => {
                        const dropEvent = new DragEvent(eventType, {
                            bubbles: true,
                            cancelable: true,
                            dataTransfer: dataTransfer
                        });
                        dropZone.dispatchEvent(dropEvent);
                    });
                },
                setNativeValue(element, value) {
                    let proto = window.HTMLInputElement.prototype;
                    if (element.tagName === 'TEXTAREA') proto = window.HTMLTextAreaElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
                    setter.call(element, value);
                    element.dispatchEvent(new Event('input', {bubbles: true}));
                    element.dispatchEvent(new Event('change', {bubbles: true}));
                }
            },
            Chat: {
                getTitle() {
                    const h1 = document.querySelector('.page-title h1');
                    if (h1 && h1.textContent.trim()) return h1.textContent.trim();
                    return document.title.replace(' - Google AI Studio', '').trim() || 'New Chat';
                },
                async deleteLastTwoTurns(count = 2) {
                    for (let i = 0; i < count; i++) {
                        const turns = document.querySelectorAll('.chat-turn-container');
                        if (turns.length === 0) break;
                        const lastTurn = turns[turns.length - 1];
                        const menuBtn = lastTurn.querySelector('button[aria-label="Open options"]') || lastTurn.querySelector('ms-chat-turn-options button');
                        if (menuBtn) {
                            menuBtn.click();
                            const menuPanel = await AIStudioDOM.Utils.waitForElement('.mat-mdc-menu-panel', 2000);
                            if (menuPanel) {
                                const options = Array.from(menuPanel.querySelectorAll('button, .mat-mdc-menu-item'));
                                const deleteBtn = options.find(opt => opt.textContent.includes('Delete'));
                                if (deleteBtn) {
                                    deleteBtn.click();
                                    await new Promise(r => setTimeout(r, 500));
                                    const dialog = document.querySelector('mat-dialog-container');
                                    if (dialog) {
                                        const confirmBtns = Array.from(dialog.querySelectorAll('button'));
                                        const confirmBtn = confirmBtns.find(b => b.textContent.includes('Delete') || b.textContent.includes('Confirm'));
                                        if (confirmBtn) confirmBtn.click();
                                    }
                                }
                            }
                        }
                        await new Promise(r => setTimeout(r, 1000));
                    }
                },
                async extractViaNativeCopy(turnElement) {
                    const menuBtn = turnElement.querySelector('button[aria-label="Open options"]') || turnElement.querySelector('ms-chat-turn-options button');
                    if (!menuBtn) return null;
                    menuBtn.click();

                    const copyIcon = await AIStudioDOM.Utils.waitForElement('.cdk-overlay-container .copy-markdown-button', 3000);
                    if (!copyIcon) {
                        document.body.click();
                        return null;
                    }

                    const copyBtn = copyIcon.closest('button');

                    win.__cbActive = true;
                    win.__cbInterceptedText = null;

                    copyBtn.click();

                    await new Promise(r => setTimeout(r, 300));
                    win.__cbActive = false;

                    const backdrop = document.querySelector('.cdk-overlay-backdrop');
                    if (backdrop) backdrop.click();
                    else document.body.click();

                    return win.__cbInterceptedText;
                }
            },
            Controls: {
                getActiveModelName() {
                    const slugEl = document.querySelector('.model-selector-card [data-test-id="model-name"]');
                    if (slugEl && slugEl.textContent.trim()) {
                        return slugEl.textContent.trim();
                    }
                    const titleEl = document.querySelector('.model-selector-card .title');
                    return titleEl ? titleEl.textContent.trim() : null;
                },
                async setModel(modelId) {
                    if (!modelId) return false;
                    const targetSlug = modelId.replace(/^models\//, '').trim();
                    if (this.getActiveModelName() === targetSlug) return true;

                    const selectorBtn = document.querySelector('.model-selector-card');
                    if (!selectorBtn) return false;

                    selectorBtn.click();
                    const carousel = await AIStudioDOM.Utils.waitForElement('ms-model-carousel', 3000);
                    if (!carousel) return false;

                    const rowSelector = `button[id="model-carousel-row-models/${targetSlug}"]`;
                    let option = await AIStudioDOM.Utils.waitForElement(rowSelector, 1000);

                    if (!option) {
                        const categoryBtns = Array.from(carousel.querySelectorAll('button[data-test-category-button]'));
                        const geminiChip = categoryBtns.find(btn => btn.textContent.trim() === 'Gemini');
                        if (geminiChip && geminiChip.getAttribute('aria-selected') !== 'true') {
                            geminiChip.click();
                            option = await AIStudioDOM.Utils.waitForElement(rowSelector, 2000);
                        }
                    }

                    if (!option) {
                        const closeBtn = document.querySelector('mat-dialog-container button[data-test-close-button], mat-dialog-container button[aria-label="Close panel"]');
                        if (closeBtn) closeBtn.click();
                        else document.querySelector('.cdk-overlay-backdrop')?.click();
                        await new Promise(r => setTimeout(r, 300));
                        return false;
                    }

                    option.click();

                    for (let i = 0; i < 15; i++) {
                        await new Promise(r => setTimeout(r, 100));
                        if (this.getActiveModelName() === targetSlug) {
                            await new Promise(r => setTimeout(r, 300));
                            return true;
                        }
                    }

                    return this.getActiveModelName() === targetSlug;
                },
                async setThinkingLevel(level) {
                    let select = document.querySelector('mat-select[aria-label="Thinking Level"]');
                    let attempts = 0;

                    // The thinking level control might take a moment to render after a model swap
                    while (!select && attempts < 10) {
                        await new Promise(r => setTimeout(r, 200));
                        select = document.querySelector('mat-select[aria-label="Thinking Level"]');
                        attempts++;
                    }

                    if (select) {
                        const currentValue = select.querySelector('.mat-mdc-select-value-text')?.textContent?.trim();
                        if (currentValue && currentValue.includes(level)) return; // Already set

                        const trigger = select.querySelector('.mat-mdc-select-trigger') || select;
                        trigger.click();

                        const panel = await AIStudioDOM.Utils.waitForElement('.mat-mdc-select-panel', 3000);
                        if (panel) {
                            await new Promise(r => setTimeout(r, 300)); // Wait for expand animation
                            const options = Array.from(panel.querySelectorAll('mat-option, .mat-mdc-option'));
                            const target = options.find(opt => opt.textContent.includes(level));
                            if (target) {
                                target.click();
                            } else {
                                document.body.click(); // Close if not found
                            }
                        }
                        await new Promise(r => setTimeout(r, 500));
                    }
                },
                async setTemperature(value) {
                    const slider = document.querySelector('input[type="range"][aria-label="Temperature"]');
                    if (slider) {
                        AIStudioDOM.Utils.setNativeValue(slider, value);
                        await new Promise(r => setTimeout(r, 500));
                    }
                },
                async setUrlContext(enabled) {
                    const toggleBtn = document.querySelector('button[role="switch"][aria-label="Browse the url context"]');
                    if (toggleBtn) {
                        const isChecked = toggleBtn.getAttribute('aria-checked') === 'true';
                        if (isChecked !== enabled) {
                            toggleBtn.click();
                            await new Promise(r => setTimeout(r, 500));
                        }
                    }
                },
                async applyParameters(settings) {
                    if (!settings) return;
                    if (settings.temperature !== undefined && settings.temperature !== null) {
                        await this.setTemperature(settings.temperature);
                    }
                    if (settings.thinkingLevel !== undefined && settings.thinkingLevel !== null) {
                        await this.setThinkingLevel(settings.thinkingLevel);
                    }
                    if (settings.urlContext !== undefined && settings.urlContext !== null) {
                        await this.setUrlContext(settings.urlContext);
                    }
                }
            },
            Input: {
                lastAppliedCommandsKey: "",
                isGenerating() {
                    const btn = document.querySelector('ms-run-button button');
                    if (!btn) return false;
                    return btn.querySelector('.spin') !== null || btn.textContent.includes('Stop');
                },
                getPromptArea() {
                    return document.querySelector('textarea[formcontrolname="promptText"]') || document.querySelector('textarea[aria-label="Enter a prompt"]');
                },
                setPromptText(text) {
                    const textarea = this.getPromptArea();
                    if (textarea) {
                        textarea.focus();
                        AIStudioDOM.Utils.setNativeValue(textarea, text);
                    }
                },
                syncModeFromCommands(text) {
                    const {appliedCommands} = this.parseLeadingCommands(text);
                    const currentKey = appliedCommands.map(c => c.name).join(',');
                    if (currentKey !== this.lastAppliedCommandsKey) {
                        this.lastAppliedCommandsKey = currentKey;
                        if (appliedCommands.length > 0) {
                            const targetMode = appliedCommands.some(c => c.mode === 'EDIT') ? 'EDIT' : 'ASK';
                            if (win.__cbCurrentMode !== targetMode) {
                                win.__cbCurrentMode = targetMode;
                                AIStudioDOM.Injections.updateToggleStyles();
                            }
                        }
                    }
                },
                applyCommand(cmd) {
                    const textarea = this.getPromptArea();
                    if (!textarea) return;

                    let val = textarea.value;
                    let cursor = textarea.selectionStart;
                    let textBefore = val.substring(0, cursor);
                    let textAfter = val.substring(cursor);

                    let newTextBefore = textBefore.substring(0, textBefore.length - AIStudioDOM.Injections.activePrefix.length) + '/' + cmd.name + ' ';

                    AIStudioDOM.Utils.setNativeValue(textarea, newTextBefore + textAfter);
                    textarea.selectionStart = textarea.selectionEnd = newTextBefore.length;
                    textarea.focus();

                    this.syncModeFromCommands(textarea.value);

                    AIStudioDOM.Injections.hideSuggestions();
                },
                parseLeadingCommands(text) {
                    let remainingPrompt = text.trimStart();
                    const appliedCommands = [];
                    const commandRegex = /^\/([a-zA-Z0-9_-]+)(?:\s+|$)/;

                    while (remainingPrompt.length > 0) {
                        const match = remainingPrompt.match(commandRegex);
                        if (!match) break;
                        const cmd = (win.__cbCommands || []).find(c => c.name === match[1]);
                        if (!cmd) break;
                        appliedCommands.push(cmd);
                        remainingPrompt = remainingPrompt.substring(match[0].length).trimStart();
                    }

                    return {appliedCommands, remainingPrompt};
                },
                interceptAndWrap(textarea) {
                    let val = textarea.value;
                    if (!val.trim()) return;
                    if (val.includes('<user_prompt mode=')) return;

                    this.syncModeFromCommands(val);
                    const {appliedCommands, remainingPrompt} = this.parseLeadingCommands(val);
                    const mode = win.__cbCurrentMode || 'ASK';

                    let wrapped = "";
                    if (appliedCommands.length > 0) {
                        wrapped += "<applied_commands>\n";
                        appliedCommands.forEach(cmd => {
                            wrapped += `  <command name="${cmd.name}">\n    ${cmd.promptBody.trim().replace(/\n/g, '\n    ')}\n  </command>\n`;
                        });
                        wrapped += "</applied_commands>\n\n";
                    }
                    wrapped += remainingPrompt
                        ? `<user_prompt mode="${mode}">\n${remainingPrompt}\n</user_prompt>`
                        : `<user_prompt mode="${mode}">\n</user_prompt>`;

                    AIStudioDOM.Utils.setNativeValue(textarea, wrapped);
                    this.lastAppliedCommandsKey = "";
                }
            },
            System: {
                async updateInstructions(expectedTitle, expectedFirstSentence, newInstructions) {
                    const sysCard = document.querySelector('[data-test-system-instructions-card]');
                    if (!sysCard) return;
                    const subtitle = sysCard.querySelector('.subtitle');
                    if (subtitle && subtitle.textContent.includes(expectedFirstSentence)) return;

                    showToast('⚙️ Updating System Instructions...', 'var(--color-v3-accent-1)', 0);
                    sysCard.click();

                    const dialog = await AIStudioDOM.Utils.waitForElement('mat-dialog-container', 3000);
                    if (dialog) {
                        await new Promise(r => setTimeout(r, 500));

                        const select = dialog.querySelector('mat-select');
                        if (select) {
                            select.click();
                            await AIStudioDOM.Utils.waitForElement('.mat-mdc-select-panel mat-option', 3000);
                            await new Promise(r => setTimeout(r, 300));

                            const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option'));
                            const targetOption = options.find(opt => opt.textContent.includes(expectedTitle));

                            if (targetOption) {
                                targetOption.click();
                            } else {
                                const createOption = options.find(opt => opt.textContent.includes('Create new instruction'));
                                if (createOption) createOption.click();
                            }

                            await new Promise(r => setTimeout(r, 500));

                            const titleInput = dialog.querySelector('input[placeholder="Title"]');
                            if (titleInput && titleInput.value !== expectedTitle) {
                                titleInput.focus();
                                AIStudioDOM.Utils.setNativeValue(titleInput, expectedTitle);
                                titleInput.dispatchEvent(new Event('blur', {bubbles: true}));
                            }

                            const sysTextarea = dialog.querySelector('textarea[aria-label="System instructions"]');
                            if (sysTextarea && sysTextarea.value !== newInstructions) {
                                sysTextarea.focus();
                                AIStudioDOM.Utils.setNativeValue(sysTextarea, newInstructions);
                                sysTextarea.dispatchEvent(new Event('blur', {bubbles: true}));
                            }

                            await new Promise(r => setTimeout(r, 800));
                        }

                        const closeBtn = dialog.querySelector('button[aria-label="Close panel"], button[data-test-close-button]');
                        if (closeBtn) closeBtn.click();

                        await new Promise(r => setTimeout(r, 500));
                    }
                }
            },
            Preferences: {
                isProgrammaticUpdate: false,
                snapshot: null,
                STORAGE_KEY: 'cb_model_prefs',
                saveTimeout: null,

                getStore() {
                    try {
                        return JSON.parse(localStorage.getItem(this.STORAGE_KEY)) || {};
                    } catch (e) {
                        return {};
                    }
                },
                saveStore(store) {
                    localStorage.setItem(this.STORAGE_KEY, JSON.stringify(store));
                },
                getCurrentSettings() {
                    const tempSlider = document.querySelector('input[type="range"][aria-label="Temperature"]');
                    const thinkingSelect = document.querySelector('mat-select[aria-label="Thinking Level"]');
                    const urlSwitch = document.querySelector('button[role="switch"][aria-label="Browse the url context"]');

                    return {
                        temperature: tempSlider ? parseFloat(tempSlider.value) : null,
                        thinkingLevel: thinkingSelect ? thinkingSelect.textContent.trim() : null,
                        urlContext: urlSwitch ? urlSwitch.getAttribute('aria-checked') === 'true' : null
                    };
                },
                debounceSave() {
                    clearTimeout(this.saveTimeout);
                    this.saveTimeout = setTimeout(() => {
                        if (this.isProgrammaticUpdate) return;
                        const modelName = AIStudioDOM.Controls.getActiveModelName();
                        if (!modelName) return;

                        const settings = this.getCurrentSettings();
                        const store = this.getStore();
                        const existing = store[modelName] || {};

                        if (settings.temperature !== null) existing.temperature = settings.temperature;
                        if (settings.thinkingLevel !== null) existing.thinkingLevel = settings.thinkingLevel;
                        if (settings.urlContext !== null) existing.urlContext = settings.urlContext;

                        store[modelName] = existing;
                        this.saveStore(store);
                    }, 1000);
                },
                async applySavedSettings(modelName) {
                    if (!modelName) return;
                    const store = this.getStore();
                    const saved = store[modelName];
                    if (!saved) {
                        this.debounceSave();
                        return;
                    }

                    this.isProgrammaticUpdate = true;
                    try {
                        await new Promise(r => setTimeout(r, 500));
                        await AIStudioDOM.Controls.applyParameters(saved);
                    } finally {
                        this.isProgrammaticUpdate = false;
                    }
                },
                snapshotCurrentState() {
                    const modelName = AIStudioDOM.Controls.getActiveModelName();
                    const settings = this.getCurrentSettings();
                    this.snapshot = {modelName, ...settings};
                },
                async restoreSnapshot() {
                    if (!this.snapshot) return;
                    this.isProgrammaticUpdate = true;
                    try {
                        await AIStudioDOM.Controls.setModel(this.snapshot.modelName);
                        await AIStudioDOM.Controls.applyParameters(this.snapshot);
                    } finally {
                        this.isProgrammaticUpdate = false;
                        this.snapshot = null;
                    }
                }
            },
            Injections: {
                activePrefix: null,
                filteredCmds: [],
                selectedIndex: 0,
                suggestionBox: null,
                initSuggestionBox() {
                    this.suggestionBox = document.createElement('div');
                    this.suggestionBox.id = 'cb-suggestion-box';
                    this.suggestionBox.style.cssText = `
                        position: absolute; bottom: 100%; left: 0; margin-bottom: 8px;
                        background: var(--color-v3-surface-container); border: 1px solid var(--color-v3-outline-var);
                        border-radius: 12px; padding: 4px;
                        box-shadow: var(--v3-shadow-lg); z-index: 10000;
                        display: none; flex-direction: column; min-width: 320px; max-width: 480px;
                        max-height: 240px; overflow-y: auto; overscroll-behavior: contain;
                        scrollbar-width: thin; scrollbar-color: var(--color-v3-outline-var) transparent;
                        font-family: Inter, sans-serif; font-size: 13px;
                    `;
                },
                hideSuggestions() {
                    this.activePrefix = null;
                    this.filteredCmds = [];
                    this.selectedIndex = 0;
                    if (this.suggestionBox) {
                        this.suggestionBox.style.display = 'none';
                    }
                },
                updateToggleStyles() {
                    document.body.classList.toggle('cb-is-bridge-chat', Boolean(win.__cbIsBridgeChat));
                    const toggleContainer = document.getElementById('cb-mode-toggle');
                    if (toggleContainer) {
                        toggleContainer.style.display = win.__cbIsBridgeChat ? 'flex' : 'none';
                    }

                    const askBtn = document.getElementById('cb-mode-ask');
                    const editBtn = document.getElementById('cb-mode-edit');
                    if (!askBtn || !editBtn) return;

                    const activeStyle = 'background: var(--color-v3-button-container-accent); color: var(--color-v3-text-on-button); border: 1px solid var(--color-v3-outline); border-radius: 12px; padding: 4px 12px; cursor: pointer; font-weight: 500; transition: all 0.2s;';
                    const inactiveStyle = 'background: transparent; color: var(--color-v3-text-var); border: 1px solid var(--color-v3-outline-var); border-radius: 12px; padding: 4px 12px; cursor: pointer; font-weight: 400; transition: all 0.2s;';

                    askBtn.style.cssText = win.__cbCurrentMode === 'ASK' ? activeStyle : inactiveStyle;
                    editBtn.style.cssText = win.__cbCurrentMode === 'EDIT' ? activeStyle : inactiveStyle;
                },
                injectModeToggle() {
                    if (document.getElementById('cb-mode-toggle')) return;
                    const textarea = AIStudioDOM.Input.getPromptArea();
                    if (!textarea) return;

                    const container = textarea.closest('.textarea-row') || textarea.closest('ms-prompt-input-bar') || textarea.closest('.input-container') || textarea.parentElement;
                    if (!container) return;

                    const toggleContainer = document.createElement('div');
                    toggleContainer.id = 'cb-mode-toggle';
                    toggleContainer.style.cssText = `
                        display: ${win.__cbIsBridgeChat ? 'flex' : 'none'}; gap: 8px;
                        font-family: Inter, sans-serif; font-size: 13px; position: relative;
                    `;

                    const askBtn = document.createElement('button');
                    askBtn.id = 'cb-mode-ask';
                    askBtn.textContent = '💬 Ask';
                    askBtn.onclick = (e) => {
                        e.preventDefault();
                        win.__cbCurrentMode = 'ASK';
                        this.updateToggleStyles();
                    };

                    const editBtn = document.createElement('button');
                    editBtn.id = 'cb-mode-edit';
                    editBtn.textContent = '⚡ Edit';
                    editBtn.onclick = (e) => {
                        e.preventDefault();
                        win.__cbCurrentMode = 'EDIT';
                        this.updateToggleStyles();
                    };

                    toggleContainer.appendChild(askBtn);
                    toggleContainer.appendChild(editBtn);
                    toggleContainer.appendChild(this.suggestionBox);

                    container.parentElement.insertBefore(toggleContainer, container);
                    this.updateToggleStyles();
                },
                updateSuggestions() {
                    if (!this.activePrefix || this.filteredCmds.length === 0) {
                        this.hideSuggestions();
                        return;
                    }

                    while (this.suggestionBox.firstChild) {
                        this.suggestionBox.removeChild(this.suggestionBox.firstChild);
                    }

                    this.filteredCmds.forEach((cmd, i) => {
                        let item = document.createElement('div');
                        item.style.cssText = `
                            padding: 6px 10px; border-radius: 8px; cursor: pointer; display: flex; justify-content: space-between; align-items: center;
                            background: ${i === this.selectedIndex ? 'var(--color-v3-button-container-high)' : 'transparent'};
                            color: var(--color-v3-text); gap: 12px;
                        `;

                        let leftCol = document.createElement('div');
                        leftCol.style.cssText = 'display: flex; align-items: center; gap: 6px; min-width: 0; overflow: hidden;';

                        let nameStrong = document.createElement('strong');
                        nameStrong.textContent = '/' + cmd.name;
                        nameStrong.style.cssText = 'flex-shrink: 0;';
                        leftCol.appendChild(nameStrong);

                        if (cmd.description) {
                            let descSpan = document.createElement('span');
                            descSpan.style.cssText = 'color: var(--color-v3-text-var); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: normal;';
                            descSpan.textContent = cmd.description;
                            descSpan.title = cmd.description;
                            leftCol.appendChild(descSpan);
                        }

                        let modeSpan = document.createElement('span');
                        modeSpan.style.cssText = 'color: var(--color-v3-text-var); font-size: 11px; flex-shrink: 0;';
                        modeSpan.textContent = cmd.mode;

                        item.appendChild(leftCol);
                        item.appendChild(modeSpan);

                        item.onmousedown = (e) => {
                            e.preventDefault();
                            AIStudioDOM.Input.applyCommand(cmd);
                        };
                        this.suggestionBox.appendChild(item);
                    });
                    this.suggestionBox.style.display = 'flex';

                    const selectedEl = this.suggestionBox.children[this.selectedIndex];
                    if (selectedEl) {
                        selectedEl.scrollIntoView({ block: 'nearest' });
                    }
                },
                injectTurnButtons() {
                    const allModelTurns = document.querySelectorAll('.chat-turn-container.model');
                    if (!win.__cbIsBridgeChat) {
                        document.querySelectorAll('.cb-send-to-ide-btn').forEach(btn => btn.style.display = 'none');
                        allModelTurns.forEach(turn => turn.classList.remove('cb-latest-turn'));
                        return;
                    } else {
                        document.querySelectorAll('.cb-send-to-ide-btn').forEach(btn => btn.style.display = 'inline-flex');
                    }

                    const completedTurns = Array.from(allModelTurns).filter(turn => turn.querySelector('button[aria-label="Good response"]') !== null);
                    const lastCompletedTurn = completedTurns[completedTurns.length - 1] || null;

                    allModelTurns.forEach(turn => {
                        if (turn === lastCompletedTurn) {
                            if (!turn.classList.contains('cb-latest-turn')) {
                                turn.classList.add('cb-latest-turn');
                            }
                        } else {
                            if (turn.classList.contains('cb-latest-turn')) {
                                turn.classList.remove('cb-latest-turn');
                            }
                        }

                        if (turn.querySelector('.cb-send-to-ide-btn')) return;

                        const hasThumbUp = turn.querySelector('button[aria-label="Good response"]') !== null;
                        if (!hasThumbUp) return;

                        const isLoading = turn.querySelector('ms-chat-loading-indicator') !== null;
                        if (isLoading) return;

                        const actionBar = turn.querySelector('.actions.hover-or-edit');
                        if (!actionBar) return;

                        const sendBtn = document.createElement('button');
                        sendBtn.className = 'cb-send-to-ide-btn';
                        sendBtn.style.cssText = `
                            background: var(--color-v3-surface-container); border-radius: 16px;
                            padding: 0 12px; margin-left: 8px; font-size: 13px; font-weight: 500; font-family: inherit;
                            cursor: pointer; display: inline-flex; align-items: center; justify-content: center;
                            height: 32px; transition: all 0.2s ease; white-space: nowrap; box-sizing: border-box;
                            border: 1px solid var(--color-v3-outline); color: var(--color-v3-text);
                        `;
                        applyBoundStateToButton(sendBtn);

                        sendBtn.onmouseover = () => {
                            if (win.__cbIsBound) sendBtn.style.background = 'var(--color-v3-hover)';
                        };
                        sendBtn.onmouseout = () => {
                            sendBtn.style.background = win.__cbIsBound ? 'var(--color-v3-surface-container)' : 'transparent';
                        };

                        sendBtn.addEventListener('click', async (e) => {
                            e.preventDefault();
                            e.stopPropagation();
                            if (sendBtn.textContent.includes('...')) return;

                            sendBtn.textContent = '⏳ Copying...';
                            sendBtn.style.color = 'var(--color-v3-accent-1)';
                            sendBtn.style.borderColor = 'var(--color-v3-accent-1)';

                            const text = await AIStudioDOM.Chat.extractViaNativeCopy(turn);
                            if (text) {
                                sendToIde(text);
                                sendBtn.textContent = '✅ Sent to IDE';
                                sendBtn.style.color = 'var(--color-v3-accent-4)';
                                sendBtn.style.borderColor = 'var(--color-v3-accent-4)';
                            } else {
                                sendBtn.textContent = '❌ Extract Failed';
                                sendBtn.style.color = 'var(--color-v3-accent-3)';
                                sendBtn.style.borderColor = 'var(--color-v3-accent-3)';
                            }

                            setTimeout(() => {
                                applyBoundStateToButton(sendBtn);
                            }, 3000);
                        });

                        actionBar.appendChild(sendBtn);
                    });
                },
                startObservers(onNavigationChanged) {
                    let lastModelName = null;
                    let initialLoad = true;
                    let lastTitle = "";
                    let lastPathname = "";

                    setInterval(() => {
                        const sysCard = document.querySelector('[data-test-system-instructions-card]');
                        const subtitle = sysCard?.querySelector('.subtitle');
                        if (subtitle) {
                            const isBridge = subtitle.textContent.includes('expert AI coding assistant');
                            if (win.__cbIsBridgeChat !== isBridge) {
                                win.__cbIsBridgeChat = isBridge;
                                this.updateToggleStyles();
                                updateStatusPill();
                            }
                        }

                        ensureStatusPillMounted();
                        this.injectModeToggle();
                        this.injectTurnButtons();

                        const currentModel = AIStudioDOM.Controls.getActiveModelName();
                        if (currentModel && currentModel !== lastModelName) {
                            lastModelName = currentModel;

                            if (!AIStudioDOM.Preferences.isProgrammaticUpdate) {
                                if (initialLoad) {
                                    initialLoad = false;
                                    const title = AIStudioDOM.Chat.getTitle();
                                    if (title === "Playground" || title === "New Chat") {
                                        AIStudioDOM.Preferences.applySavedSettings(currentModel);
                                    }
                                } else {
                                    AIStudioDOM.Preferences.applySavedSettings(currentModel);
                                }
                            }
                        }

                        const currentTitle = AIStudioDOM.Chat.getTitle();
                        const currentPathname = window.location.pathname;
                        if (currentTitle !== lastTitle || currentPathname !== lastPathname) {
                            lastTitle = currentTitle;
                            lastPathname = currentPathname;
                            onNavigationChanged(currentPathname, currentTitle);
                        }
                    }, 1000);

                    document.addEventListener('input', (e) => {
                        if (e.target.matches('input[type="range"][aria-label="Temperature"]')) {
                            AIStudioDOM.Preferences.debounceSave();
                        }
                    }, true);

                    document.addEventListener('change', (e) => {
                        if (e.target.matches('input[type="range"][aria-label="Temperature"]')) {
                            AIStudioDOM.Preferences.debounceSave();
                        }
                    }, true);

                    document.addEventListener('click', (e) => {
                        const switchBtn = e.target.closest('button[role="switch"][aria-label="Browse the url context"]');
                        if (switchBtn) {
                            setTimeout(() => AIStudioDOM.Preferences.debounceSave(), 100);
                        }

                        const option = e.target.closest('mat-option, .mat-mdc-option');
                        if (option) {
                            setTimeout(() => AIStudioDOM.Preferences.debounceSave(), 500);
                        }
                    }, true);
                }
            },
            Diagnostics: {
                async runWithTimeout(name, actionFn, timeoutMs = 5000) {
                    const start = performance.now();
                    try {
                        await Promise.race([
                            actionFn(),
                            new Promise((_, reject) => setTimeout(() => reject(new Error("TIMEOUT")), timeoutMs))
                        ]);
                        const duration = Math.round(performance.now() - start);
                        return {name, status: 'PASS', durationMs: duration};
                    } catch (e) {
                        const duration = Math.round(performance.now() - start);
                        if (e.message === "TIMEOUT") {
                            return {name, status: 'TIMEOUT', durationMs: duration, error: `Exceeded ${timeoutMs}ms`};
                        }
                        return {name, status: 'FAIL', durationMs: duration, error: e.stack || e.toString()};
                    }
                },
                async run(ws) {
                    showToast('🧪 Running Diagnostics...', 'var(--color-v3-outline-accent)', 0);

                    AIStudioDOM.Preferences.snapshotCurrentState();
                    AIStudioDOM.Preferences.isProgrammaticUpdate = true;

                    const steps = [];
                    const totalStart = performance.now();

                    try {
                        steps.push(await this.runWithTimeout(`Set Model to ${FLASH_MODEL_ID}`, async () => {
                            const swapped = await AIStudioDOM.Controls.setModel(FLASH_MODEL_ID);
                            const currentModel = AIStudioDOM.Controls.getActiveModelName();
                            if (!swapped || currentModel !== FLASH_MODEL_ID) {
                                throw new Error(`Model swap failed. Expected: ${FLASH_MODEL_ID}, Got: ${currentModel}`);
                            }
                        }));

                        steps.push(await this.runWithTimeout('Set Temperature to 0.2', async () => {
                            await AIStudioDOM.Controls.setTemperature(0.2);

                            const slider = document.querySelector('input[type="range"][aria-label="Temperature"]');
                            if (!slider) throw new Error("Temperature slider not found in DOM");
                            if (parseFloat(slider.value) !== 0.2) {
                                throw new Error(`Temperature swap failed. Current value: ${slider.value}`);
                            }
                        }));

                        steps.push(await this.runWithTimeout('Open/Close System Instructions', async () => {
                            const sysCard = document.querySelector('[data-test-system-instructions-card]');
                            if (!sysCard) throw new Error("System card not found");
                            sysCard.click();
                            const dialog = await AIStudioDOM.Utils.waitForElement('mat-dialog-container', 3000);
                            if (!dialog) throw new Error("Dialog did not appear");
                            await new Promise(r => setTimeout(r, 500));
                            const closeBtn = dialog.querySelector('button[aria-label="Close panel"], button[data-test-close-button]');
                            if (closeBtn) closeBtn.click();
                            await new Promise(r => setTimeout(r, 500));

                            const dialogStillOpen = document.querySelector('mat-dialog-container');
                            if (dialogStillOpen) {
                                throw new Error("System instructions dialog failed to close");
                            }
                        }));

                        steps.push(await this.runWithTimeout('Restore Settings', async () => {
                            const snap = AIStudioDOM.Preferences.snapshot;
                            await AIStudioDOM.Preferences.restoreSnapshot();

                            const currentModel = AIStudioDOM.Controls.getActiveModelName();
                            if (snap.modelName && currentModel !== snap.modelName) {
                                throw new Error(`Model restore failed. Expected: ${snap.modelName}, Got: ${currentModel}`);
                            }
                        }));
                    } finally {
                        AIStudioDOM.Preferences.isProgrammaticUpdate = false;
                    }

                    const totalDuration = Math.round(performance.now() - totalStart);
                    const report = {totalDurationMs: totalDuration, steps};

                    if (ws && ws.readyState === WebSocket.OPEN) {
                        ws.send("[DIAGNOSTIC_RESULT]" + JSON.stringify(report));
                    }
                    showToast('✅ Diagnostics Complete', 'var(--color-v3-accent-4)', 3000);
                }
            }
        };

        AIStudioDOM.Injections.initSuggestionBox();
        AIStudioDOM.Injections.startObservers((currentPathname, currentTitle) => {
            activeSockets.forEach(ws => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(`[HANDSHAKE]${tabId}|${currentPathname}|${currentTitle}`);
                }
            });
        });

        function maintainConnections() {
            const currentTitle = AIStudioDOM.Chat.getTitle();
            const currentPathname = window.location.pathname;
            PORTS.forEach(port => {
                if (!activeSockets.has(port)) {
                    try {
                        const ws = new WebSocket(`ws://127.0.0.1:${port}/ai-bridge`);
                        let wasOpen = false;

                        activeSockets.set(port, ws);

                        ws.onopen = () => {
                            wasOpen = true;
                            ws.send(`[HANDSHAKE]${tabId}|${currentPathname}|${currentTitle}`);
                            updateStatusPill();
                        };

                        ws.onmessage = (event) => {
                            if (event.data.startsWith('[BOUND]')) {
                                win.__cbIsBound = true;
                                const parts = event.data.split('|', 2);
                                if (parts.length > 1 && parts[1].trim() !== '') {
                                    win.__cbProjectName = parts[1].trim();
                                }
                                updateStatusPill();
                                updateUiElements();
                                return;
                            }
                            if (event.data === '[UNBOUND]') {
                                win.__cbIsBound = false;
                                win.__cbProjectName = 'IntelliJ';
                                updateStatusPill();
                                updateUiElements();
                                return;
                            }
                            if (event.data.startsWith('[COMMANDS]')) {
                                try {
                                    win.__cbCommands = JSON.parse(event.data.substring(10));
                                } catch (e) {
                                }
                                return;
                            }
                            if (event.data === '[DIAGNOSTIC_RUN]') {
                                AIStudioDOM.Diagnostics.run(ws);
                                return;
                            }
                            lastActivePort = port;
                            handleIncomingPayload(event.data);
                        };

                        ws.onclose = () => {
                            activeSockets.delete(port);
                            if (wasOpen) {
                                updateStatusPill();
                            }
                        };

                        ws.onerror = () => {
                        };
                    } catch (e) {
                        activeSockets.delete(port);
                    }
                }
            });
        }

        setInterval(maintainConnections, 3000);
        maintainConnections();

        // --- WEB UI: EVENT LISTENERS ---

        document.addEventListener('input', (e) => {
            if (!win.__cbIsBridgeChat) return;

            const textarea = e.target;
            if (textarea.tagName === 'TEXTAREA' && (textarea.getAttribute('formcontrolname') === 'promptText' || textarea.getAttribute('aria-label') === 'Enter a prompt')) {
                let val = textarea.value;

                if (val.includes('<user_prompt mode="EDIT">')) {
                    if (win.__cbCurrentMode !== 'EDIT') {
                        win.__cbCurrentMode = 'EDIT';
                        AIStudioDOM.Injections.updateToggleStyles();
                    }
                } else if (val.includes('<user_prompt mode="ASK">')) {
                    if (win.__cbCurrentMode !== 'ASK') {
                        win.__cbCurrentMode = 'ASK';
                        AIStudioDOM.Injections.updateToggleStyles();
                    }
                } else {
                    AIStudioDOM.Input.syncModeFromCommands(val);
                }

                let cursor = textarea.selectionStart;
                let textBefore = val.substring(0, cursor);
                let tokenMatch = textBefore.match(/(?:^|\s)(\/[a-zA-Z0-9_-]*)$/);

                if (tokenMatch && win.__cbCommands && win.__cbCommands.length > 0) {
                    let prefix = tokenMatch[1];
                    let beforeToken = textBefore.substring(0, textBefore.length - prefix.length);
                    let {remainingPrompt} = AIStudioDOM.Input.parseLeadingCommands(beforeToken);

                    if (remainingPrompt.length === 0) {
                        AIStudioDOM.Injections.activePrefix = prefix;
                        let search = prefix.substring(1).toLowerCase();
                        AIStudioDOM.Injections.filteredCmds = win.__cbCommands.filter(c =>
                            c.name !== 'commit' &&
                            (!c.promptBody || !c.promptBody.includes('{{vcs_diff}}')) &&
                            c.name.toLowerCase().startsWith(search)
                        );
                        if (AIStudioDOM.Injections.selectedIndex >= AIStudioDOM.Injections.filteredCmds.length) AIStudioDOM.Injections.selectedIndex = 0;
                        AIStudioDOM.Injections.updateSuggestions();
                    } else {
                        AIStudioDOM.Injections.hideSuggestions();
                    }
                } else {
                    AIStudioDOM.Injections.hideSuggestions();
                }
            }
        }, true);

        document.addEventListener('pointerdown', (e) => {
            if (!AIStudioDOM.Injections.activePrefix) return;
            const box = AIStudioDOM.Injections.suggestionBox;
            if (box && !box.contains(e.target) && e.target !== AIStudioDOM.Input.getPromptArea()) {
                AIStudioDOM.Injections.hideSuggestions();
            }
        }, true);

        document.addEventListener('keydown', (e) => {
            if (!win.__cbIsBridgeChat) return;

            const textarea = e.target;
            if (textarea.tagName === 'TEXTAREA' && (textarea.getAttribute('formcontrolname') === 'promptText' || textarea.getAttribute('aria-label') === 'Enter a prompt')) {

                if (AIStudioDOM.Injections.activePrefix && AIStudioDOM.Injections.filteredCmds.length > 0) {
                    if (e.key === 'ArrowDown') {
                        e.preventDefault();
                        e.stopPropagation();
                        AIStudioDOM.Injections.selectedIndex = (AIStudioDOM.Injections.selectedIndex + 1) % AIStudioDOM.Injections.filteredCmds.length;
                        AIStudioDOM.Injections.updateSuggestions();
                        return;
                    } else if (e.key === 'ArrowUp') {
                        e.preventDefault();
                        e.stopPropagation();
                        AIStudioDOM.Injections.selectedIndex = (AIStudioDOM.Injections.selectedIndex - 1 + AIStudioDOM.Injections.filteredCmds.length) % AIStudioDOM.Injections.filteredCmds.length;
                        AIStudioDOM.Injections.updateSuggestions();
                        return;
                    } else if (e.key === 'Enter' || e.key === 'Tab') {
                        e.preventDefault();
                        e.stopPropagation();
                        AIStudioDOM.Input.applyCommand(AIStudioDOM.Injections.filteredCmds[AIStudioDOM.Injections.selectedIndex]);
                        return;
                    } else if (e.key === 'Escape') {
                        e.preventDefault();
                        e.stopPropagation();
                        AIStudioDOM.Injections.hideSuggestions();
                        return;
                    }
                }

                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey || e.altKey)) {
                    AIStudioDOM.Input.interceptAndWrap(textarea);
                }
            }
        }, true);

        document.addEventListener('click', (e) => {
            if (!win.__cbIsBridgeChat) return;

            const btn = e.target.closest('ms-run-button button');
            if (btn && !AIStudioDOM.Input.isGenerating()) {
                const textarea = AIStudioDOM.Input.getPromptArea();
                if (textarea) AIStudioDOM.Input.interceptAndWrap(textarea);
            }
        }, true);

        // --- AUTOMATED GENERATION PIPELINES ---

        async function submitAndMonitorGeneration(promptText, options) {
            const textarea = AIStudioDOM.Input.getPromptArea();
            if (!textarea) {
                if (options.onAbort) await options.onAbort();
                clearIndefiniteToast();
                isProcessing = false;
                return;
            }

            AIStudioDOM.Input.setPromptText(promptText);

            setTimeout(async () => {
                if (options.expectedModel && AIStudioDOM.Controls.getActiveModelName() !== options.expectedModel) {
                    showToast(`❌ Active model mismatch (${AIStudioDOM.Controls.getActiveModelName()})! Prompt left unsent.`, 'var(--color-v3-accent-3)', 5000);
                    if (options.onAbort) await options.onAbort();
                    isProcessing = false;
                    return;
                }

                const runBtn = await AIStudioDOM.Utils.waitForElement('ms-run-button button');
                if (!runBtn) {
                    if (options.onAbort) await options.onAbort();
                    clearIndefiniteToast();
                    isProcessing = false;
                    return;
                }

                const initialModelTurns = document.querySelectorAll('.chat-turn-container.model').length;
                const initialTotalTurns = document.querySelectorAll('.chat-turn-container').length;
                runBtn.click();

                showToast(options.generatingToast, 'var(--color-v3-accent-1)', 0);
                let hasSeenGenerating = false;
                let startupTicks = 0;

                const checkInterval = setInterval(async () => {
                    if (AIStudioDOM.Input.isGenerating()) {
                        hasSeenGenerating = true;
                        return;
                    }

                    const allModelTurns = document.querySelectorAll('.chat-turn-container.model');
                    startupTicks++;
                    if (!hasSeenGenerating && allModelTurns.length <= initialModelTurns && startupTicks < 6) {
                        return;
                    }

                    clearInterval(checkInterval);

                    try {
                        await new Promise(r => setTimeout(r, 300));
                        const scrollBtn = document.querySelector('.scroll-to-bottom');
                        if (scrollBtn) scrollBtn.click();

                        const finalModelTurns = document.querySelectorAll('.chat-turn-container.model');
                        if (finalModelTurns.length > initialModelTurns) {
                            const lastTurn = finalModelTurns[finalModelTurns.length - 1];
                            lastTurn.scrollIntoView({behavior: 'auto', block: 'end'});
                            await new Promise(r => setTimeout(r, 200));

                            const hasThumbUp = lastTurn.querySelector('button[aria-label="Good response"]') !== null;
                            if (hasThumbUp) {
                                showToast(options.extractingToast, 'var(--color-v3-accent-1)', 0);
                                const text = await AIStudioDOM.Chat.extractViaNativeCopy(lastTurn);
                                if (text) {
                                    sendToIde(text);
                                    showToast(options.successToast, 'var(--color-v3-accent-4)', 3000);
                                } else {
                                    showToast(options.extractFailedToast, options.extractFailedColor || 'var(--color-v3-accent-1)', 3000);
                                }
                            } else {
                                showToast(options.stoppedToast, 'var(--color-v3-accent-1)', 3000);
                            }
                        } else {
                            showToast(options.stoppedToast, 'var(--color-v3-accent-1)', 3000);
                        }

                        if (options.onCleanup) {
                            const addedTurns = document.querySelectorAll('.chat-turn-container').length - initialTotalTurns;
                            await options.onCleanup(addedTurns);
                        }
                    } finally {
                        clearIndefiniteToast();
                        isProcessing = false;
                    }
                }, 500);
            }, 300);
        }

        async function handleCommitGeneration(payloadObj) {
            showToast('⚙️ Configuring for Commit Generation...', 'var(--color-v3-accent-1)', 0);

            AIStudioDOM.Preferences.snapshotCurrentState();
            AIStudioDOM.Preferences.isProgrammaticUpdate = true;
            let swapped = false;
            try {
                swapped = await AIStudioDOM.Controls.setModel(FLASH_MODEL_ID);
                if (swapped) {
                    await AIStudioDOM.Controls.setThinkingLevel('High');
                    await AIStudioDOM.Controls.setUrlContext(true);
                }
            } finally {
                AIStudioDOM.Preferences.isProgrammaticUpdate = false;
            }

            if (!swapped || AIStudioDOM.Controls.getActiveModelName() !== FLASH_MODEL_ID) {
                AIStudioDOM.Input.setPromptText(payloadObj.text);
                AIStudioDOM.Preferences.snapshot = null;
                isProcessing = false;
                showToast(`❌ Model swap to ${FLASH_MODEL_ID} failed! Prompt left unsent.`, 'var(--color-v3-accent-3)', 5000);
                return;
            }

            await submitAndMonitorGeneration(payloadObj.text, {
                expectedModel: FLASH_MODEL_ID,
                generatingToast: '⏳ Generating Commit Message...',
                extractingToast: '⏳ Extracting Commit Message...',
                successToast: '✅ Sent Commit Message to IDE',
                extractFailedToast: '❌ Failed to copy model response',
                extractFailedColor: 'var(--color-v3-accent-3)',
                stoppedToast: '⏹️ Commit Generation Stopped',
                onAbort: async () => {
                    await AIStudioDOM.Preferences.restoreSnapshot();
                },
                onCleanup: async (addedTurns) => {
                    if (addedTurns > 0) {
                        showToast('🧹 Cleaning up session...', 'var(--color-v3-accent-1)', 0);
                        await AIStudioDOM.Chat.deleteLastTwoTurns(addedTurns);
                    }
                    showToast('⚙️ Restoring Settings...', 'var(--color-v3-accent-1)', 0);
                    await AIStudioDOM.Preferences.restoreSnapshot();
                    showToast('✅ Ready', 'var(--color-v3-accent-4)', 3000);
                }
            });
        }

        // --- IDE PAYLOAD HANDLER ---

        async function handleIncomingPayload(payloadString) {
            if (isProcessing) return;
            isProcessing = true;

            let payloadObj;
            try {
                payloadObj = JSON.parse(payloadString);
            } catch (e) {
                payloadObj = {text: payloadString, attachments: [], systemInstructions: ""};
            }

            if (payloadObj.isCommit) {
                handleCommitGeneration(payloadObj);
                return;
            }

            if (payloadObj.text) {
                const modeMatch = payloadObj.text.match(/<user_prompt mode="(ASK|EDIT)">/);
                if (modeMatch) {
                    win.__cbCurrentMode = modeMatch[1];
                    AIStudioDOM.Injections.updateToggleStyles();
                }
            }

            if (payloadObj.systemInstructions) {
                const expectedTitle = "IntelliJ Context Bridge";
                const expectedFirstSentence = "You are an expert AI coding assistant";
                await AIStudioDOM.System.updateInstructions(expectedTitle, expectedFirstSentence, payloadObj.systemInstructions);
            }

            if (payloadObj.attachments && payloadObj.attachments.length > 0) {
                showToast(`📎 Attaching ${payloadObj.attachments.length} files...`, 'var(--color-v3-accent-1)', 0);
                const files = payloadObj.attachments.map(att => AIStudioDOM.Utils.base64ToFile(att.base64Data, att.mimeType, att.name));
                AIStudioDOM.Utils.simulateFileDrop(files);
                await new Promise(r => setTimeout(r, 2000));
            }

            await submitAndMonitorGeneration(payloadObj.text, {
                generatingToast: '⏳ AI is Generating...',
                extractingToast: '⏳ Syncing to IDE...',
                successToast: '✅ Generation Complete & Synced',
                extractFailedToast: '⚠️ Generation Complete (Auto-sync failed)',
                extractFailedColor: 'var(--color-v3-accent-1)',
                stoppedToast: '⏹️ Generation Stopped'
            });
        }
    });

})();