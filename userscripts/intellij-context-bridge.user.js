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
    win.__cbIntercepted = false;

    // --- Global Status Pill (Replaces the heavy FAB) ---
    let statusPill;
    let toastTimeout;

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

    // --- Clipboard Interception ---
    function sendToIde(text) {
        if (window.__ws && window.__ws.readyState === WebSocket.OPEN) {
            window.__ws.send(text);
            win.__cbIntercepted = true;
        }
    }

    const origExec = win.document.execCommand;
    win.document.execCommand = function(command, showUI, value) {
        if (win.__cbActive && command.toLowerCase() === 'copy') {
            const activeEl = win.document.activeElement;
            let text = activeEl && (activeEl.tagName === 'TEXTAREA' || activeEl.tagName === 'INPUT')
                ? activeEl.value.substring(activeEl.selectionStart, activeEl.selectionEnd)
                : win.getSelection().toString();
            if (text) sendToIde(text);
        }
        return origExec.apply(this, arguments);
    };

    document.addEventListener('DOMContentLoaded', () => {
        let isProcessing = false;
        initStatusPill();

        // --- Per-Turn Button Injection ---
        function injectTurnButtons() {
            // Target the actual model turn containers
            const turns = document.querySelectorAll('.chat-turn-container.model');
            turns.forEach(turn => {
                // Skip if we already injected the button
                if (turn.querySelector('.cb-send-to-ide-btn')) return;

                // 1. Ensure it's fully generated AND not a "Thoughts" turn
                // "Thoughts" turns do not have a thumb_up button.
                const hasThumbUp = turn.querySelector('button[aria-label="Good response"]') !== null;
                if (!hasThumbUp) return;

                // 2. Ensure it's not currently loading
                const isLoading = turn.querySelector('ms-chat-loading-indicator') !== null;
                if (isLoading) return;

                // 3. Find the exact action bar to align perfectly with native buttons
                const actionBar = turn.querySelector('.actions.hover-or-edit');
                if (!actionBar) return;

                const sendBtn = document.createElement('button');
                sendBtn.className = 'cb-send-to-ide-btn';
                sendBtn.textContent = '✨ Send to IDE';
                sendBtn.style.cssText = `
                    background: transparent; color: #4CAF50; border: 1px solid #4CAF50; border-radius: 16px;
                    padding: 0 12px; margin-left: 8px; font-size: 13px; font-weight: 500; font-family: inherit;
                    cursor: pointer; display: inline-flex; align-items: center; justify-content: center;
                    height: 32px; transition: all 0.2s ease; white-space: nowrap; box-sizing: border-box;
                `;

                sendBtn.onmouseover = () => { sendBtn.style.background = 'rgba(76, 175, 80, 0.1)'; };
                sendBtn.onmouseout = () => { sendBtn.style.background = 'transparent'; };

                sendBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    if (sendBtn.textContent.includes('...')) return;
                    extractViaNativeCopy(turn, sendBtn);
                });

                actionBar.appendChild(sendBtn);
            });
        }

        // Run the injector loop to catch newly generated turns
        setInterval(injectTurnButtons, 1000);

        // --- WebSocket Connection ---
        function connect() {
            window.__ws = new WebSocket('ws://127.0.0.1:37373/ai-bridge');
            window.__ws.onopen = () => showToast('🔗 IDE Connected', '#4CAF50', 3000);
            window.__ws.onmessage = (event) => handleIncomingPayload(event.data);
            window.__ws.onclose = () => { showToast('🔌 IDE Disconnected', '#F44336', 0); setTimeout(connect, 3000); };
            window.__ws.onerror = () => window.__ws.close();
        }

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

        async function handleIncomingPayload(payloadString) {
            if (isProcessing) return;
            isProcessing = true;

            let payloadObj;
            try {
                payloadObj = JSON.parse(payloadString);
            } catch (e) {
                payloadObj = { text: payloadString, attachments: [] };
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
            const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
            nativeInputValueSetter.call(textarea, payloadObj.text);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));

            setTimeout(async () => {
                const runBtn = await waitForElement('button[type="submit"]');
                if (runBtn) {
                    runBtn.click();
                    monitorGeneration();
                } else {
                    isProcessing = false;
                }
            }, 300);
        }

        function monitorGeneration() {
            showToast('⏳ AI is Generating...', '#FF9800', 0);
            setTimeout(() => {
                const checkInterval = setInterval(() => {
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
                        isProcessing = false;
                        showToast('✅ Generation Complete', '#4CAF50', 3000);
                    }
                }, 1000);
            }, 2000);
        }

        // --- Scoped Extraction Logic ---
        async function extractViaNativeCopy(turnElement, btnElement) {
            const originalText = btnElement.textContent;
            btnElement.textContent = '⏳ Copying...';
            btnElement.style.color = '#FF9800';
            btnElement.style.borderColor = '#FF9800';

            const menuBtn = turnElement.querySelector('button[aria-label="Open options"]') || turnElement.querySelector('ms-chat-turn-options button');
            if (!menuBtn) {
                btnElement.textContent = '❌ Menu Error';
                return;
            }

            menuBtn.click();

            const copyIcon = await waitForElement('.cdk-overlay-container .copy-markdown-button', 3000);
            if (!copyIcon) {
                document.body.click();
                btnElement.textContent = '❌ Copy Btn Error';
                return;
            }

            const copyBtn = copyIcon.closest('button');

            win.__cbActive = true;
            win.__cbIntercepted = false;

            copyBtn.click();

            setTimeout(async () => {
                win.__cbActive = false;

                const backdrop = document.querySelector('.cdk-overlay-backdrop');
                if (backdrop) backdrop.click();
                else document.body.click();

                if (win.__cbIntercepted) {
                    btnElement.textContent = '✅ Sent to IDE';
                    btnElement.style.color = '#4CAF50';
                    btnElement.style.borderColor = '#4CAF50';
                } else {
                    btnElement.textContent = '❌ Intercept Failed';
                    btnElement.style.color = '#F44336';
                    btnElement.style.borderColor = '#F44336';
                }

                setTimeout(() => {
                    btnElement.textContent = originalText;
                    btnElement.style.color = '#4CAF50';
                    btnElement.style.borderColor = '#4CAF50';
                }, 3000);

            }, 500);
        }

        connect();
    });

})();