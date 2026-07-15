package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.context.AiPayload
import com.github.bumblebee202111.intellijcontextbridge.context.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.context.PsiSkeletonExtractor
import com.github.bumblebee202111.intellijcontextbridge.server.BrowserTab
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
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
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
import org.jetbrains.concurrency.CancellablePromise
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
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

data class BrowserTabItem(val id: String, val title: String) {
    override fun toString() = title
}

class ContextComposerPanel(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

    private var historyIndex = -1
    private var draftPrompt = ""
    @Volatile
    private var lastDedupedFiles = emptySet<VirtualFile>()
    private var isConfigLoaded = false

    private data class AggregatedState(
        val allMaxed: Boolean,
        val allSkeleton: Boolean,
        val allNone: Boolean,
        val hasLeaves: Boolean
    ) {
        val level: ContextLevel get() {
            if (!hasLeaves) return ContextLevel.NONE
            if (allNone) return ContextLevel.NONE
            if (allSkeleton) return ContextLevel.SKELETON
            if (allMaxed) return ContextLevel.FULL
            return ContextLevel.MIXED
        }
    }

    private val nodeStateCache = ConcurrentHashMap<DefaultMutableTreeNode, AggregatedState>()
    private var treeUpdateJob: CancellablePromise<*>? = null

    private val vfsRefreshTimer = Timer(500) {
        refreshUi()
    }.apply { isRepeats = false }

    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    private var showSelectedOnly = false
    private var searchQuery = ""
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val tree = Tree(treeModel).apply {
        emptyText.text = "Loading project..."
        emptyText.appendSecondaryText("Space/Click: Toggle | Enter: Open | Right-Click: Clear", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }

    val content: JPanel = JPanel(BorderLayout())

    init {
        // 1. Listen for disk saves and external changes
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                var needsRefresh = false
                val projectDir = project.guessProjectDir() ?: return

                for (event in events) {
                    val file = event.file
                    if (file != null && VfsUtilCore.isAncestor(projectDir, file, false)) {
                        needsRefresh = true
                        break
                    }
                }

                if (needsRefresh) {
                    contextState.fileStates.keys.removeIf { !it.isValid }
                    vfsRefreshTimer.restart()
                }
            }
        })

        // 2. Listen for live typing in the editor (unsaved changes)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document)
                // Only trigger refresh if we are actually tracking this file in the context
                if (file != null && contextState.fileStates.containsKey(file)) {
                    vfsRefreshTimer.restart()
                }
            }
        }, project)

        tree.cellRenderer = ContextTreeCellRenderer(::getComputedLevel) { file -> lastDedupedFiles.contains(file) }

        fun getNextToggleLevel(node: DefaultMutableTreeNode, file: VirtualFile): ContextLevel {
            val currentLevel = getComputedLevel(node)
            val maxLevel = if (file.isDirectory && file.children.isNotEmpty()) {
                ContextLevel.FULL
            } else {
                ContextCapabilityUtil.getMaxLevel(file)
            }
            return ContextCapabilityUtil.getNextLevel(currentLevel, maxLevel)
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val file = (node.userObject as? NodeData)?.file ?: return

                if (SwingUtilities.isRightMouseButton(e)) {
                    applyStateToNode(node, ContextLevel.NONE)
                    if (showSelectedOnly) refreshUi() else {
                        tree.repaint()
                        autoCollapse(tree, node, path)
                    }
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 28) {
                            val nextLevel = getNextToggleLevel(node, file)
                            applyStateToNode(node, nextLevel)
                            refreshUi()
                            e.consume()
                        }
                    } else if (e.clickCount == 2) {
                        if (!file.isDirectory) {
                            OpenFileDescriptor(project, file).navigate(true)
                            e.consume()
                        }
                    }
                }
            }
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val paths = tree.selectionPaths ?: return
                var stateChanged = false

                for (path in paths) {
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                    val file = (node.userObject as? NodeData)?.file ?: continue

                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> {
                            if (!file.isDirectory) OpenFileDescriptor(project, file).navigate(true)
                        }
                        KeyEvent.VK_SPACE -> {
                            val nextLevel = getNextToggleLevel(node, file)
                            applyStateToNode(node, nextLevel)
                            stateChanged = true
                        }
                        KeyEvent.VK_S -> { applyStateToNode(node, ContextLevel.SKELETON); stateChanged = true }
                        KeyEvent.VK_F -> { applyStateToNode(node, ContextLevel.FULL); stateChanged = true }
                        KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> {
                            applyStateToNode(node, ContextLevel.NONE)
                            autoCollapse(tree, node, path)
                            stateChanged = true
                        }
                    }
                }
                if (stateChanged) {
                    refreshUi()
                    e.consume()
                }
            }
        })

        val treeContainer = JPanel(BorderLayout())
        val topToolbar = JPanel(BorderLayout(5, 0)).apply { border = JBUI.Borders.empty(5) }

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Add Active File", "Add currently opened editor file to context as Full", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
                    if (file != null) {
                        ReadAction.nonBlocking<Unit> {
                            contextState.applyStateRecursively(file, ContextLevel.FULL, checkIgnore = true)
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

        treeContainer.add(topToolbar, BorderLayout.NORTH)
        treeContainer.add(JBScrollPane(tree), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }

        val promptArea = EditorTextField(project, FileTypes.PLAIN_TEXT).apply {
            setOneLineMode(false)
            setPlaceholder("Type your prompt here...")
            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                editor.settings.additionalLinesCount = 0
            }
        }

        promptArea.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (historyIndex == -1) {
                    draftPrompt = promptArea.text
                }
            }
        })

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

        val editRadio = JBRadioButton("⚡ Edit", true)
        val askRadio = JBRadioButton("💬 Ask")
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

        val copyButton = JButton("Copy", AllIcons.Actions.Copy)
        val tabComboBox = ComboBox<BrowserTabItem>().apply {
            toolTipText = "Select the target AI Studio tab"
            preferredSize = Dimension(180, preferredSize.height)
        }
        val sendWsButton = JButton("Waiting...").apply {
            isEnabled = false
            toolTipText = "Send context directly to the selected AI Studio tab"
            putClientProperty("JButton.buttonType", "defaultButton")
        }

        val loadingIcon = AsyncProcessIcon("PayloadGenerator").apply { isVisible = false }

        fun setGeneratingState(isGenerating: Boolean) {
            loadingIcon.isVisible = isGenerating
            copyButton.isEnabled = !isGenerating
            sendWsButton.isEnabled = !isGenerating && tabComboBox.itemCount > 0
        }

        copyButton.addActionListener {
            val promptText = promptArea.text
            val intent = if (askRadio.isSelected) IntentMode.ASK else IntentMode.EDIT
            historyIndex = -1
            draftPrompt = ""

            setGeneratingState(true)

            ReadAction.nonBlocking<AiPayload> {
                PayloadGenerator.generatePayload(project, contextState, promptText, intent)
            }
            .finishOnUiThread(ModalityState.nonModal()) { payloadObj ->
                payloadObj.turn?.let { contextState.addTurn(it) }
                refreshUi()
                CopyPasteManager.getInstance().setContents(StringSelection(payloadObj.text))

                if (payloadObj.attachments.isNotEmpty()) {
                    Messages.showWarningDialog(
                        "You copied ${payloadObj.attachments.size} media file(s). They cannot be copied to the clipboard.",
                        "Media Files Skipped"
                    )
                }

                promptArea.text = ""
                setGeneratingState(false)

                val originalText = copyButton.text
                copyButton.text = "Copied!"
                Timer(1500) { copyButton.text = originalText }.apply { isRepeats = false }.start()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        }

        sendWsButton.addActionListener {
            val selectedTab = tabComboBox.selectedItem as? BrowserTabItem ?: return@addActionListener
            val promptText = promptArea.text
            val intent = if (askRadio.isSelected) IntentMode.ASK else IntentMode.EDIT
            historyIndex = -1
            draftPrompt = ""

            setGeneratingState(true)

            ReadAction.nonBlocking<AiPayload> {
                PayloadGenerator.generatePayload(project, contextState, promptText, intent)
            }
            .finishOnUiThread(ModalityState.nonModal()) { payloadObj ->
                payloadObj.turn?.let { contextState.addTurn(it) }
                refreshUi()

                val jsonString = Json.encodeToString(payloadObj)
                server.sendToTab(selectedTab.id, jsonString)

                promptArea.text = ""
                setGeneratingState(false)

                val originalText = sendWsButton.text
                sendWsButton.text = "Sent!"
                Timer(1500) { sendWsButton.text = originalText }.apply { isRepeats = false }.start()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        }

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

        val splitPane = JBSplitter(true, 0.7f)
        splitPane.firstComponent = treeContainer
        splitPane.secondComponent = bottomPanel

        content.add(splitPane, BorderLayout.CENTER)

        refreshUi()
    }

    fun refreshUi() {
        refreshTree()
    }

    private fun refreshTree() {
        treeUpdateJob?.cancel()

        treeUpdateJob = ReadAction.nonBlocking<Pair<DefaultTreeModel, Set<VirtualFile>>> {
            if (!isConfigLoaded) {
                contextState.loadConfig()
                isConfigLoaded = true
            }

            // 1. Re-calculate deduplication hashes in the background
            val newDedupedFiles = mutableSetOf<VirtualFile>()
            val projectDir = project.guessProjectDir()
            if (projectDir != null) {
                val cache = contextState.getDedupCache()
                for ((file, currentLevel) in contextState.fileStates) {
                    if (currentLevel == ContextLevel.NONE || currentLevel == ContextLevel.MIXED) continue

                    val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
                    val cachedRecord = cache[relativePath]

                    if (cachedRecord != null && cachedRecord.level == currentLevel) {
                        try {
                            val extension = file.extension?.lowercase() ?: ""
                            val isTextFile = ContextCapabilityUtil.textExtensions.contains(extension) || !file.fileType.isBinary

                            val currentHash = if (!isTextFile) {
                                if (currentLevel == ContextLevel.FULL) contextState.calculateHash("${file.modificationStamp}_${file.length}") else "OMITTED_BINARY_SKELETON"
                            } else {
                                val extractedText = if (currentLevel == ContextLevel.FULL) {
                                    // Use Document to capture unsaved live typing changes
                                    val doc = FileDocumentManager.getInstance().getCachedDocument(file)
                                    doc?.text ?: VfsUtilCore.loadText(file)
                                } else {
                                    PsiSkeletonExtractor.extract(project, file) ?: "OMITTED_NON_CODE_SKELETON"
                                }
                                contextState.calculateHash(extractedText)
                            }

                            if (currentHash == cachedRecord.hash) {
                                newDedupedFiles.add(file)
                            }
                        } catch (e: Exception) {
                            // Safely ignore file read errors during background hash checks
                        }
                    }
                }
            }

            // 2. Build the visual tree
            nodeStateCache.clear()
            val rootNode = if (projectDir != null) {
                buildFileTree(projectDir, showSelectedOnly, searchQuery) ?: DefaultMutableTreeNode(NodeData(projectDir, "No Project Root"))
            } else {
                DefaultMutableTreeNode("No Project Root")
            }
            Pair(DefaultTreeModel(rootNode), newDedupedFiles)
        }
        .expireWith(project)
        .finishOnUiThread(ModalityState.nonModal()) { (newModel, newDedupedFiles) ->
            lastDedupedFiles = newDedupedFiles
            tree.model = newModel
            if (searchQuery.isNotBlank()) {
                TreeUtil.expandAll(tree)
            } else {
                expandExplicitNodes(tree, newModel.root as DefaultMutableTreeNode)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun getAggregatedState(node: DefaultMutableTreeNode): AggregatedState {
        val file = (node.userObject as? NodeData)?.file ?: return AggregatedState(
            allMaxed = true,
            allSkeleton = true,
            allNone = true,
            hasLeaves = false
        )
        
        if (!file.isDirectory || file.children.isEmpty()) {
            val level = contextState.getLevel(file)
            val maxLevel = if (file.isDirectory) ContextLevel.SKELETON else ContextCapabilityUtil.getMaxLevel(file)
            return AggregatedState(
                allMaxed = (level == maxLevel),
                allSkeleton = (level == ContextLevel.SKELETON),
                allNone = (level == ContextLevel.NONE),
                hasLeaves = true
            )
        }

        nodeStateCache[node]?.let { return it }

        var allMaxed = true
        var allSkeleton = true
        var allNone = true
        var hasLeaves = false

        val enumeration = node.children()
        while (enumeration.hasMoreElements()) {
            val child = enumeration.nextElement() as DefaultMutableTreeNode
            val childState = getAggregatedState(child)

            if (childState.hasLeaves) {
                hasLeaves = true
                if (!childState.allMaxed) allMaxed = false
                if (!childState.allSkeleton) allSkeleton = false
                if (!childState.allNone) allNone = false
            }
        }

        val result = AggregatedState(allMaxed, allSkeleton, allNone, hasLeaves)
        nodeStateCache[node] = result
        return result
    }

    private fun getComputedLevel(node: DefaultMutableTreeNode): ContextLevel {
        return getAggregatedState(node).level
    }

    private fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel) {
        val file = (node.userObject as? NodeData)?.file ?: return
        if (!file.isDirectory) {
            contextState.applyStateRecursively(file, level, checkIgnore = false)
        } else {
            val enumeration = node.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val descendant = enumeration.nextElement() as DefaultMutableTreeNode
                val descFile = (descendant.userObject as? NodeData)?.file ?: continue
                if (!descFile.isDirectory || descFile.children.isEmpty()) {
                    contextState.applyStateRecursively(descFile, level, checkIgnore = false)
                }
            }
        }
        nodeStateCache.clear()
    }

    @Suppress("UnsafeVfsRecursion")
    private fun buildFileTree(dir: VirtualFile, showSelectedOnly: Boolean, searchQuery: String, prefix: String = ""): DefaultMutableTreeNode? {
        if (FileFilterUtil.isIgnored(project, dir)) return null

        val children = dir.children.filter { !FileFilterUtil.isIgnored(project, it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        if (dir.isDirectory && children.size == 1 && children[0].isDirectory) {
            val newPrefix = if (prefix.isEmpty()) dir.name else "$prefix.${dir.name}"
            return buildFileTree(children[0], showSelectedOnly, searchQuery, newPrefix)
        }

        val displayName = if (prefix.isEmpty()) dir.name else "$prefix.${dir.name}"
        val node = DefaultMutableTreeNode(NodeData(dir, displayName))

        var hasValidChildren = false
        val dirMatchesSearch = searchQuery.isBlank() || displayName.contains(searchQuery, ignoreCase = true)

        for (child in children) {
            if (child.isDirectory) {
                val childNode = buildFileTree(child, showSelectedOnly, searchQuery, "")
                if (childNode != null) {
                    node.add(childNode)
                    hasValidChildren = true
                }
            } else {
                val level = contextState.getLevel(child)
                val isSelected = level != ContextLevel.NONE

                val passesSelection = !showSelectedOnly || isSelected
                val passesSearch = searchQuery.isBlank() || dirMatchesSearch || child.name.contains(searchQuery, ignoreCase = true)

                if (passesSelection && passesSearch) {
                    node.add(DefaultMutableTreeNode(NodeData(child, child.name)))
                    hasValidChildren = true
                }
            }
        }

        val level = getComputedLevel(node)
        val isSelected = level != ContextLevel.NONE
        val passesSelection = !showSelectedOnly || isSelected

        if (!hasValidChildren) {
            if (!passesSelection) return null
            if (searchQuery.isNotBlank() && !dirMatchesSearch) return null
            if (children.isNotEmpty()) return null
        }

        return node
    }

    private fun autoCollapse(tree: Tree, node: DefaultMutableTreeNode, path: TreePath) {
        var currentPath = path.parentPath
        var currentNode = node.parent as? DefaultMutableTreeNode

        while (currentPath != null && currentNode != null) {
            if (getComputedLevel(currentNode) == ContextLevel.NONE) {
                tree.collapsePath(currentPath)
            } else {
                break
            }
            currentPath = currentPath.parentPath
            currentNode = currentNode.parent as? DefaultMutableTreeNode
        }
    }

    private fun expandExplicitNodes(tree: Tree, node: DefaultMutableTreeNode) {
        val level = getComputedLevel(node)

        if (level == ContextLevel.MIXED) {
            tree.expandPath(TreePath(node.path))
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode
                if (child != null) expandExplicitNodes(tree, child)
            }
        }
    }
}