package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.context.AiPayload
import com.github.bumblebee202111.intellijcontextbridge.context.ContextSuggestionEngine
import com.github.bumblebee202111.intellijcontextbridge.context.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.server.BrowserTab
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.services.ContextCoroutineScopeService
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.icons.AllIcons
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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

data class BrowserTabItem(val id: String, val title: String) {
    override fun toString() = title
}

class ContextComposerPanel(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
    private val treeManager = ContextTreeManager(project, contextState)
    private val coroutineService = project.service<ContextCoroutineScopeService>()

    private var historyIndex = -1
    private var draftPrompt = ""
    @Volatile
    private var lastDedupedFiles = emptySet<VirtualFile>()
    private var isConfigLoaded = false

    // Holds files requested by the AI until the next payload is sent
    private var pendingAiRequests = emptySet<VirtualFile>()

    private var treeUpdateJob: Job? = null

    // Unified Debouncer for all UI updates (300ms)
    private val uiRefreshTimer = Timer(300) {
        val currentPromptText = promptArea.text
        refreshTree(currentPromptText)
    }.apply { isRepeats = false }

    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    private var showSelectedOnly = false
    private var searchQuery = ""

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val tree = Tree(treeModel).apply {
        emptyText.text = "Loading project..."
        emptyText.appendSecondaryText("Space/Click: Toggle | Enter: Open | Right-Click: Clear", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
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

    private val promptArea = EditorTextField(project, FileTypes.PLAIN_TEXT).apply {
        setOneLineMode(false)
        setPlaceholder("Type your prompt here...")
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            editor.settings.additionalLinesCount = 0
        }
    }

    private val editRadio = JBRadioButton("⚡ Edit", true)
    private val askRadio = JBRadioButton("💬 Ask")
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy)
    private val tabComboBox = ComboBox<BrowserTabItem>().apply {
        toolTipText = "Select the target AI Studio tab"
        preferredSize = Dimension(180, preferredSize.height)
    }
    private val sendWsButton = JButton("Waiting...").apply {
        isEnabled = false
        toolTipText = "Send context directly to the selected AI Studio tab"
        putClientProperty("JButton.buttonType", "defaultButton")
    }
    private val loadingIcon = AsyncProcessIcon("PayloadGenerator").apply { isVisible = false }

    val content: JPanel = JPanel(BorderLayout())

    init {
        // Register decoupled IDE event listeners to trigger the debouncer
        ContextChangeTracker(project, contextState) { refreshUi() }

        tree.cellRenderer = ContextTreeCellRenderer(treeManager::getComputedLevel) { file -> lastDedupedFiles.contains(file) }
        suggestionTree.cellRenderer = ContextTreeCellRenderer(treeManager::getComputedLevel) { false }
        requestedTree.cellRenderer = ContextTreeCellRenderer(treeManager::getComputedLevel) { false }

        tree.addMouseListener(createTreeMouseListener(tree))
        tree.addKeyListener(createTreeKeyListener(tree))

        suggestionTree.addMouseListener(createTreeMouseListener(suggestionTree))
        suggestionTree.addKeyListener(createTreeKeyListener(suggestionTree))

        requestedTree.addMouseListener(createTreeMouseListener(requestedTree))
        requestedTree.addKeyListener(createTreeKeyListener(requestedTree))

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

        // Setup WebSocket UI hook
        val updateTabsUI = { tabs: List<BrowserTab> ->
            SwingUtilities.invokeLater {
                val currentSelection = tabComboBox.selectedItem as? BrowserTabItem
                tabComboBox.removeAllItems()
                tabs.forEach { tabComboBox.addItem(BrowserTabItem(it.id, it.title)) }

                if (currentSelection != null && tabs.any { it.id == currentSelection.id }) {
                    tabComboBox.selectedItem = tabs.first { it.id == currentSelection.id }.let { BrowserTabItem(it.id, it.title) }
                } else if (tabComboBox.itemCount > 0) {
                    tabComboBox.selectedIndex = 0
                }

                if (tabComboBox.itemCount > 0) {
                    sendWsButton.text = "Send to AI Studio"
                    if (!loadingIcon.isVisible) sendWsButton.isEnabled = true
                } else {
                    sendWsButton.text = "Waiting..."
                    sendWsButton.isEnabled = false
                }
            }
        }

        server.onTabsChanged = updateTabsUI
        updateTabsUI(server.getActiveTabs())

        refreshUi()
    }

    fun handleReadFileToolCall(paths: List<String>, reason: String) {
        val projectDir = project.guessProjectDir() ?: return
        val requestedFiles = mutableSetOf<VirtualFile>()

        paths.forEach { path ->
            val file = projectDir.findFileByRelativePath(path)
            if (file != null && file.exists() && !file.isDirectory) {
                requestedFiles.add(file)
            }
        }

        if (requestedFiles.isNotEmpty()) {
            pendingAiRequests = requestedFiles
            promptArea.text = "AI requested files to: $reason\n\n"
            refreshUi()
        } else {
            Messages.showWarningDialog(
                "The AI requested files, but none were found in the project.\n\nReason: $reason\nPaths: ${paths.joinToString()}",
                "Tool Call: read_file"
            )
        }
    }

    private fun setupTopToolbar(): JPanel {
        val topToolbar = JPanel(BorderLayout(5, 0)).apply { border = JBUI.Borders.empty(5) }

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Add Active File", "Add currently opened editor file to context", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
                    if (file != null) {
                        ReadAction.nonBlocking<Unit> {
                            contextState.applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = true)
                        }.finishOnUiThread(ModalityState.nonModal()) {
                            refreshUi()
                        }.submit(AppExecutorUtil.getAppExecutorService())
                    } else {
                        Messages.showInfoMessage("No active editor found.", "Add Active File")
                    }
                }
            })
            addSeparator()
            add(object : ToggleAction("Show Selected Context Only", "Filter to show only selected files", AllIcons.General.Filter) {
                override fun isSelected(e: AnActionEvent): Boolean = showSelectedOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showSelectedOnly = state
                    refreshUi()
                }
            })
        }

        val nativeToolbar = ActionManager.getInstance().createActionToolbar("ContextComposerTopToolbar", actionGroup, true).apply {
            targetComponent = tree
        }

        var searchTimer: Timer? = null
        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Search files..."
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: SwingDocumentEvent) {
                    searchTimer?.stop()
                    searchTimer = Timer(300) {
                        searchQuery = text.trim()
                        refreshUi()
                    }.apply { isRepeats = false; start() }
                }
            })
        }

        topToolbar.add(nativeToolbar.component, BorderLayout.WEST)
        topToolbar.add(searchField, BorderLayout.CENTER)
        return topToolbar
    }

    private fun setupBottomPanel(): JPanel {
        val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }

        promptArea.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (historyIndex == -1) {
                    draftPrompt = promptArea.text
                }
                refreshUi()
            }
        })

        setupPromptHistoryShortcuts()

        ButtonGroup().apply {
            add(editRadio)
            add(askRadio)
        }
        val intentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            border = JBUI.Borders.emptyBottom(5)
            add(JLabel("Mode:"))
            add(editRadio)
            add(askRadio)
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
                        refreshUi()
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
        val bottomToolbar = ActionManager.getInstance().createActionToolbar("ContextComposerBottomToolbar", bottomActionGroup, true).apply {
            targetComponent = promptArea
        }

        copyButton.addActionListener { executePayloadAction(isWsSend = false) }
        sendWsButton.addActionListener {
            val selectedTab = tabComboBox.selectedItem as? BrowserTabItem ?: return@addActionListener
            executePayloadAction(isWsSend = true, selectedTab = selectedTab)
        }

        val actionButtonPanel = JPanel(BorderLayout(5, 0)).apply {
            border = JBUI.Borders.emptyTop(5)
            add(bottomToolbar.component, BorderLayout.WEST)

            val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(tabComboBox)
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
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK)), promptArea)

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
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK)), promptArea)
    }

    private fun executePayloadAction(isWsSend: Boolean, selectedTab: BrowserTabItem? = null) {
        val promptText = promptArea.text
        val intent = if (askRadio.isSelected) IntentMode.ASK else IntentMode.EDIT
        historyIndex = -1
        draftPrompt = ""

        // Clear pending requests upon successful transmission
        pendingAiRequests = emptySet()

        setGeneratingState(true)

        ReadAction.nonBlocking<AiPayload> {
            PayloadGenerator.generatePayload(project, contextState, promptText, intent)
        }
        .finishOnUiThread(ModalityState.nonModal()) { payloadObj ->
            payloadObj.turn?.let { contextState.addTurn(it) }
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
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun setGeneratingState(isGenerating: Boolean) {
        loadingIcon.isVisible = isGenerating
        copyButton.isEnabled = !isGenerating
        sendWsButton.isEnabled = !isGenerating && tabComboBox.itemCount > 0
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
                    treeManager.applyStateToNode(node, ContextLevel.NONE)
                    treeManager.collapseDescendants(targetTree, node, path)

                    // OPTIMISTIC UPDATE: Paint instantly, calculate heavily later
                    targetTree.repaint()
                    refreshUi()
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 28) {
                            val nextLevel = treeManager.getNextToggleLevel(node, file)
                            treeManager.applyStateToNode(node, nextLevel)
                            treeManager.collapseDescendants(targetTree, node, path)

                            // OPTIMISTIC UPDATE: Paint instantly, calculate heavily later
                            targetTree.repaint()
                            refreshUi()
                            e.consume()
                        }
                    } else if (e.clickCount == 2) {
                        if (file != null && !file.isDirectory) {
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
                var stateChanged = false

                for (path in paths) {
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                    val nodeData = node.userObject as? NodeData ?: continue
                    val file = nodeData.file

                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> {
                            if (file != null && !file.isDirectory) OpenFileDescriptor(project, file).navigate(true)
                        }
                        KeyEvent.VK_SPACE -> {
                            val nextLevel = treeManager.getNextToggleLevel(node, file)
                            treeManager.applyStateToNode(node, nextLevel)
                            treeManager.collapseDescendants(targetTree, node, path)
                            stateChanged = true
                        }
                        KeyEvent.VK_S -> {
                            treeManager.applyStateToNode(node, ContextLevel.SKELETON)
                            treeManager.collapseDescendants(targetTree, node, path)
                            stateChanged = true
                        }
                        KeyEvent.VK_F -> {
                            treeManager.applyStateToNode(node, ContextLevel.COMPLETE)
                            treeManager.collapseDescendants(targetTree, node, path)
                            stateChanged = true
                        }
                        KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                            treeManager.applyStateToNode(node, ContextLevel.NONE)
                            treeManager.collapseDescendants(targetTree, node, path)
                            stateChanged = true
                        }
                    }
                }
                if (stateChanged) {
                    // OPTIMISTIC UPDATE: Paint instantly, calculate heavily later
                    targetTree.repaint()
                    refreshUi()
                    e.consume()
                }
            }
        }
    }

    fun refreshUi() {
        uiRefreshTimer.restart()
    }

    private fun refreshTree(currentPromptText: String) {
        treeUpdateJob?.cancel()

        treeUpdateJob = coroutineService.scope.launch {
            if (!isConfigLoaded) {
                readAction { contextState.loadConfig() }
                isConfigLoaded = true
            }

            val suggestions = ContextSuggestionEngine.calculateSuggestions(project, contextState, currentPromptText)

            val newDedupedFiles = mutableSetOf<VirtualFile>()
            val projectDir = project.guessProjectDir()

            readAction {
                if (projectDir != null) {
                    val cache = contextState.getDedupCache()
                    for ((file, currentLevel) in contextState.fileStates) {
                        if (currentLevel == ContextLevel.NONE || currentLevel == ContextLevel.MIXED) continue

                        val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
                        val cachedRecord = cache[relativePath]

                        if (cachedRecord != null && cachedRecord.level == currentLevel) {
                            try {
                                val currentHash = contextState.getFileHash(project, file, currentLevel)
                                if (currentHash == cachedRecord.hash) {
                                    newDedupedFiles.add(file)
                                }
                            } catch (e: Exception) {
                                // Safely ignore file read errors
                            }
                        }
                    }
                }
            }

            treeManager.clearCache()

            val mainRootNode = readAction {
                if (projectDir != null) {
                    treeManager.buildFileTree(projectDir, showSelectedOnly, searchQuery, isRoot = true) ?: DefaultMutableTreeNode(NodeData(projectDir, "No Project Root"))
                } else {
                    DefaultMutableTreeNode("No Project Root")
                }
            }

            val suggestionRootNode = readAction {
                if (projectDir != null && suggestions.isNotEmpty()) {
                    treeManager.buildFileTree(projectDir, false, "", allowedLeaves = suggestions, isRoot = true) ?: DefaultMutableTreeNode("No Suggestions")
                } else {
                    DefaultMutableTreeNode("No Suggestions")
                }
            }

            val requestedRootNode = readAction {
                if (projectDir != null && pendingAiRequests.isNotEmpty()) {
                    val node = treeManager.buildFileTree(projectDir, false, "", allowedLeaves = pendingAiRequests, isRoot = true)
                    if (node != null) {
                        node.userObject = NodeData(null, "[AI Requested Files]")
                        node
                    } else null
                } else null
            }

            // Switch to the EDT for UI updates safely
            ApplicationManager.getApplication().invokeLater({
                lastDedupedFiles = newDedupedFiles

                // CAPTURE: Record which folders the user currently has open across all 3 trees
                val expandedMain = treeManager.getExpandedFilePaths(tree)
                val expandedSugg = treeManager.getExpandedFilePaths(suggestionTree)
                val expandedReq = treeManager.getExpandedFilePaths(requestedTree)

                // SWAP: Replace the models
                tree.model = DefaultTreeModel(mainRootNode)

                // RESTORE: Re-open the folders precisely
                if (searchQuery.isNotBlank()) {
                    TreeUtil.expandAll(tree)
                } else {
                    treeManager.restoreExpandedFilePaths(tree, expandedMain)
                    // If it's the very first load and nothing was expanded, open the root
                    if (expandedMain.isEmpty() && tree.rowCount > 0) {
                        tree.expandRow(0)
                    }
                }

                suggestionTree.model = DefaultTreeModel(suggestionRootNode)
                val hasSuggestions = suggestionRootNode.childCount > 0
                suggestionTreeContainer.isVisible = hasSuggestions
                if (hasSuggestions) {
                    treeManager.restoreExpandedFilePaths(suggestionTree, expandedSugg)
                    if (expandedSugg.isEmpty()) TreeUtil.expandAll(suggestionTree)
                }

                if (requestedRootNode != null) {
                    requestedTree.model = DefaultTreeModel(requestedRootNode)
                    requestedTreeContainer.isVisible = true
                    treeManager.restoreExpandedFilePaths(requestedTree, expandedReq)
                    if (expandedReq.isEmpty()) TreeUtil.expandAll(requestedTree)
                } else {
                    requestedTree.model = DefaultTreeModel(DefaultMutableTreeNode("No pending requests."))
                    requestedTreeContainer.isVisible = false
                }

            }, ModalityState.nonModal())
        }
    }
}