// ==UserScript==
// @name         IntelliJ Context Bridge (AI Studio)
// @namespace    http://tampermonkey.net/
// @version      0.1
// @description  Bridge for AI Studio and IntelliJ Context Bridge
// @match        https://aistudio.google.com/*
// @run-at       document-start
// @grant        unsafeWindow
// ==/UserScript==

(function() {
    'use strict';

    if (window.top !== window.self) return;

    const win = unsafeWindow || window;

    win.__cbActive = false;
    win.__cbInterceptedText = null;
    win.__cbCurrentMode = 'ASK';
    win.__cbCommands = [];
    win.__cbIsBound = false;
    win.__cbProjectName = 'IntelliJ';

    const PORTS = Array.from({length: 10}, (_, i) => 37373 + i);
    const activeSockets = new Map();
    const tabId = Math.random().toString(36).substring(2, 10);
    let lastActivePort = null;

    let statusPill;
    let toastTimeout;

    // --- CLIPBOARD INTERCEPTORS ---

    const origExec = win.document.execCommand;
    win.document.execCommand = function(command, showUI, value) {
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
        win.navigator.clipboard.writeText = async function(text) {
            if (win.__cbActive) {
                win.__cbInterceptedText = text;
            }
            return origWriteText(text);
        };
    }

    // --- UI HELPERS ---

    function initStatusPill() {
        statusPill = document.createElement('div');
        statusPill.id = 'cb-status-pill';
        statusPill.style.cssText = `
            position: fixed; bottom: 24px; right: 24px; z-index: 999999;
            background-color: #333; color: white; border-radius: 16px;
            padding: 6px 12px; font-family: Inter, sans-serif; font-size: 12px; font-weight: bold;
            box-shadow: 0 2px 8px rgba(0,0,0,0.2); transition: opacity 0.3s ease;
            pointer-events: none; opacity: 0;
        `;
        document.body.appendChild(statusPill);
    }

    function showToast(text, color, duration = 3000) {
        if (!statusPill) return;
        statusPill.textContent = text;
        statusPill.style.backgroundColor = color;
        statusPill.style.opacity = '1';
        clearTimeout(toastTimeout);
        if (duration > 0) {
            toastTimeout = setTimeout(() => { statusPill.style.opacity = '0'; }, duration);
        }
    }

    function updateStatusPill() {
        let connected = Array.from(activeSockets.values()).some(ws => ws.readyState === WebSocket.OPEN);

        if (connected && win.__cbIsBound) {
            showToast(`🎯 Bound to ${win.__cbProjectName}`, '#4CAF50', 0);
        } else if (connected && !win.__cbIsBound) {
            showToast('🔗 Connected (Standby)', '#757575', 0);
        } else {
            showToast('🔌 IDE Disconnected', '#F44336', 0);
        }
    }

    function updateUiElements() {
        const toggleContainer = document.getElementById('cb-mode-toggle');
        if (toggleContainer) {
            toggleContainer.style.opacity = win.__cbIsBound ? '1' : '0.5';
            toggleContainer.style.pointerEvents = win.__cbIsBound ? 'auto' : 'none';
        }

        const turns = document.querySelectorAll('.cb-send-to-ide-btn');
        turns.forEach(btn => {
            if (win.__cbIsBound) {
                btn.textContent = `✨ Send to ${win.__cbProjectName}`;
                btn.style.color = '#4CAF50';
                btn.style.borderColor = '#4CAF50';
                btn.style.opacity = '1';
                btn.style.pointerEvents = 'auto';
            } else {
                btn.textContent = '🚫 IDE Not Bound';
                btn.style.color = '#757575';
                btn.style.borderColor = '#757575';
                btn.style.opacity = '0.7';
                btn.style.pointerEvents = 'none';
            }
        });
    }

    function getChatTitle() {
        const h1 = document.querySelector('.page-title h1');
        if (h1 && h1.textContent.trim()) return h1.textContent.trim();
        return document.title.replace(' - Google AI Studio', '').trim() || 'New Chat';
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
        initStatusPill();

        function maintainConnections() {
            const currentTitle = getChatTitle();
            const currentPathname = window.location.pathname;
            PORTS.forEach(port => {
                if (!activeSockets.has(port)) {
                    try {
                        const ws = new WebSocket(`ws://127.0.0.1:${port}/ai-bridge`);

                        activeSockets.set(port, ws);

                        ws.onopen = () => {
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
                                } catch(e) {}
                                return;
                            }
                            lastActivePort = port;
                            handleIncomingPayload(event.data);
                        };

                        ws.onclose = () => {
                            activeSockets.delete(port);
                            updateStatusPill();
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

        let lastTitle = "";
        let lastPathname = "";
        setInterval(() => {
            const currentTitle = getChatTitle();
            const currentPathname = window.location.pathname;
            if (currentTitle !== lastTitle || currentPathname !== lastPathname) {
                lastTitle = currentTitle;
                lastPathname = currentPathname;
                activeSockets.forEach(ws => {
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(`[HANDSHAKE]${tabId}|${currentPathname}|${currentTitle}`);
                    }
                });
            }
        }, 2000);

        // --- NATIVE EXTRACTION ENGINE ---

        async function extractViaNativeCopy(turnElement) {
            const menuBtn = turnElement.querySelector('button[aria-label="Open options"]') || turnElement.querySelector('ms-chat-turn-options button');
            if (!menuBtn) return null;
            menuBtn.click();

            const copyIcon = await waitForElement('.cdk-overlay-container .copy-markdown-button', 3000);
            if (!copyIcon) {
                document.body.click();
                return null;
            }

            const copyBtn = copyIcon.closest('button');

            win.__cbActive = true;
            win.__cbInterceptedText = null;

            copyBtn.click();

            // Wait for clipboard API / execCommand to fire
            await new Promise(r => setTimeout(r, 300));
            win.__cbActive = false;

            const backdrop = document.querySelector('.cdk-overlay-backdrop');
            if (backdrop) backdrop.click();
            else document.body.click();

            return win.__cbInterceptedText;
        }

        // --- MANUAL UI INJECTION ---

        function injectTurnButtons() {
            const turns = document.querySelectorAll('.chat-turn-container.model');
            turns.forEach(turn => {
                if (turn.querySelector('.cb-send-to-ide-btn')) return;

                const hasThumbUp = turn.querySelector('button[aria-label="Good response"]') !== null;
                if (!hasThumbUp) return;

                const isLoading = turn.querySelector('ms-chat-loading-indicator') !== null;
                if (isLoading) return;

                const actionBar = turn.querySelector('.actions.hover-or-edit');
                if (!actionBar) return;

                const sendBtn = document.createElement('button');
                sendBtn.className = 'cb-send-to-ide-btn';
                sendBtn.textContent = win.__cbIsBound ? `✨ Send to ${win.__cbProjectName}` : '🚫 IDE Not Bound';
                sendBtn.style.cssText = `
                    background: transparent; border-radius: 16px;
                    padding: 0 12px; margin-left: 8px; font-size: 13px; font-weight: 500; font-family: inherit;
                    cursor: pointer; display: inline-flex; align-items: center; justify-content: center;
                    height: 32px; transition: all 0.2s ease; white-space: nowrap; box-sizing: border-box;
                    color: ${win.__cbIsBound ? '#4CAF50' : '#757575'};
                    border: 1px solid ${win.__cbIsBound ? '#4CAF50' : '#757575'};
                    opacity: ${win.__cbIsBound ? '1' : '0.7'};
                    pointer-events: ${win.__cbIsBound ? 'auto' : 'none'};
                `;

                sendBtn.onmouseover = () => { if (win.__cbIsBound) sendBtn.style.background = 'rgba(76, 175, 80, 0.1)'; };
                sendBtn.onmouseout = () => { sendBtn.style.background = 'transparent'; };

                sendBtn.addEventListener('click', async (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    if (sendBtn.textContent.includes('...')) return;

                    const originalText = sendBtn.textContent;
                    sendBtn.textContent = '⏳ Copying...';
                    sendBtn.style.color = '#FF9800';
                    sendBtn.style.borderColor = '#FF9800';

                    const text = await extractViaNativeCopy(turn);
                    if (text) {
                        sendToIde(text);
                        sendBtn.textContent = '✅ Sent to IDE';
                        sendBtn.style.color = '#4CAF50';
                        sendBtn.style.borderColor = '#4CAF50';
                    } else {
                        sendBtn.textContent = '❌ Extract Failed';
                        sendBtn.style.color = '#F44336';
                        sendBtn.style.borderColor = '#F44336';
                    }

                    setTimeout(() => {
                        sendBtn.textContent = originalText;
                        sendBtn.style.color = '#4CAF50';
                        sendBtn.style.borderColor = '#4CAF50';
                    }, 3000);
                });

                actionBar.appendChild(sendBtn);
            });
        }

        setInterval(injectTurnButtons, 1000);

        // --- WEB UI: MODE TOGGLE & SUGGESTION BAR ---

        let suggestionBox = document.createElement('div');
        suggestionBox.id = 'cb-suggestion-box';
        suggestionBox.style.cssText = `
            position: absolute; bottom: 100%; left: 0; margin-bottom: 8px;
            background: #222; border: 1px solid #444; border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.3); z-index: 10000;
            display: none; flex-direction: column; min-width: 300px;
            font-family: Inter, sans-serif; font-size: 13px; overflow: hidden;
        `;

        const nativeTextAreaValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;

        function updateToggleStyles() {
            const askBtn = document.getElementById('cb-mode-ask');
            const editBtn = document.getElementById('cb-mode-edit');
            if (!askBtn || !editBtn) return;

            const activeStyle = 'background: #4CAF50; color: white; border: 1px solid #4CAF50; border-radius: 12px; padding: 4px 12px; cursor: pointer; font-weight: bold; transition: all 0.2s;';
            const inactiveStyle = 'background: transparent; color: #aaa; border: 1px solid #555; border-radius: 12px; padding: 4px 12px; cursor: pointer; transition: all 0.2s;';

            askBtn.style.cssText = win.__cbCurrentMode === 'ASK' ? activeStyle : inactiveStyle;
            editBtn.style.cssText = win.__cbCurrentMode === 'EDIT' ? activeStyle : inactiveStyle;
        }

        function injectModeToggle() {
            if (document.getElementById('cb-mode-toggle')) return;
            const textarea = document.querySelector('textarea[formcontrolname="promptText"]') || document.querySelector('textarea[aria-label="Enter a prompt"]');
            if (!textarea) return;

            const container = textarea.closest('ms-prompt-input-bar') || textarea.closest('.input-container') || textarea.parentElement;
            if (!container) return;

            const toggleContainer = document.createElement('div');
            toggleContainer.id = 'cb-mode-toggle';
            toggleContainer.style.cssText = `
                display: flex; gap: 8px; margin-bottom: 8px; padding-left: 8px;
                font-family: Inter, sans-serif; font-size: 13px; position: relative;
                opacity: ${win.__cbIsBound ? '1' : '0.5'};
                pointer-events: ${win.__cbIsBound ? 'auto' : 'none'};
            `;

            const askBtn = document.createElement('button');
            askBtn.id = 'cb-mode-ask';
            askBtn.textContent = '💬 Ask';
            askBtn.onclick = (e) => { e.preventDefault(); win.__cbCurrentMode = 'ASK'; updateToggleStyles(); };

            const editBtn = document.createElement('button');
            editBtn.id = 'cb-mode-edit';
            editBtn.textContent = '⚡ Edit';
            editBtn.onclick = (e) => { e.preventDefault(); win.__cbCurrentMode = 'EDIT'; updateToggleStyles(); };

            toggleContainer.appendChild(askBtn);
            toggleContainer.appendChild(editBtn);
            toggleContainer.appendChild(suggestionBox);

            container.parentElement.insertBefore(toggleContainer, container);
            updateToggleStyles();
        }

        setInterval(injectModeToggle, 1000);

        // --- WEB UI: AUTOCOMPLETE STATE ---

        let activePrefix = null;
        let filteredCmds = [];
        let selectedIndex = 0;

        function updateSuggestions() {
            if (!activePrefix || filteredCmds.length === 0) {
                suggestionBox.style.display = 'none';
                return;
            }

            while (suggestionBox.firstChild) {
                suggestionBox.removeChild(suggestionBox.firstChild);
            }

            filteredCmds.forEach((cmd, i) => {
                let item = document.createElement('div');
                item.style.cssText = `
                    padding: 8px 12px; cursor: pointer; display: flex; justify-content: space-between;
                    background: ${i === selectedIndex ? '#4CAF50' : 'transparent'};
                    color: ${i === selectedIndex ? '#fff' : '#ccc'};
                `;

                let nameStrong = document.createElement('strong');
                nameStrong.textContent = '/' + cmd.name;

                let modeSpan = document.createElement('span');
                modeSpan.style.cssText = 'opacity:0.7; font-size:11px;';
                modeSpan.textContent = cmd.mode;

                item.appendChild(nameStrong);
                item.appendChild(modeSpan);

                item.onmousedown = (e) => {
                    e.preventDefault();
                    applyCommand(cmd);
                };
                suggestionBox.appendChild(item);
            });
            suggestionBox.style.display = 'flex';
        }

        function applyCommand(cmd) {
            const textarea = document.querySelector('textarea[formcontrolname="promptText"]') || document.querySelector('textarea[aria-label="Enter a prompt"]');
            if (!textarea) return;

            let val = textarea.value;
            let cursor = textarea.selectionStart;
            let textBefore = val.substring(0, cursor);
            let textAfter = val.substring(cursor);

            let newTextBefore = textBefore.substring(0, textBefore.length - activePrefix.length) + '/' + cmd.name + ' ';

            nativeTextAreaValueSetter.call(textarea, newTextBefore + textAfter);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));
            textarea.selectionStart = textarea.selectionEnd = newTextBefore.length;
            textarea.focus();

            if (win.__cbCurrentMode !== cmd.mode) {
                win.__cbCurrentMode = cmd.mode;
                updateToggleStyles();
            }

            activePrefix = null;
            updateSuggestions();
        }

        function interceptAndWrap(textarea) {
            let val = textarea.value;
            if (!val.trim()) return;
            if (val.includes('<user_prompt mode=')) return;

            let remainingPrompt = val.trimStart();
            let appliedCommands = [];
            const commandRegex = /^\/([a-zA-Z0-9_-]+)\s*/;

            while (true) {
                const match = remainingPrompt.match(commandRegex);
                if (match) {
                    const cmdName = match[1];
                    const cmd = win.__cbCommands.find(c => c.name === cmdName);
                    if (cmd) {
                        appliedCommands.push(cmd);
                        remainingPrompt = remainingPrompt.substring(match[0].length).trimStart();
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            const mode = win.__cbCurrentMode || 'ASK';
            const finalUserPrompt = remainingPrompt || "Execute the applied commands.";

            let wrapped = "";
            if (appliedCommands.length > 0) {
                wrapped += "<applied_commands>\n";
                appliedCommands.forEach(cmd => {
                    wrapped += `  <command name="${cmd.name}">\n    ${cmd.promptBody.trim().replace(/\n/g, '\n    ')}\n  </command>\n`;
                });
                wrapped += "</applied_commands>\n\n";
            }
            wrapped += `<user_prompt mode="${mode}">\n${finalUserPrompt}\n</user_prompt>`;

            nativeTextAreaValueSetter.call(textarea, wrapped);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));
        }

        // --- WEB UI: EVENT LISTENERS ---

        document.addEventListener('input', (e) => {
            const textarea = e.target;
            if (textarea.tagName === 'TEXTAREA' && (textarea.getAttribute('formcontrolname') === 'promptText' || textarea.getAttribute('aria-label') === 'Enter a prompt')) {
                let val = textarea.value;

                if (val.includes('<user_prompt mode="EDIT">') && win.__cbCurrentMode !== 'EDIT') {
                    win.__cbCurrentMode = 'EDIT';
                    updateToggleStyles();
                } else if (val.includes('<user_prompt mode="ASK">') && win.__cbCurrentMode !== 'ASK') {
                    win.__cbCurrentMode = 'ASK';
                    updateToggleStyles();
                }

                let cursor = textarea.selectionStart;
                let textBefore = val.substring(0, cursor);
                let match = textBefore.match(/(?:^|\n)(\/[a-z]*)$/);

                if (match && win.__cbCommands && win.__cbCommands.length > 0) {
                    activePrefix = match[1];
                    let search = activePrefix.substring(1).toLowerCase();
                    filteredCmds = win.__cbCommands.filter(c => c.name.toLowerCase().startsWith(search));
                    if (selectedIndex >= filteredCmds.length) selectedIndex = 0;
                    updateSuggestions();
                } else {
                    activePrefix = null;
                    updateSuggestions();
                }
            }
        }, true);

        document.addEventListener('keydown', (e) => {
            const textarea = e.target;
            if (textarea.tagName === 'TEXTAREA' && (textarea.getAttribute('formcontrolname') === 'promptText' || textarea.getAttribute('aria-label') === 'Enter a prompt')) {

                if (activePrefix && filteredCmds.length > 0) {
                    if (e.key === 'ArrowDown') {
                        e.preventDefault(); e.stopPropagation();
                        selectedIndex = (selectedIndex + 1) % filteredCmds.length;
                        updateSuggestions();
                        return;
                    } else if (e.key === 'ArrowUp') {
                        e.preventDefault(); e.stopPropagation();
                        selectedIndex = (selectedIndex - 1 + filteredCmds.length) % filteredCmds.length;
                        updateSuggestions();
                        return;
                    } else if (e.key === 'Enter' || e.key === 'Tab') {
                        e.preventDefault(); e.stopPropagation();
                        applyCommand(filteredCmds[selectedIndex]);
                        return;
                    } else if (e.key === 'Escape') {
                        e.preventDefault(); e.stopPropagation();
                        activePrefix = null;
                        updateSuggestions();
                        return;
                    }
                }

                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey || e.altKey)) {
                    interceptAndWrap(textarea);
                }
            }
        }, true);

        document.addEventListener('click', (e) => {
            const btn = e.target.closest('button[type="submit"]') || e.target.closest('button[aria-label="Run"]') || e.target.closest('.send-button');
            if (btn) {
                const textarea = document.querySelector('textarea[formcontrolname="promptText"]') || document.querySelector('textarea[aria-label="Enter a prompt"]');
                if (textarea) interceptAndWrap(textarea);
            }
        }, true);

        // --- UTILITIES ---

        async function waitForElement(selector, timeout = 15000) {
            return new Promise((resolve) => {
                if (document.querySelector(selector)) return resolve(document.querySelector(selector));
                const observer = new MutationObserver(() => {
                    const el = document.querySelector(selector);
                    if (el) { observer.disconnect(); resolve(el); }
                });
                observer.observe(document.body, { childList: true, subtree: true });
                setTimeout(() => { observer.disconnect(); resolve(null); }, timeout);
            });
        }

        function base64ToFile(base64Data, mimeType, filename) {
            const byteString = atob(base64Data);
            const ab = new ArrayBuffer(byteString.length);
            const ia = new Uint8Array(ab);
            for (let i = 0; i < byteString.length; i++) {
                ia[i] = byteString.charCodeAt(i);
            }
            const blob = new Blob([ab], { type: mimeType });
            return new File([blob], filename, { type: mimeType });
        }

        function simulateFileDrop(files) {
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
        }

        async function setModel(modelId) {
            const selectorBtn = document.querySelector('.model-selector-card');
            if (selectorBtn) {
                selectorBtn.click();
                const option = await waitForElement(`button[id="model-carousel-row-${modelId}"]`, 3000);
                if (option) {
                    option.click();
                }
                await new Promise(r => setTimeout(r, 500));
            }
        }

        async function setThinkingLevel(level) {
            const select = document.querySelector('mat-select[aria-label="Thinking Level"]');
            if (select) {
                select.click();
                const panel = await waitForElement('.mat-mdc-select-panel', 3000);
                if (panel) {
                    const options = Array.from(panel.querySelectorAll('mat-option, .mat-mdc-option'));
                    const target = options.find(opt => opt.textContent.includes(level));
                    if (target) target.click();
                }
                await new Promise(r => setTimeout(r, 500));
            }
        }

        async function setTemperature(value) {
            const slider = document.querySelector('input[type="range"][aria-label="Temperature"]');
            if (slider) {
                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                nativeInputValueSetter.call(slider, value);
                slider.dispatchEvent(new Event('input', { bubbles: true }));
                slider.dispatchEvent(new Event('change', { bubbles: true }));
                await new Promise(r => setTimeout(r, 500));
            }
        }

        async function setUrlContext(enabled) {
            const toggleBtn = document.querySelector('button[role="switch"][aria-label="Browse the url context"]');
            if (toggleBtn) {
                const isChecked = toggleBtn.getAttribute('aria-checked') === 'true';
                if (isChecked !== enabled) {
                    toggleBtn.click();
                    await new Promise(r => setTimeout(r, 500));
                }
            }
        }

        async function deleteLastTwoTurns() {
            for (let i = 0; i < 2; i++) {
                const turns = document.querySelectorAll('.chat-turn-container');
                if (turns.length === 0) break;
                const lastTurn = turns[turns.length - 1];
                const menuBtn = lastTurn.querySelector('button[aria-label="Open options"]') || lastTurn.querySelector('ms-chat-turn-options button');
                if (menuBtn) {
                    menuBtn.click();
                    const menuPanel = await waitForElement('.mat-mdc-menu-panel', 2000);
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
        }

        // --- AUTOMATED GENERATION PIPELINES ---

        async function handleCommitGeneration(payloadObj) {
            showToast('⚙️ Configuring for Commit Generation...', '#FF9800', 0);

            await setModel('models/gemini-3.7-flash');
            await setThinkingLevel('High');
            await setUrlContext(true);

            const textarea = await waitForElement('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"]');
            if (!textarea) { isProcessing = false; return; }

            textarea.focus();
            nativeTextAreaValueSetter.call(textarea, payloadObj.text);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));

            setTimeout(async () => {
                const runBtn = await waitForElement('button[type="submit"]');
                if (runBtn) {
                    runBtn.click();
                    monitorCommitGeneration();
                } else {
                    isProcessing = false;
                }
            }, 300);
        }

        function monitorCommitGeneration() {
            showToast('⏳ Generating Commit Message...', '#FF9800', 0);
            setTimeout(() => {
                const checkInterval = setInterval(async () => {
                    const scrollBtn = document.querySelector('.scroll-to-bottom');
                    if (scrollBtn) scrollBtn.click();

                    const allTurns = document.querySelectorAll('.chat-turn-container.model');
                    if (allTurns.length === 0) return;

                    const lastTurn = allTurns[allTurns.length - 1];
                    lastTurn.scrollIntoView({ behavior: 'auto', block: 'end' });

                    const hasThumbUp = lastTurn.querySelector('button[aria-label="Good response"]') !== null;
                    const isLoading = lastTurn.querySelector('ms-chat-loading-indicator') !== null;

                    if (hasThumbUp && !isLoading) {
                        clearInterval(checkInterval);

                        showToast('⏳ Extracting Commit Message...', '#FF9800', 0);
                        const text = await extractViaNativeCopy(lastTurn);

                        if (text) {
                            const match = /<commit_message>([\s\S]*?)<\/commit_message>/.exec(text);
                            if (match && match[1]) {
                                sendToIde(`[COMMIT]${match[1].trim()}`);
                                showToast('✅ Sent Commit Message to IDE', '#4CAF50', 3000);
                            } else {
                                showToast('❌ Failed to extract <commit_message> tag', '#F44336', 3000);
                            }
                        } else {
                            showToast('❌ Failed to copy model response', '#F44336', 3000);
                        }

                        showToast('🧹 Cleaning up session...', '#FF9800', 0);
                        await deleteLastTwoTurns();

                        showToast('⚙️ Restoring Settings...', '#FF9800', 0);
                        await setModel('models/gemini-3.1-pro-preview');
                        await setTemperature(0.7);
                        await setUrlContext(true);

                        isProcessing = false;
                        showToast('✅ Ready', '#4CAF50', 3000);
                    }
                }, 1000);
            }, 2000);
        }

        function monitorStandardGeneration() {
            showToast('⏳ AI is Generating...', '#FF9800', 0);
            setTimeout(() => {
                const checkInterval = setInterval(async () => {
                    const scrollBtn = document.querySelector('.scroll-to-bottom');
                    if (scrollBtn) scrollBtn.click();

                    const allTurns = document.querySelectorAll('.chat-turn-container.model');
                    if (allTurns.length === 0) return;

                    const lastTurn = allTurns[allTurns.length - 1];
                    lastTurn.scrollIntoView({ behavior: 'auto', block: 'end' });

                    const hasThumbUp = lastTurn.querySelector('button[aria-label="Good response"]') !== null;
                    const isLoading = lastTurn.querySelector('ms-chat-loading-indicator') !== null;

                    if (hasThumbUp && !isLoading) {
                        clearInterval(checkInterval);
                        showToast('⏳ Syncing to IDE...', '#FF9800', 0);

                        const text = await extractViaNativeCopy(lastTurn);
                        if (text) {
                            sendToIde(text);
                            showToast('✅ Generation Complete & Synced', '#4CAF50', 3000);
                        } else {
                            showToast('⚠️ Generation Complete (Auto-sync failed)', '#FF9800', 3000);
                        }
                        isProcessing = false;
                    }
                }, 1000);
            }, 2000);
        }

        // --- IDE PAYLOAD HANDLER ---

        async function handleIncomingPayload(payloadString) {
            if (isProcessing) return;
            isProcessing = true;

            let payloadObj;
            try {
                payloadObj = JSON.parse(payloadString);
            } catch (e) {
                payloadObj = { text: payloadString, attachments: [], systemInstructions: "" };
            }

            if (payloadObj.isCommit) {
                handleCommitGeneration(payloadObj);
                return;
            }

            if (payloadObj.text) {
                const modeMatch = payloadObj.text.match(/<user_prompt mode="(ASK|EDIT)">/);
                if (modeMatch) {
                    win.__cbCurrentMode = modeMatch[1];
                    updateToggleStyles();
                }
            }

            if (payloadObj.systemInstructions) {
                const expectedTitle = "IntelliJ Context Bridge";
                const expectedFirstSentence = "You are an expert AI coding assistant";

                const sysCard = document.querySelector('[data-test-system-instructions-card]');
                if (sysCard) {
                    const subtitle = sysCard.querySelector('.subtitle');

                    if (!subtitle || !subtitle.textContent.includes(expectedFirstSentence)) {
                        showToast('⚙️ Updating System Instructions...', '#FF9800', 0);
                        sysCard.click();

                        const dialog = await waitForElement('mat-dialog-container', 3000);
                        if (dialog) {
                            await new Promise(r => setTimeout(r, 500));

                            const select = dialog.querySelector('mat-select');
                            if (select) {
                                select.click();
                                await waitForElement('.mat-mdc-select-panel mat-option', 3000);
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
                                    nativeInputValueSetter.call(titleInput, expectedTitle);
                                    titleInput.dispatchEvent(new Event('input', { bubbles: true }));
                                    titleInput.dispatchEvent(new Event('change', { bubbles: true }));
                                    titleInput.dispatchEvent(new Event('blur', { bubbles: true }));
                                }

                                const sysTextarea = dialog.querySelector('textarea[aria-label="System instructions"]');
                                if (sysTextarea && sysTextarea.value !== payloadObj.systemInstructions) {
                                    sysTextarea.focus();
                                    nativeTextAreaValueSetter.call(sysTextarea, payloadObj.systemInstructions);
                                    sysTextarea.dispatchEvent(new Event('input', { bubbles: true }));
                                    sysTextarea.dispatchEvent(new Event('change', { bubbles: true }));
                                    sysTextarea.dispatchEvent(new Event('blur', { bubbles: true }));
                                }

                                await new Promise(r => setTimeout(r, 800));
                            }

                            const closeBtn = dialog.querySelector('button[aria-label="Close panel"], button[data-test-close-button]');
                            if (closeBtn) closeBtn.click();

                            await new Promise(r => setTimeout(r, 500));
                        }
                    }
                }
            }

            if (payloadObj.attachments && payloadObj.attachments.length > 0) {
                showToast(`📎 Attaching ${payloadObj.attachments.length} files...`, '#FF9800', 0);
                const files = payloadObj.attachments.map(att => base64ToFile(att.base64Data, att.mimeType, att.name));
                simulateFileDrop(files);
                await new Promise(r => setTimeout(r, 2000));
            }

            const textarea = await waitForElement('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"]');
            if (!textarea) { isProcessing = false; return; }

            textarea.focus();
            nativeTextAreaValueSetter.call(textarea, payloadObj.text);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));

            setTimeout(async () => {
                const runBtn = await waitForElement('button[type="submit"]');
                if (runBtn) {
                    runBtn.click();
                    monitorStandardGeneration();
                } else {
                    isProcessing = false;
                }
            }, 300);
        }
    });

})();