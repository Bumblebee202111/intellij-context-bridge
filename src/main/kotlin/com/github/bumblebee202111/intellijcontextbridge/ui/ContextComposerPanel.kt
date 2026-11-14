package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandRegistryService
import com.github.bumblebee202111.intellijcontextbridge.commands.SlashCommand
import com.github.bumblebee202111.intellijcontextbridge.context.core.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.server.BridgeSessionService
import com.github.bumblebee202111.intellijcontextbridge.server.BrowserTab
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.IntentMode
import com.intellij.codeInsight.AutoPopupController
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

class ContextComposerPanel(private val project: Project) : Disposable {
    companion object {
        private const val VIEW_CONTEXT = "CONTEXT"
        private const val VIEW_CHAT = "CHAT"
    }

    private val contextState = project.service<ContextState>()
    private val bridgeSession = project.service<BridgeSessionService>()
    private val commandRegistry = project.service<CommandRegistryService>()

    private var activeView = VIEW_CONTEXT
    private var historyIndex = -1
    private var draftPrompt = ""
    private var lastAppliedCommands = emptyList<String>()
    private var appliedCommandNames = emptyList<String>()

    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    private val promptCompletionProvider = PromptCompletionProvider(
        project = project,
        onFileMentionSelected = ::applyFileMentionContext
    )

    private val treeManager = ContextTreeManager(
        project = project,
        contextState = contextState,
        onVfsRefreshed = { projectDir, externalRoots ->
            val items = promptCompletionProvider.collectFileMentionItems(projectDir, externalRoots)
            ApplicationManager.getApplication().invokeLater({
                promptCompletionProvider.updateFileMentionItems(items)
            }, ModalityState.nonModal())
        }
    )

    private val mainCardLayout = CardLayout()
    private val mainViewport = JPanel(mainCardLayout)

    private val chatTimelinePanel = ChatTimelinePanel(
        project = project,
        onStageReadFiles = { paths ->
            handleReadToolAction(paths, stagePending = true) {
                switchView(VIEW_CONTEXT)
            }
        },
        onSendRequestedFiles = { paths ->
            handleReadToolAction(paths, stagePending = false) {
                executePayloadAction(isWsSend = bridgeSession.isBoundTabConnected())
            }
        },
        onHistoryChanged = {
            refreshUi(vfsStructureChanged = true, forceMainRebuild = true)
        }
    )

