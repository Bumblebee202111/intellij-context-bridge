package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.concurrency.CancellablePromise
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ContextComposerPanel(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

    private var historyIndex = -1
    private var draftPrompt = ""
    private var lastDedupedFiles = emptySet<VirtualFile>()

    private val nodeStateCache = ConcurrentHashMap<DefaultMutableTreeNode, ContextLevel>()
    private var treeUpdateJob: CancellablePromise<*>? = null

    private val dateFormat = SimpleDateFormat("HH:mm:ss")
    private val promptArea = JBTextArea()
    private val undoButton = JButton("Undo Last")

    private var showSelectedOnly = false
    private var searchQuery = ""
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    private val tree = Tree(treeModel).apply {
        toolTipText = "Left-click icon: Toggle. Right-click row: Clear. Double-click: Open."
    }

    val content: JPanel = JPanel(BorderLayout())

    init {
        tree.cellRenderer = ContextTreeCellRenderer(::getComputedLevel) { file -> lastDedupedFiles.contains(file) }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val file = (node.userObject as? NodeData)?.file ?: return

                if (SwingUtilities.isRightMouseButton(e)) {
                    applyStateToNode(node, ContextLevel.NONE)
                    updateDedupedFiles()
                    if (showSelectedOnly) refreshUi() else {
                        tree.repaint()
                        autoCollapse(tree, node, path)
                    }
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 22) {
                            val currentLevel = getComputedLevel(node)
                            
                            val maxLevel = if (file.isDirectory && file.children.isNotEmpty()) {
                                ContextLevel.FULL
                            } else {
                                ContextCapabilityUtil.getMaxLevel(file)
                            }
                            
                            val nextLevel = ContextCapabilityUtil.getNextLevel(currentLevel, maxLevel)
                            
                            applyStateToNode(node, nextLevel)
                            updateDedupedFiles()
                            if (showSelectedOnly) refreshUi() else tree.repaint()
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
                    updateDedupedFiles()
                    if (showSelectedOnly) refreshUi() else tree.repaint()
                    e.consume()
                }
            }
        })

        val treeContainer = JPanel(BorderLayout())
        val topToolbar = JPanel(BorderLayout(5, 0)).apply { border = JBUI.Borders.empty(5, 5) }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            val addActiveFileBtn = JButton("+ Active File").apply {
                toolTipText = "Add currently opened editor file to context as Full"
                addActionListener {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val file = editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
                    if (file != null) {
                        contextState.applyStateRecursively(file, ContextLevel.FULL)
                        refreshUi()
                    } else {
                        Messages.showInfoMessage("No active editor found.", "Add Active File")
                    }
                }
            }

            val filterToggle = JToggleButton(AllIcons.General.Filter).apply {
                toolTipText = "Show Selected Context Only"
                addActionListener { showSelectedOnly = isSelected; refreshUi() }
            }

            add(addActiveFileBtn)
            add(Box.createHorizontalStrut(5))
            add(filterToggle)
        }

        var searchTimer: Timer? = null
        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Search files..."
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    searchTimer?.stop()
                    searchTimer = Timer(300) {
                        searchQuery = text.trim()
                        refreshUi()
                    }.apply { isRepeats = false; start() }
                }
            })
        }

        topToolbar.add(buttonPanel, BorderLayout.WEST)
        topToolbar.add(searchField, BorderLayout.CENTER)

        treeContainer.add(topToolbar, BorderLayout.NORTH)
        treeContainer.add(JBScrollPane(tree), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }
        promptArea.apply {
            rows = 4
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Type your prompt here... (Ctrl+Up for history)"

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val isModifierDown = e.isControlDown || e.isMetaDown
                    if (isModifierDown && e.keyCode == KeyEvent.VK_UP) {
                        val history = contextState.getPromptHistory()
                        if (history.isEmpty()) return

                        if (historyIndex == -1) {
                            draftPrompt = text
                            historyIndex = history.size - 1
                            text = history[historyIndex]
                        } else if (historyIndex > 0) {
                            historyIndex--
                            text = history[historyIndex]
                        }
                        e.consume()
                    } else if (isModifierDown && e.keyCode == KeyEvent.VK_DOWN) {
                        val history = contextState.getPromptHistory()
                        if (historyIndex != -1) {
                            if (historyIndex < history.size - 1) {
                                historyIndex++
                                text = history[historyIndex]
                            } else if (historyIndex == history.size - 1) {
                                historyIndex = -1
                                text = draftPrompt
                            }
                            e.consume()
                        }
                    }
                }
            })
        }
        bottomPanel.add(JBScrollPane(promptArea), BorderLayout.CENTER)

        val actionButtonPanel = JPanel(java.awt.GridLayout(1, 4, 5, 0)).apply { border = JBUI.Borders.emptyTop(5) }

        val clearButton = JButton("New Chat").apply {
            addActionListener {
                contextState.clear()
                contextState.loadConfig()
                promptArea.text = ""
                historyIndex = -1
                draftPrompt = ""
                refreshUi()
            }
        }

        undoButton.apply {
            addActionListener {
                val turn = contextState.removeLastTurn()
                if (turn != null) {
                    promptArea.text = turn.prompt
                    refreshUi()

                    val originalText = text
                    text = "Undone!"
                    isEnabled = false
                    Timer(1500) { text = originalText; updateUndoButton() }.apply { isRepeats = false }.start()
                }
            }
        }

        val copyButton = JButton("Copy").apply {
            addActionListener {
                val promptText = promptArea.text
                historyIndex = -1
                draftPrompt = ""
                
                val originalText = text
                text = "Generating..."
                isEnabled = false

                ReadAction.nonBlocking<com.github.bumblebee202111.intellijcontextbridge.context.AiPayload> {
                    PayloadGenerator.generatePayload(project, contextState, promptText)
                }
                .finishOnUiThread(ModalityState.defaultModalityState()) { payloadObj ->
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
                    text = "Copied!"
                    Timer(1500) { text = originalText; isEnabled = true }.apply { isRepeats = false }.start()
                }
                .submit(AppExecutorUtil.getAppExecutorService())
            }
        }

        val sendWsButton = JButton("Waiting for Browser...").apply {
            isEnabled = false
            toolTipText = "Send context directly to AI Studio via WebSocket"
            
            addActionListener {
                val promptText = promptArea.text
                historyIndex = -1
                draftPrompt = ""
                
                val originalText = text
                text = "Generating..."
                isEnabled = false

                ReadAction.nonBlocking<com.github.bumblebee202111.intellijcontextbridge.context.AiPayload> {
                    PayloadGenerator.generatePayload(project, contextState, promptText)
                }
                .finishOnUiThread(ModalityState.defaultModalityState()) { payloadObj ->
                    payloadObj.turn?.let { contextState.addTurn(it) }

                    refreshUi()

                    val jsonString = Json.encodeToString(payloadObj)
                    server.broadcast(jsonString)

                    text = "Sent!"
                    promptArea.text = ""
                    Timer(1500) { text = originalText; isEnabled = true }.apply { isRepeats = false }.start()
                }
                .submit(AppExecutorUtil.getAppExecutorService())
            }
        }

        server.onConnectionChanged = { isConnected ->
            SwingUtilities.invokeLater {
                if (isConnected) {
                    sendWsButton.text = "Send to AI Studio"
                    sendWsButton.isEnabled = true
                } else {
                    sendWsButton.text = "Waiting for Browser..."
                    sendWsButton.isEnabled = false
                }
            }
        }

        actionButtonPanel.add(clearButton)
        actionButtonPanel.add(undoButton)
        actionButtonPanel.add(copyButton)
        actionButtonPanel.add(sendWsButton)

        bottomPanel.add(actionButtonPanel, BorderLayout.SOUTH)

        val splitPane = JBSplitter(true, 0.7f)
        splitPane.firstComponent = treeContainer
        splitPane.secondComponent = bottomPanel

        content.add(splitPane, BorderLayout.CENTER)

        refreshUi()
    }

    private fun updateUndoButton() {
        val lastTurn = contextState.getLastTurn()
        if (lastTurn == null) {
            undoButton.isEnabled = false
            undoButton.toolTipText = "No previous sends to undo"
        } else {
            undoButton.isEnabled = true
            val snippet = lastTurn.prompt.replace("\n", " ").take(40)
            val displaySnippet = if (lastTurn.prompt.length > 40) "$snippet..." else snippet
            val time = dateFormat.format(Date(lastTurn.timestamp))
            undoButton.toolTipText = "<html><b>Undo Last Send</b><br>Sent: $time<br>Prompt: <i>$displaySnippet</i><br>Files: ${lastTurn.sentFiles.size}</html>"
        }
    }

    private fun updateDedupedFiles() {
        val projectDir = project.guessProjectDir() ?: return
        val cache = contextState.getDedupCache()

        lastDedupedFiles = contextState.fileStates.entries.mapNotNull { (file, currentLevel) ->
            val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
            val cachedRecord = cache[relativePath]

            // Only show the green cached dot if the selected level exactly matches the cached level!
            if (cachedRecord != null && cachedRecord.level == currentLevel) {
                file
            } else {
                null
            }
        }.toSet()
    }

    fun refreshUi() {
        updateDedupedFiles()
        updateUndoButton()
        refreshTree()
    }

    private fun refreshTree() {
        treeUpdateJob?.cancel()

        treeUpdateJob = ReadAction.nonBlocking<DefaultTreeModel> {
            nodeStateCache.clear()
            val rootDir = project.guessProjectDir()
            val rootNode = if (rootDir != null) {
                buildFileTree(rootDir, showSelectedOnly, searchQuery) ?: DefaultMutableTreeNode(NodeData(rootDir, "No Project Root"))
            } else {
                DefaultMutableTreeNode("No Project Root")
            }
            DefaultTreeModel(rootNode)
        }
        .expireWith(project)
        .finishOnUiThread(ModalityState.NON_MODAL) { newModel ->
            tree.model = newModel
            if (searchQuery.isNotBlank()) {
                TreeUtil.expandAll(tree)
            } else {
                expandExplicitNodes(tree, newModel.root as DefaultMutableTreeNode)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun getComputedLevel(node: DefaultMutableTreeNode): ContextLevel {
        val file = (node.userObject as? NodeData)?.file ?: return ContextLevel.NONE
        
        if (!file.isDirectory || file.children.isEmpty()) return contextState.getLevel(file)

        nodeStateCache[node]?.let { return it }

        var hasFull = false
        var hasSkeleton = false
        var hasNone = false

        val enumeration = node.children()
        while (enumeration.hasMoreElements()) {
            val child = enumeration.nextElement() as DefaultMutableTreeNode
            when (getComputedLevel(child)) {
                ContextLevel.FULL -> hasFull = true
                ContextLevel.SKELETON -> hasSkeleton = true
                ContextLevel.NONE -> hasNone = true
                ContextLevel.MIXED -> { hasFull = true; hasNone = true }
            }
            if (hasFull && hasSkeleton && hasNone) break
        }

        val result = if (hasFull && !hasSkeleton && !hasNone) ContextLevel.FULL
        else if (!hasFull && hasSkeleton && !hasNone) ContextLevel.SKELETON
        else if (!hasFull && !hasSkeleton && hasNone) ContextLevel.NONE
        else if (!hasFull && !hasSkeleton && !hasNone) ContextLevel.NONE
        else ContextLevel.MIXED

        nodeStateCache[node] = result
        return result
    }

    private fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel) {
        val file = (node.userObject as? NodeData)?.file ?: return
        if (!file.isDirectory) {
            contextState.applyStateRecursively(file, level)
        } else {
            val enumeration = node.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val descendant = enumeration.nextElement() as DefaultMutableTreeNode
                val descFile = (descendant.userObject as? NodeData)?.file ?: continue
                if (!descFile.isDirectory) {
                    contextState.applyStateRecursively(descFile, level)
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

        // Flattening logic for middle packages
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