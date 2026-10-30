package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandContextAction
import com.github.bumblebee202111.intellijcontextbridge.commands.CommandRegistryService
import com.github.bumblebee202111.intellijcontextbridge.commands.SlashCommand
import com.github.bumblebee202111.intellijcontextbridge.commands.SyncCommand
import com.github.bumblebee202111.intellijcontextbridge.context.core.ContextSuggestionEngine
import com.github.bumblebee202111.intellijcontextbridge.context.core.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.server.BrowserTab
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ContextComposerPanel(private val project: Project) : Disposable {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
    private val treeManager = ContextTreeManager(project, contextState)

    private var historyIndex = -1
    private var draftPrompt = ""
    private var lastAppliedCommands = emptyList<String>()

    @Volatile
    private var lastDedupedFiles = emptySet<VirtualFile>()
    private var isConfigLoaded = false

    private var pendingAiRequests = emptySet<VirtualFile>()

    @Volatile
    private var vfsStructureDirty = true

    @Volatile
    private var mainTreeForceRebuild = true

    private var lastSuggestions: Set<VirtualFile>? = null
    private var lastRequestedFiles: Set<VirtualFile>? = null

    private var treeUpdateJob: Job? = null

    private val uiRefreshTimer = Timer(300) {
        refreshTree()
    }.apply { isRepeats = false }

    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    private var showSelectedOnly = false

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "Loading project..."
        emptyText.appendSecondaryText(
            "Space/Click: Toggle | Enter: Open | Right-Click: Clear/Detach",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            null
        )
    }

    private val suggestionTreeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val suggestionTree = Tree(suggestionTreeModel).apply {
        emptyText.text = "No suggestions at this time."
        isRootVisible = false
        showsRootHandles = true
    }

    private val requestedTreeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val requestedTree = Tree(requestedTreeModel).apply {
        emptyText.text = "No pending requests."
        isRootVisible = true
        showsRootHandles = true
    }

    private val suggestionTreeContainer = JPanel(BorderLayout()).apply {
        border = IdeBorderFactory.createTitledBorder("Suggested Context")
        isVisible = false
    }

    private val requestedTreeContainer = JPanel(BorderLayout()).apply {
        border = IdeBorderFactory.createTitledBorder("Pending AI Requests")
        isVisible = false
    }

    private val promptCompletionProvider = PromptCompletionProvider(
        project = project,
        onCommandSelected = ::applyCommandContext,
        onFileMentionSelected = ::applyFileMentionContext
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
        ContextChangeTracker(this, project, contextState) { vfsStructureChanged ->
            if (vfsStructureChanged) {
                vfsStructureDirty = true
            }
            refreshUi()
        }

        configureTree(tree) { file -> lastDedupedFiles.contains(file) }
        configureTree(suggestionTree) { false }
        configureTree(requestedTree) { false }
        setupExternalFileDropTarget()

        val mainTreeContainer = JPanel(BorderLayout())
        mainTreeContainer.add(setupTopToolbar(), BorderLayout.NORTH)
        mainTreeContainer.add(JBScrollPane(tree), BorderLayout.CENTER)

        suggestionTreeContainer.add(JBScrollPane(suggestionTree), BorderLayout.CENTER)
        requestedTreeContainer.add(JBScrollPane(requestedTree), BorderLayout.CENTER)

        val topTreesSplitter = JBSplitter(true, 0.5f).apply {
            firstComponent = requestedTreeContainer
            secondComponent = suggestionTreeContainer
        }

        val treeSplitter = JBSplitter(true, 0.3f).apply {
            firstComponent = topTreesSplitter
            secondComponent = mainTreeContainer
        }

        val splitPane = JBSplitter(true, 0.7f).apply {
            firstComponent = treeSplitter
            secondComponent = setupBottomPanel()
        }

        content.add(splitPane, BorderLayout.CENTER)

        tabComboBox.addItemListener { event ->
            if (!isUpdatingTabs && event.stateChange == ItemEvent.SELECTED) {
                val selected = event.item as? BrowserTab ?: return@addItemListener
                changeBinding(selected.id, selected.pathname, selected.title)
            }
        }

        server.addTabsListener(this, ::handleTabsChanged)
        updateTabsUI(server.getActiveTabs())
        server.addHandshakeListener(this, ::handleTabHandshake)

        refreshUi()
    }

    private fun configureTree(targetTree: Tree, isCached: (VirtualFile) -> Boolean) {
        targetTree.cellRenderer = ContextTreeCellRenderer(treeManager::getComputedLevel, isCached)
        targetTree.addMouseListener(createTreeMouseListener(targetTree))
        targetTree.addKeyListener(createTreeKeyListener(targetTree))
        TreeSpeedSearch(targetTree)
    }

    private fun setupExternalFileDropTarget() {
        tree.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                return try {
                    @Suppress("UNCHECKED_CAST")
                    val droppedFiles = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        ?: return false
                    if (droppedFiles.isEmpty()) return false

                    contextState.scope.launch {
                        val virtualFiles = readAction {
                            droppedFiles.mapNotNull { ioFile ->
                                LocalFileSystem.getInstance().findFileByIoFile(ioFile)
                            }.also { vfsFiles ->
                                contextState.addExternalFiles(vfsFiles)
                            }
                        }
                        if (virtualFiles.isNotEmpty()) {
                            ApplicationManager.getApplication().invokeLater({
                                vfsStructureDirty = true
                                mainTreeForceRebuild = true
                                refreshUi()
                            }, ModalityState.nonModal())
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun updateTabsUI(tabs: List<BrowserTab>) {
        SwingUtilities.invokeLater {
            isUpdatingTabs = true
            try {
                val previousSelectionId = contextState.activeTabId
                tabComboBox.removeAllItems()
                tabComboBox.addItem(NONE_TAB)

                var matchedItem: BrowserTab? = null
                tabs.forEach { tab ->
                    tabComboBox.addItem(tab)
                    if (tab.id == previousSelectionId) {
                        matchedItem = tab
                    }
                }

                if (matchedItem != null) {
                    tabComboBox.selectedItem = matchedItem
                } else if (contextState.activeTabId != null) {
                    val state = contextState.getState()
                    val disconnectedItem = BrowserTab(
                        contextState.activeTabId!!,
                        contextState.activeTabPathname ?: "",
                        "${state.boundTabTitle} (Disconnected)"
                    )
                    tabComboBox.addItem(disconnectedItem)
                    tabComboBox.selectedItem = disconnectedItem
                } else {
                    tabComboBox.selectedItem = NONE_TAB
                }
                updateSendButtonState()
            } finally {
                isUpdatingTabs = false
            }
        }
    }

    private fun handleTabsChanged(tabs: List<BrowserTab>) {
        val state = contextState.getState()
        if (contextState.activeTabId != null && tabs.none { it.id == contextState.activeTabId }) {
            val match = tabs.find { state.boundTabTitle.isNotEmpty() && it.title == state.boundTabTitle }
            if (match != null) {
                changeBinding(match.id, match.pathname, match.title)
            }
        }
        updateTabsUI(tabs)
    }

    private fun handleTabHandshake(tab: BrowserTab) {
        val state = contextState.getState()

        if (state.boundTabTitle.isNotEmpty() && tab.title == state.boundTabTitle) {
            if (tab.id != contextState.activeTabId || tab.pathname != contextState.activeTabPathname) {
                changeBinding(tab.id, tab.pathname, tab.title)
                updateTabsUI(server.getActiveTabs())
            }
        } else if (tab.id == contextState.activeTabId) {
            if (tab.pathname == contextState.activeTabPathname) {
                changeBinding(tab.id, tab.pathname, tab.title)
                updateTabsUI(server.getActiveTabs())
            } else {
                changeBinding(null, "", "")
                updateTabsUI(server.getActiveTabs())
            }
        }

        val commands = project.service<CommandRegistryService>().getCommands()
        val dtos = commands.map { SyncCommand(it.name, it.mode.name, it.promptBody) }
        val json = Json.encodeToString(dtos)
        server.sendToTab(tab.id, "[COMMANDS]$json")
    }

    private fun changeBinding(newId: String?, newPathname: String, newTitle: String) {
        val oldId = contextState.activeTabId
        if (oldId != null && oldId != newId) {
            server.sendToTab(oldId, "[UNBOUND]")
        }
        if (newId.isNullOrEmpty()) {
            contextState.unbind()
        } else {
            contextState.bindToTab(newId, newPathname, newTitle)
            if (oldId != newId) {
                server.sendToTab(newId, "[BOUND]|${project.name}")
            }
        }
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        if (contextState.activeTabId == null) {
            sendWsButton.text = "Select Tab to Send"
            sendWsButton.isEnabled = false
        } else {
            val isDisconnected = tabComboBox.selectedItem?.toString()?.endsWith("(Disconnected)") == true
            if (isDisconnected) {
                sendWsButton.text = "Disconnected"
                sendWsButton.isEnabled = false
            } else {
                sendWsButton.text = "Send to AI Studio"
                sendWsButton.isEnabled = !loadingIcon.isVisible
            }
        }
    }

    fun handleReadFileToolCall(paths: List<String>, reason: String) {
        contextState.scope.launch {
            val requestedFiles = readAction {
                val found = mutableSetOf<VirtualFile>()

                fun collectFiles(vf: VirtualFile) {
                    ProgressManager.checkCanceled()
                    if (!vf.isValid || FileFilterUtil.isIgnored(project, vf)) return
                    if (vf.isDirectory) {
                        vf.children.forEach(::collectFiles)
                    } else {
                        found.add(vf)
                    }
                }

                for (path in paths) {
                    ProgressManager.checkCanceled()
                    val file = contextState.resolveFile(path)
                    if (file != null && file.exists()) {
                        collectFiles(file)
                    }
                }
                found
            }

            ApplicationManager.getApplication().invokeLater({
                if (requestedFiles.isNotEmpty()) {
                    pendingAiRequests = requestedFiles
                    promptArea.text = if (reason.isNotBlank()) {
                        "AI requested files to: $reason\n\n"
                    } else {
                        ""
                    }
                    refreshUi()
                } else {
                    Messages.showWarningDialog(
                        "The AI requested files, but none were found in the project.\n\nReason: $reason\nPaths: ${paths.joinToString()}",
                        "Tool Call: read_file"
                    )
                }
            }, ModalityState.nonModal())
        }
    }

    fun applyCommandContext(command: SlashCommand) {
        ReadAction.nonBlocking<Unit> {
            if (command.contextAction == CommandContextAction.REPLACE) {
                contextState.clearFileStates()
            }

            val projectDir = project.guessProjectDir() ?: return@nonBlocking
            for (path in command.includeFiles) {
                val file = projectDir.findFileByRelativePath(path)
                if (file != null && file.exists()) {
                    contextState.applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = true)
                }
            }
        }
            .finishOnUiThread(ModalityState.nonModal()) {
                refreshUi()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun applyFileMentionContext(file: VirtualFile) {
        ReadAction.nonBlocking<Unit> {
            if (file.isValid) {
                contextState.applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = true)
            }
        }
            .finishOnUiThread(ModalityState.nonModal()) {
                refreshUi()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun setupTopToolbar(): JPanel {
        val topToolbar = JPanel(BorderLayout(5, 0)).apply { border = JBUI.Borders.empty(5) }

        val actionGroup = DefaultActionGroup().apply {
            add(object :
                AnAction("Add Active File", "Add currently opened editor file to context", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
                    if (file != null) {
                        ReadAction.nonBlocking<Unit> {
                            contextState.addExternalFiles(listOf(file))
                        }.finishOnUiThread(ModalityState.nonModal()) {
                            vfsStructureDirty = true
                            mainTreeForceRebuild = true
                            refreshUi()
                        }.submit(AppExecutorUtil.getAppExecutorService())
                    } else {
                        Messages.showInfoMessage("No active editor found.", "Add Active File")
                    }
                }
            })
            add(object : AnAction(
                "Attach External Files or Folders...",
                "Pick files or directories outside the project to include in context",
                AllIcons.General.OpenDisk
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val descriptor = FileChooserDescriptorFactory.createAllButJarContentsDescriptor().apply {
                        title = "Attach External Files or Folders"
                        description = "Select files or folders to include in Context Bridge"
                    }
                    FileChooser.chooseFiles(descriptor, project, null) { files ->
                        if (files.isEmpty()) return@chooseFiles
                        ReadAction.nonBlocking<Unit> {
                            contextState.addExternalFiles(files)
                        }.finishOnUiThread(ModalityState.nonModal()) {
                            vfsStructureDirty = true
                            mainTreeForceRebuild = true
                            refreshUi()
                        }.submit(AppExecutorUtil.getAppExecutorService())
                    }
                }
            })
            addSeparator()
            add(object : ToggleAction(
                "Show Selected Context Only",
                "Filter to show only selected files",
                AllIcons.General.Filter
            ) {
                override fun isSelected(e: AnActionEvent): Boolean = showSelectedOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showSelectedOnly = state
                    mainTreeForceRebuild = true
                    refreshUi()
                }
            })
            addSeparator()
            add(object : AnAction("Run Web UI Diagnostics", "Test stability of background DOM interactions", AllIcons.Actions.StartDebugger) {
                override fun actionPerformed(e: AnActionEvent) {
                    val tabId = contextState.activeTabId
                    if (tabId != null) {
                        DiagnosticDialog(project, tabId).show()
                    } else {
                        Messages.showWarningDialog("No active AI Studio tab bound. Please select a tab first.", "Diagnostics Failed")
                    }
                }
            })
        }

        val nativeToolbar =
            ActionManager.getInstance().createActionToolbar("ContextComposerTopToolbar", actionGroup, true).apply {
                targetComponent = tree
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

                val commandRegistry = project.service<CommandRegistryService>()
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
                    isConfigLoaded = false
                    vfsStructureDirty = true
                    mainTreeForceRebuild = true
                    promptArea.text = ""
                    historyIndex = -1
                    draftPrompt = ""
                    pendingAiRequests = emptySet()
                    refreshUi()
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
                                vfsStructureDirty = true
                                mainTreeForceRebuild = true
                                refreshUi()
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
            val selectedTab = tabComboBox.selectedItem as? BrowserTab ?: return@addActionListener
            if (selectedTab.id.isNotEmpty()) {
                executePayloadAction(isWsSend = true, selectedTab = selectedTab)
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

    private fun executePayloadAction(isWsSend: Boolean, selectedTab: BrowserTab? = null) {
        val promptText = promptArea.text
        val intent = if (askRadio.isSelected) IntentMode.ASK else IntentMode.EDIT
        historyIndex = -1
        draftPrompt = ""

        // Clear pending requests upon successful transmission
        pendingAiRequests = emptySet()

        setGeneratingState(true)

        contextState.scope.launch {
            try {
                val result = PayloadGenerator.generatePayload(project, contextState, promptText, intent)
                ApplicationManager.getApplication().invokeLater({
                    val payloadObj = result.payload
                    contextState.addTurn(result.turn)
                    refreshUi()

                    if (isWsSend && selectedTab != null) {
                        val jsonString = Json.encodeToString(payloadObj)
                        server.sendToTab(selectedTab.id, jsonString)

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

    private fun applyNodeStateAsync(targetTree: Tree, action: () -> Boolean) {
        contextState.scope.launch {
            val structureChanged = readAction {
                action()
            }
            ApplicationManager.getApplication().invokeLater({
                if (structureChanged) {
                    vfsStructureDirty = true
                    mainTreeForceRebuild = true
                }
                targetTree.repaint()
                refreshUi()
            }, ModalityState.nonModal())
        }
    }

    private fun createTreeMouseListener(targetTree: Tree): MouseAdapter {
        return object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = targetTree.getPathForLocation(e.x, e.y) ?: return
                val bounds = targetTree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                val nodeData = node.userObject as? NodeData ?: return
                val file = nodeData.file

                if (SwingUtilities.isRightMouseButton(e)) {
                    applyNodeStateAsync(targetTree) {
                        treeManager.applyStateToNode(node, ContextLevel.NONE)
                    }
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 28) {
                            applyNodeStateAsync(targetTree) {
                                val nextLevel = treeManager.getNextToggleLevel(node, file)
                                treeManager.applyStateToNode(node, nextLevel)
                            }
                            e.consume()
                        }
                    } else if (e.clickCount == 2) {
                        if (file != null && !nodeData.isDirectory) {
                            OpenFileDescriptor(project, file).navigate(true)
                            e.consume()
                        }
                    }
                }
            }
        }
    }

    private fun createTreeKeyListener(targetTree: Tree): KeyAdapter {
        return object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val paths = targetTree.selectionPaths ?: return
                val keyCode = e.keyCode

                if (keyCode == KeyEvent.VK_ENTER) {
                    for (path in paths) {
                        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                        val nodeData = node.userObject as? NodeData ?: continue
                        val file = nodeData.file
                        if (file != null && !nodeData.isDirectory) {
                            OpenFileDescriptor(project, file).navigate(true)
                        }
                    }
                    return
                }

                if (keyCode == KeyEvent.VK_SPACE ||
                    keyCode == KeyEvent.VK_S ||
                    keyCode == KeyEvent.VK_F ||
                    keyCode == KeyEvent.VK_BACK_SPACE ||
                    keyCode == KeyEvent.VK_DELETE
                ) {
                    applyNodeStateAsync(targetTree) {
                        var structureChanged = false
                        for (path in paths) {
                            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                            val nodeData = node.userObject as? NodeData ?: continue
                            val file = nodeData.file

                            val changed = when (keyCode) {
                                KeyEvent.VK_SPACE -> {
                                    val nextLevel = treeManager.getNextToggleLevel(node, file)
                                    treeManager.applyStateToNode(node, nextLevel)
                                }
                                KeyEvent.VK_S -> treeManager.applyStateToNode(node, ContextLevel.SKELETON)
                                KeyEvent.VK_F -> treeManager.applyStateToNode(node, ContextLevel.COMPLETE)
                                KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                                    treeManager.applyStateToNode(node, ContextLevel.NONE)
                                }
                                else -> false
                            }
                            if (changed) structureChanged = true
                        }
                        structureChanged
                    }
                    e.consume()
                }
            }
        }
    }

    fun refreshUi() {
        promptCompletionProvider.refreshItems()
        uiRefreshTimer.restart()
    }

    private fun updateStagingTree(
        targetTree: Tree,
        container: JPanel,
        rebuilt: Boolean,
        rootNode: DefaultMutableTreeNode?,
        emptyPlaceholder: String
    ) {
        if (!rebuilt) {
            targetTree.repaint()
            return
        }
        if (rootNode != null) {
            container.isVisible = true
            treeManager.updateTreeModel(targetTree, rootNode, expandAllByDefault = true)
        } else {
            targetTree.model = DefaultTreeModel(DefaultMutableTreeNode(emptyPlaceholder))
            container.isVisible = false
        }
        container.revalidate()
        content.revalidate()
        content.repaint()
    }

    private fun refreshTree() {
        treeUpdateJob?.cancel()

        treeUpdateJob = contextState.scope.launch {
            if (!isConfigLoaded) {
                readAction {
                    if (contextState.getLastTurn() == null) {
                        contextState.loadConfig()
                    } else {
                        contextState.restoreFromLastTurn()
                    }
                }
                isConfigLoaded = true
            }

            val suggestions = ContextSuggestionEngine.calculateSuggestions(project, contextState)

            val newDedupedFiles = mutableSetOf<VirtualFile>()
            val projectDir = readAction { project.guessProjectDir() }

            if (projectDir != null) {
                val cache = contextState.getDedupCache()
                for ((file, currentLevel) in contextState.fileStates) {
                    if (currentLevel == ContextLevel.NONE || currentLevel == ContextLevel.MIXED) continue

                    val isDeduped = readAction {
                        ProgressManager.checkCanceled()
                        if (!file.isValid) return@readAction false
                        PayloadGenerator.isFileDeduped(project, projectDir, contextState, file, currentLevel, cache)
                    }

                    if (isDeduped) {
                        newDedupedFiles.add(file)
                    }
                }
            }

            val shouldRefreshVfs = vfsStructureDirty
            val fileMentionItems = if (shouldRefreshVfs) {
                readAction {
                    promptCompletionProvider.collectFileMentionItems(projectDir, contextState.externalRoots)
                }
            } else {
                null
            }

            treeManager.clearCache()

            val shouldRebuildMainTree = shouldRefreshVfs || mainTreeForceRebuild || showSelectedOnly
            val mainRootNode = if (shouldRebuildMainTree) {
                readAction {
                    treeManager.buildWorkspaceTree(projectDir, showSelectedOnly)
                }
            } else {
                null
            }

            val shouldRebuildSuggestionTree = shouldRefreshVfs || suggestions != lastSuggestions
            val suggestionRootNode = if (shouldRebuildSuggestionTree) {
                readAction {
                    if (projectDir != null && suggestions.isNotEmpty()) {
                        treeManager.buildFileTree(projectDir, false, allowedLeaves = suggestions, isRoot = true)
                            ?.takeIf { it.childCount > 0 }
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            val currentRequests = pendingAiRequests
            val shouldRebuildRequestedTree = shouldRefreshVfs || currentRequests != lastRequestedFiles
            val requestedRootNode = if (shouldRebuildRequestedTree) {
                readAction {
                    treeManager.buildRequestedFilesTree(projectDir, currentRequests)
                }
            } else {
                null
            }

            ApplicationManager.getApplication().invokeLater({
                if (shouldRefreshVfs) {
                    vfsStructureDirty = false
                }
                if (shouldRebuildMainTree) {
                    mainTreeForceRebuild = false
                }

                lastDedupedFiles = newDedupedFiles
                if (fileMentionItems != null) {
                    promptCompletionProvider.updateFileMentionItems(fileMentionItems)
                }

                if (mainRootNode != null) {
                    treeManager.updateTreeModel(tree, mainRootNode, expandAllByDefault = false)
                } else {
                    tree.repaint()
                }

                if (shouldRebuildSuggestionTree) {
                    lastSuggestions = suggestions
                }
                updateStagingTree(
                    suggestionTree,
                    suggestionTreeContainer,
                    shouldRebuildSuggestionTree,
                    suggestionRootNode,
                    "No Suggestions"
                )

                if (shouldRebuildRequestedTree) {
                    lastRequestedFiles = currentRequests
                }
                updateStagingTree(
                    requestedTree,
                    requestedTreeContainer,
                    shouldRebuildRequestedTree,
                    requestedRootNode,
                    "No pending requests."
                )

            }, ModalityState.nonModal())
        }
    }

    override fun dispose() {
        treeUpdateJob?.cancel()
    }
}