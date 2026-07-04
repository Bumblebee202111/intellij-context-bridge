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

    function updateFab(text, color) {
        const fab = document.getElementById('context-bridge-fab');
        if (fab) {
            fab.textContent = text;
            fab.style.backgroundColor = color;
        }
    }

    function sendToIde(text) {
        if (window.__ws && window.__ws.readyState === WebSocket.OPEN) {
            window.__ws.send(text);
            updateFab('✅ Sent', '#2196F3');
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

        const fab = document.createElement('button');
        fab.id = 'context-bridge-fab';
        fab.textContent = '🔌 Disconnected';
        fab.style.cssText = `position: fixed; bottom: 24px; right: 24px; z-index: 999999; background-color: #F44336; color: white; border: none; border-radius: 24px; padding: 12px 20px; font-family: Inter, sans-serif; font-size: 14px; font-weight: bold; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.3); transition: all 0.3s ease;`;
        document.body.appendChild(fab);

        fab.addEventListener('click', (e) => {
            e.preventDefault();
            if (fab.textContent.includes('Generating') || fab.textContent.includes('...')) return;
            extractViaNativeCopy();
        });

        function connect() {
            window.__ws = new WebSocket('ws://127.0.0.1:37373/ai-bridge');
            window.__ws.onopen = () => updateFab('✨ Send to IDE', '#4CAF50');
            window.__ws.onmessage = (event) => handleIncomingPayload(event.data);
            window.__ws.onclose = () => { updateFab('🔌 Disconnected', '#F44336'); setTimeout(connect, 3000); };
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

        // --- Base64 to File Converter ---
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

        // --- Drag and Drop Simulator ---
        function simulateFileDrop(files) {
            // AI Studio has a specific div for drag & drop, or we fallback to document.body
            const dropZone = document.querySelector('[msglobalfiledragdrop]') || document.body;

            const dataTransfer = new DataTransfer();
            files.forEach(file => dataTransfer.items.add(file));

            // Dispatch dragenter, dragover, and drop to satisfy Angular's event listeners
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
                // Try to parse as JSON (New format)
                payloadObj = JSON.parse(payloadString);
            } catch (e) {
                // Fallback for old plain-text payloads
                payloadObj = { text: payloadString, attachments: [] };
            }

            // 1. Handle Media Attachments
            if (payloadObj.attachments && payloadObj.attachments.length > 0) {
                updateFab(`📎 Attaching ${payloadObj.attachments.length} files...`, '#FF9800');

                const files = payloadObj.attachments.map(att =>
                    base64ToFile(att.base64Data, att.mimeType, att.name)
                );

                simulateFileDrop(files);

                // Wait 2 seconds for AI Studio to finish processing/uploading the dropped files
                // before we paste the text and hit run.
                await new Promise(r => setTimeout(r, 2000));
            }

            // 2. Inject Text Prompt
            const textarea = await waitForElement('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"]');
            if (!textarea) { isProcessing = false; return; }

            textarea.focus();
            const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
            nativeInputValueSetter.call(textarea, payloadObj.text);
            textarea.dispatchEvent(new Event('input', { bubbles: true }));

            // 3. Trigger Run
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
            updateFab('⏳ Generating...', '#FF9800');
            setTimeout(() => {
                const checkInterval = setInterval(() => {
                    const scrollBtn = document.querySelector('.scroll-to-bottom');
                    if (scrollBtn) scrollBtn.click();

                    const allTurns = document.querySelectorAll('ms-chat-turn');
                    if (allTurns.length === 0) return;

                    const lastTurn = allTurns[allTurns.length - 1];
                    lastTurn.scrollIntoView({ behavior: 'auto', block: 'end' });

                    const hasThumbUp = lastTurn.querySelector('button[aria-label="Good response"]') ||
                        Array.from(lastTurn.querySelectorAll('span')).some(s => s.textContent.trim() === 'thumb_up');
                    const isLoading = document.querySelector('ms-chat-loading-indicator') !== null;

                    if (hasThumbUp && !isLoading) {
                        clearInterval(checkInterval);
                        isProcessing = false;
                        updateFab('✨ Send to IDE', '#4CAF50');
                    }
                }, 1000);
            }, 2000);
        }

        async function extractViaNativeCopy() {
            updateFab('🔍 Finding menu...', '#FF9800');
            const allTurns = document.querySelectorAll('.chat-turn-container.model');
            if (allTurns.length === 0) return updateFab('❌ No AI response', '#F44336');
            const lastTurn = allTurns[allTurns.length - 1];

            const menuBtn = lastTurn.querySelector('button[aria-label="Open options"]') || lastTurn.querySelector('ms-chat-turn-options button');
            if (!menuBtn) return updateFab('❌ Menu not found', '#F44336');

            menuBtn.click();
            updateFab('⏳ Waiting for copy btn...', '#FF9800');

            const copyIcon = await waitForElement('.cdk-overlay-container .copy-markdown-button', 3000);
            if (!copyIcon) {
                document.body.click();
                return updateFab('❌ Copy btn not found', '#F44336');
            }

            const copyBtn = copyIcon.closest('button');

            win.__cbActive = true;
            win.__cbIntercepted = false;
            updateFab('🪤 Intercepting...', '#FF9800');

            copyBtn.click();

            setTimeout(async () => {
                win.__cbActive = false;

                const backdrop = document.querySelector('.cdk-overlay-backdrop');
                if (backdrop) backdrop.click();
                else document.body.click();

                if (!win.__cbIntercepted) {
                    updateFab('❌ Intercept Failed', '#F44336');
                }

                setTimeout(() => {
                    if (document.getElementById('context-bridge-fab').textContent.includes('❌') ||
                        document.getElementById('context-bridge-fab').textContent.includes('✅')) {
                        updateFab('✨ Send to IDE', '#4CAF50');
                    }
                }, 3000);

            }, 500);
        }

        connect();
    });

})();