    private val promptArea = TextFieldWithAutoCompletion(
        project,
        promptCompletionProvider,
        false,
        ""
    ).apply {
        setOneLineMode(false)
        setPlaceholder("Type your prompt here... ('/' for commands, '@' to mention files)")
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            editor.settings.additionalLinesCount = 0
        }
    }

    private val askRadio = JBRadioButton("💬 Ask", true)
    private val editRadio = JBRadioButton("⚡ Edit")
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy)
    private val tabComboBox = ComboBox<BrowserTab>().apply {
        toolTipText = "Select the target AI Studio tab"
        minimumSize = Dimension(100, minimumSize.height)
        preferredSize = Dimension(180, preferredSize.height)
    }
    private val sendWsButton = JButton("Select Tab to Send").apply {
        isEnabled = false
        toolTipText = "Send context directly to the selected AI Studio tab"
        putClientProperty("JButton.buttonType", "defaultButton")
        preferredSize = Dimension(preferredSize.width, preferredSize.height)
    }
    private val loadingIcon = AsyncProcessIcon("PayloadGenerator").apply { isVisible = false }

    val content: JPanel = JPanel(BorderLayout())

    private var isUpdatingTabs = false
    private val NONE_TAB = BrowserTab("", "", "<None / Disconnected>")

    init {
        Disposer.register(this, treeManager)

        ContextChangeTracker(this, project, contextState) { vfsStructureChanged ->
            refreshUi(vfsStructureChanged = vfsStructureChanged)
        }

        mainViewport.add(treeManager.content, VIEW_CONTEXT)
        mainViewport.add(chatTimelinePanel.content, VIEW_CHAT)

        val splitPane = JBSplitter(true, 0.7f).apply {
            firstComponent = mainViewport
            secondComponent = setupBottomPanel()
        }

        content.add(setupTopToolbar(), BorderLayout.NORTH)
        content.add(splitPane, BorderLayout.CENTER)

        tabComboBox.addItemListener { event ->
            if (!isUpdatingTabs && event.stateChange == ItemEvent.SELECTED) {
                val selected = event.item as? BrowserTab ?: return@addItemListener
                bridgeSession.changeBinding(selected.id, selected.pathname, selected.title)
            }
        }

        bridgeSession.addStateListener(this, ::updateTabsUI)
        bridgeSession.addResponseListener(this) {
            ApplicationManager.getApplication().invokeLater({
                switchView(VIEW_CHAT, scrollToBottom = true)
                refreshUi()
            }, ModalityState.nonModal())
        }
        commandRegistry.addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater({
                promptCompletionProvider.refreshItems()
            }, ModalityState.nonModal())
        }
        updateTabsUI()

        chatTimelinePanel.refresh()
        refreshUi()
    }

    private fun switchView(view: String, scrollToBottom: Boolean = false) {
        if (activeView != view) {
            activeView = view
            mainCardLayout.show(mainViewport, view)
            if (view == VIEW_CHAT) {
                chatTimelinePanel.refresh(scrollToBottom = scrollToBottom)
            } else {
                refreshUi()
            }
        } else if (view == VIEW_CHAT && scrollToBottom) {
            chatTimelinePanel.refresh(scrollToBottom = true)
        }
    }

    private fun updateTabsUI() {
        val displayTabs = bridgeSession.getDisplayTabs()
        val activeTabId = bridgeSession.activeTabId
        SwingUtilities.invokeLater {
            isUpdatingTabs = true
            try {
                tabComboBox.removeAllItems()
                tabComboBox.addItem(NONE_TAB)

                var matchedItem: BrowserTab? = null
                displayTabs.forEach { tab ->
                    tabComboBox.addItem(tab)
                    if (tab.id == activeTabId) {
                        matchedItem = tab
                    }
                }

                tabComboBox.selectedItem = matchedItem ?: NONE_TAB
                updateSendButtonState()
            } finally {
                isUpdatingTabs = false
            }
        }
    }

    private fun updateSendButtonState() {
        if (bridgeSession.activeTabId == null) {
            sendWsButton.text = "Select Tab to Send"
            sendWsButton.isEnabled = false
        } else if (!bridgeSession.isBoundTabConnected()) {
            sendWsButton.text = "Disconnected"
            sendWsButton.isEnabled = false
        } else {
            sendWsButton.text = "Send to AI Studio"
            sendWsButton.isEnabled = !loadingIcon.isVisible
        }
    }

    private fun handleReadToolAction(
        paths: List<String>,
        stagePending: Boolean,
        onSuccess: () -> Unit
    ) {
        contextState.scope.launch {
            val requestedFiles = readAction {
                if (stagePending) {
                    contextState.stageAiReadRequests(paths)
                } else {
                    contextState.promoteFiles(paths)
                }
            }

            ApplicationManager.getApplication().invokeLater({
                if (requestedFiles.isEmpty()) {
                    Messages.showWarningDialog(
                        "The AI requested files, but none were found in the project.\n\nPaths: ${paths.joinToString()}",
                        "Tool Call: read_file"
                    )
                    return@invokeLater
                }
                onSuccess()
            }, ModalityState.nonModal())
        }
    }

    fun applyCommandContext(command: SlashCommand) {
        contextState.scope.launch {
            readAction {
                contextState.applyCommandContext(command)
            }
            ApplicationManager.getApplication().invokeLater({
                refreshUi()
            }, ModalityState.nonModal())
        }
    }

    private fun applyFileMentionContext(file: VirtualFile) {
        contextState.scope.launch {
            readAction {
                contextState.applyFileMention(file)
            }
            ApplicationManager.getApplication().invokeLater({
                refreshUi()
            }, ModalityState.nonModal())
        }
    }

    private fun pasteResponseFromClipboard() {
        val clipboardText = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (clipboardText.isNullOrBlank()) {
            Messages.showInfoMessage(project, "Clipboard is empty.", "Paste Response")
            return
        }
        bridgeSession.processIncomingResponse(clipboardText, fromWebSocket = false)
    }

    private fun setupTopToolbar(): JPanel {
        val topToolbar = JPanel(BorderLayout(5, 0)).apply { border = JBUI.Borders.empty(5) }

        val actionGroup = DefaultActionGroup().apply {
            add(object : ToggleAction("Context", "Show project context tree", AllIcons.Nodes.Folder) {
                override fun isSelected(e: AnActionEvent): Boolean = activeView == VIEW_CONTEXT
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) switchView(VIEW_CONTEXT)
                }
                override fun displayTextInToolbar(): Boolean = true
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : ToggleAction("Chat", "Show conversation timeline and AI tool actions", AllIcons.Toolwindows.ToolWindowMessages) {
                override fun isSelected(e: AnActionEvent): Boolean = activeView == VIEW_CHAT
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) switchView(VIEW_CHAT)
                }
                override fun update(e: AnActionEvent) {
                    super.update(e)
                    val state = contextState.getState()
                    val count = state.turns.size + state.modelTurns.size
                    e.presentation.text = if (count > 0) "Chat ($count)" else "Chat"
                }
                override fun displayTextInToolbar(): Boolean = true
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object :
                AnAction("Add Active File", "Add currently opened editor file to context", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
                    if (file != null) {
                        treeManager.attachExternalFiles { listOf(file) }
                    } else {
                        Messages.showInfoMessage("No active editor found.", "Add Active File")
                    }
                }
            })
            add(object : AnAction(
                "Attach External Files or Folders...",
                "Pick files or directories outside the project to include in context (or Drop / Ctrl+V in tree)",
                AllIcons.General.OpenDisk
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val descriptor = FileChooserDescriptorFactory.createAllButJarContentsDescriptor().apply {
                        title = "Attach External Files or Folders"
                        description = "Select files or folders to include in Context Bridge"
                    }
                    FileChooser.chooseFiles(descriptor, project, null) { files ->
                        treeManager.attachExternalFiles { files }
                    }
                }
            })
            add(object : ToggleAction(
                "Show Selected Context Only",
                "Filter to show only selected files",
                AllIcons.General.Filter
            ) {
                override fun isSelected(e: AnActionEvent): Boolean = treeManager.showSelectedOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    treeManager.showSelectedOnly = state
                    switchView(VIEW_CONTEXT)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction(
                "Paste AI Response",
                "Parse AI Markdown response and tool calls from clipboard",
                AllIcons.Actions.MenuPaste
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    pasteResponseFromClipboard()
                }
            })
            add(object : AnAction("Run Web UI Diagnostics", "Test stability of background DOM interactions", AllIcons.Actions.StartDebugger) {
                override fun actionPerformed(e: AnActionEvent) {
                    if (bridgeSession.isBoundTabConnected()) {
                        DiagnosticDialog(project).show()
                    } else {
                        Messages.showWarningDialog("No active AI Studio tab bound. Please select a connected tab first.", "Diagnostics Failed")
                    }
                }
            })
        }

        val nativeToolbar =
            ActionManager.getInstance().createActionToolbar("ContextComposerTopToolbar", actionGroup, true).apply {
                targetComponent = content
            }

        topToolbar.add(nativeToolbar.component, BorderLayout.WEST)
        topToolbar.add(tabComboBox, BorderLayout.CENTER)
        return topToolbar
    }

    private fun setupBottomPanel(): JPanel {
        val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }

        promptArea.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (historyIndex == -1) {
                    draftPrompt = promptArea.text
                }

                val (currentCommands, _) = commandRegistry.parseLeadingCommands(promptArea.text)

                val currentCommandNames = currentCommands.map { it.name }
                if (currentCommandNames != lastAppliedCommands) {
                    lastAppliedCommands = currentCommandNames
                    if (currentCommands.isNotEmpty()) {
                        if (currentCommands.any { it.mode == IntentMode.EDIT }) {
                            editRadio.isSelected = true
                        } else {
                            askRadio.isSelected = true
                        }
                    }
                }

                val committedCommands = commandRegistry.parseCommittedLeadingCommands(promptArea.text)
                val committedNames = committedCommands.map { it.name }
                if (committedNames != appliedCommandNames) {
                    val newlyCommitted = committedCommands.filter { it.name !in appliedCommandNames }
                    appliedCommandNames = committedNames
                    newlyCommitted.forEach(::applyCommandContext)
                }

                val newText = event.newFragment.toString()
                if (newText == "/" || newText == "@") {
                    val offset = event.offset
                    val docText = event.document.text
                    val isWordStart =
                        offset == 0 || docText.getOrNull(offset - 1) == '\n' || docText.getOrNull(offset - 1) == ' '
                    val shouldPopup = if (newText == "@") {
                        isWordStart
                    } else {
                        commandRegistry.isAtCommandPosition(docText.substring(0, offset))
                    }
                    if (shouldPopup) {
                        ApplicationManager.getApplication().invokeLater {
                            promptArea.editor?.let { editor ->
                                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                            }
                        }
                    }
                }
            }
        })

        setupPromptHistoryShortcuts()

        ButtonGroup().apply {
            add(askRadio)
            add(editRadio)
        }
        val intentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            border = JBUI.Borders.emptyBottom(5)
            add(JLabel("Mode:"))
            add(askRadio)
            add(editRadio)
        }

        val promptContainer = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Prompt (Ctrl+Up for history)")
            add(intentPanel, BorderLayout.NORTH)
            add(promptArea, BorderLayout.CENTER)
        }
        bottomPanel.add(promptContainer, BorderLayout.CENTER)

        val bottomActionGroup = DefaultActionGroup().apply {
            add(object : AnAction("New Chat", "Clear context and start a new chat", AllIcons.General.Reset) {
                override fun actionPerformed(e: AnActionEvent) {
                    contextState.clear()
                    promptArea.text = ""
                    historyIndex = -1
                    draftPrompt = ""
                    appliedCommandNames = emptyList()
                    treeManager.reset()
                    chatTimelinePanel.refresh()
                }
            })
            add(object : AnAction("Undo Last", "Undo last sent prompt", AllIcons.Actions.Undo) {
                override fun actionPerformed(e: AnActionEvent) {
                    val turn = contextState.removeLastTurn()
                    if (turn != null) {
                        promptArea.text = turn.prompt
                        contextState.scope.launch {
                            readAction {
                                contextState.restoreFromLastTurn()
                            }
                            ApplicationManager.getApplication().invokeLater({
                                chatTimelinePanel.refresh()
                                refreshUi(vfsStructureChanged = true, forceMainRebuild = true)
                            }, ModalityState.nonModal())
                        }
                    }
                }

                override fun update(e: AnActionEvent) {
                    val lastTurn = contextState.getLastTurn()
                    e.presentation.isEnabled = lastTurn != null
                    if (lastTurn != null) {
                        val snippet = lastTurn.prompt.replace("\n", " ").take(40)
                        val displaySnippet = if (lastTurn.prompt.length > 40) "$snippet..." else snippet
                        val time = dateFormat.format(Date(lastTurn.timestamp))
                        e.presentation.description = "Undo Last Send ($time): $displaySnippet"
                    } else {
                        e.presentation.description = "No previous sends to undo"
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val bottomToolbar =
            ActionManager.getInstance().createActionToolbar("ContextComposerBottomToolbar", bottomActionGroup, true)
                .apply {
                    targetComponent = promptArea
                }

        copyButton.addActionListener { executePayloadAction(isWsSend = false) }
        sendWsButton.addActionListener {
            if (bridgeSession.isBoundTabConnected()) {
                executePayloadAction(isWsSend = true)
            }
        }
        val actionButtonPanel = JPanel(BorderLayout(5, 0)).apply {
            border = JBUI.Borders.emptyTop(5)
            add(bottomToolbar.component, BorderLayout.WEST)

            val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(copyButton)
                add(sendWsButton)
                add(loadingIcon)
            }
            add(rightButtons, BorderLayout.EAST)
        }

        bottomPanel.add(actionButtonPanel, BorderLayout.SOUTH)
        return bottomPanel
    }

    private fun setupPromptHistoryShortcuts() {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val history = contextState.getPromptHistory()
                if (history.isEmpty()) return
                if (historyIndex == -1) {
                    draftPrompt = promptArea.text
                    historyIndex = history.size - 1
                    promptArea.text = history[historyIndex]
                } else if (historyIndex > 0) {
                    historyIndex--
                    promptArea.text = history[historyIndex]
                }
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyStroke.getKeyStroke(
                    KeyEvent.VK_UP,
                    InputEvent.CTRL_DOWN_MASK
                )
            ), promptArea
        )

        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val history = contextState.getPromptHistory()
                if (historyIndex != -1) {
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        promptArea.text = history[historyIndex]
                    } else if (historyIndex == history.size - 1) {
                        historyIndex = -1
                        promptArea.text = draftPrompt
                    }
                }
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN,
                    InputEvent.CTRL_DOWN_MASK
                )
            ), promptArea
        )
    }

    private fun executePayloadAction(isWsSend: Boolean) {
        val promptText = promptArea.text
        val intent = if (askRadio.isSelected) IntentMode.ASK else IntentMode.EDIT
        val alreadyApplied = appliedCommandNames.toSet()
        historyIndex = -1
        draftPrompt = ""

        contextState.pendingAiRequests.clear()
        setGeneratingState(true)

        contextState.scope.launch {
            try {
                val result = PayloadGenerator.generatePayload(
                    project = project,
                    contextState = contextState,
                    userPrompt = promptText,
                    intentMode = intent,
                    alreadyAppliedCommands = alreadyApplied
                )
                ApplicationManager.getApplication().invokeLater({
                    val payloadObj = result.payload
                    contextState.addTurn(result.turn)
                    chatTimelinePanel.refresh(scrollToBottom = true)
                    refreshUi()

                    if (isWsSend && bridgeSession.sendPayload(payloadObj)) {
                        val originalText = sendWsButton.text
                        sendWsButton.text = "Sent!"
                        Timer(1500) { sendWsButton.text = originalText }.apply { isRepeats = false }.start()
                    } else {
                        CopyPasteManager.getInstance().setContents(StringSelection(payloadObj.text))
                        if (payloadObj.attachments.isNotEmpty()) {
                            Messages.showWarningDialog(
                                "You copied ${payloadObj.attachments.size} media file(s). They cannot be copied to the clipboard.",
                                "Media Files Skipped"
                            )
                        }
                        val originalText = copyButton.text
                        copyButton.text = "Copied!"
                        Timer(1500) { copyButton.text = originalText }.apply { isRepeats = false }.start()
                    }

                    promptArea.text = ""
                    appliedCommandNames = emptyList()
                    setGeneratingState(false)
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    setGeneratingState(false)
                }, ModalityState.nonModal())
            }
        }
    }

    private fun setGeneratingState(isGenerating: Boolean) {
        loadingIcon.isVisible = isGenerating
        copyButton.isEnabled = !isGenerating
        updateSendButtonState()
    }

    fun refreshUi(vfsStructureChanged: Boolean = false, forceMainRebuild: Boolean = false) {
        promptCompletionProvider.refreshItems()
        treeManager.requestRefresh(vfsStructureChanged = vfsStructureChanged, forceMainRebuild = forceMainRebuild)
    }

    override fun dispose() {
        // Cleaned up via Disposer hierarchy
    }
}