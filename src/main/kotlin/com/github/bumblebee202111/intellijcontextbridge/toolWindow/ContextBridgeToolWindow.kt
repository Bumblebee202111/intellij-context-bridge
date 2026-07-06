package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.parser.MarkdownResponseParser
import com.github.bumblebee202111.intellijcontextbridge.parser.ParsedSnippet
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextTreeCellRenderer
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ContextBridgeToolWindow(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

    private var historyIndex = -1
    private var draftPrompt = ""
    private var lastDedupedFiles = emptySet<VirtualFile>()

    // Lightning-fast memory cache for UI rendering
    private val nodeStateCache = mutableMapOf<DefaultMutableTreeNode, ContextLevel>()

    private val tabbedPane = com.intellij.ui.components.JBTabbedPane()
    private val responseArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Paste the AI's Markdown response here..."
        margin = JBUI.insets(5)
    }
    private val listModel = DefaultListModel<ParsedSnippet>()
    private val sendWsButton = JButton("Waiting for Browser...").apply {
        isEnabled = false
        toolTipText = "Send context directly to AI Studio via WebSocket"
    }

    init {
        contextState.loadConfig()
        server.start()

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

        server.onMessageReceived = { markdownText ->
            SwingUtilities.invokeLater {
                tabbedPane.selectedIndex = 1
                responseArea.text = markdownText
                parseMarkdownAndPopulateList(markdownText)
            }
        }
    }

    // Computes directory state instantly using the filtered in-memory TreeModel
    private fun getComputedLevel(node: DefaultMutableTreeNode): ContextLevel {
        val file = node.userObject as? VirtualFile ?: return ContextLevel.NONE
        if (!file.isDirectory) return contextState.getLevel(file)

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

    // Applies state only to files currently visible/loaded in the TreeModel
    private fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel) {
        val file = node.userObject as? VirtualFile ?: return
        if (!file.isDirectory) {
            contextState.applyStateRecursively(file, level)
        } else {
            val enumeration = node.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val descendant = enumeration.nextElement() as DefaultMutableTreeNode
                val descFile = descendant.userObject as? VirtualFile ?: continue
                if (!descFile.isDirectory) {
                    contextState.applyStateRecursively(descFile, level)
                }
            }
        }
        nodeStateCache.clear()
    }

    fun getContent(): JComponent {
        tabbedPane.addTab("1. Send Context", createComposerPanel())
        tabbedPane.addTab("2. Apply Diffs", createReceivePanel())
        return tabbedPane
    }

    private fun createComposerPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        var showSelectedOnly = false
        var searchQuery = ""

        val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
        val tree = Tree(treeModel).apply {
            toolTipText = "Left-click icon: Toggle. Right-click row: Clear. Double-click: Open."
        }

        fun refreshTree() {
            nodeStateCache.clear()
            val rootDir = project.guessProjectDir()
            val rootNode = if (rootDir != null) buildFileTree(rootDir, showSelectedOnly, searchQuery) ?: DefaultMutableTreeNode("No Project Root") else DefaultMutableTreeNode("No Project Root")
            treeModel.setRoot(rootNode)
            treeModel.reload()

            if (searchQuery.isNotBlank()) {
                TreeUtil.expandAll(tree)
            } else {
                expandExplicitNodes(tree, rootNode)
            }
        }

        // Use the local UI node cache
        tree.cellRenderer = ContextTreeCellRenderer(::getComputedLevel) { file -> lastDedupedFiles.contains(file) }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val file = node.userObject as? VirtualFile ?: return

                if (SwingUtilities.isRightMouseButton(e)) {
                    applyStateToNode(node, ContextLevel.NONE)
                    if (showSelectedOnly) refreshTree() else {
                        tree.repaint()
                        autoCollapse(tree, node, path)
                    }
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 22) {
                            val currentLevel = getComputedLevel(node)
                            val nextLevel = when (currentLevel) {
                                ContextLevel.NONE, ContextLevel.MIXED -> ContextLevel.FULL
                                ContextLevel.FULL -> ContextLevel.SKELETON
                                ContextLevel.SKELETON -> ContextLevel.FULL
                            }
                            applyStateToNode(node, nextLevel)
                            if (showSelectedOnly) refreshTree() else tree.repaint()
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
                    val file = node.userObject as? VirtualFile ?: continue

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
                    if (showSelectedOnly) refreshTree() else tree.repaint()
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
                        refreshTree()
                    } else {
                        Messages.showInfoMessage("No active editor found.", "Add Active File")
                    }
                }
            }

            val filterToggle = JToggleButton(AllIcons.General.Filter).apply {
                toolTipText = "Show Selected Context Only"
                addActionListener { showSelectedOnly = isSelected; refreshTree() }
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
                        refreshTree()
                    }.apply { isRepeats = false; start() }
                }
            })
        }

        topToolbar.add(buttonPanel, BorderLayout.WEST)
        topToolbar.add(searchField, BorderLayout.CENTER)

        treeContainer.add(topToolbar, BorderLayout.NORTH)
        treeContainer.add(JBScrollPane(tree), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }
        val promptArea = JBTextArea().apply {
            rows = 4
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Type your prompt here... (Ctrl+Up for history)"

            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val isModifierDown = e.isControlDown || e.isMetaDown
                    if (isModifierDown && e.keyCode == KeyEvent.VK_UP) {
                        val history = contextState.promptHistory
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
                        val history = contextState.promptHistory
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

        val actionButtonPanel = JPanel(java.awt.GridLayout(1, 3, 5, 0)).apply { border = JBUI.Borders.emptyTop(5) }

        val clearButton = JButton("New Chat").apply {
            addActionListener {
                contextState.clear()
                contextState.loadConfig()
                lastDedupedFiles = emptySet()
                promptArea.text = ""
                historyIndex = -1
                draftPrompt = ""
                refreshTree()
            }
        }

        val copyButton = JButton("Copy").apply {
            addActionListener {
                val promptText = promptArea.text
                contextState.addPromptToHistory(promptText)
                historyIndex = -1
                draftPrompt = ""

                val originalText = text
                text = "Generating..."
                isEnabled = false

                ReadAction.nonBlocking<com.github.bumblebee202111.intellijcontextbridge.context.AiPayload> {
                    PayloadGenerator.generatePayload(project, contextState, promptText)
                }
                .finishOnUiThread(ModalityState.defaultModalityState()) { payloadObj ->
                    lastDedupedFiles = payloadObj.dedupedFiles
                    refreshTree()

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

        sendWsButton.addActionListener {
            val promptText = promptArea.text
            contextState.addPromptToHistory(promptText)
            historyIndex = -1
            draftPrompt = ""

            val originalText = sendWsButton.text
            sendWsButton.text = "Generating..."
            sendWsButton.isEnabled = false

            ReadAction.nonBlocking<com.github.bumblebee202111.intellijcontextbridge.context.AiPayload> {
                PayloadGenerator.generatePayload(project, contextState, promptText)
            }
            .finishOnUiThread(ModalityState.defaultModalityState()) { payloadObj ->
                lastDedupedFiles = payloadObj.dedupedFiles
                refreshTree()

                val jsonString = Json.encodeToString(payloadObj)
                server.broadcast(jsonString)

                sendWsButton.text = "Sent!"
                promptArea.text = ""
                Timer(1500) { sendWsButton.text = originalText; sendWsButton.isEnabled = true }.apply { isRepeats = false }.start()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        }

        actionButtonPanel.add(clearButton)
        actionButtonPanel.add(copyButton)
        actionButtonPanel.add(sendWsButton)

        bottomPanel.add(actionButtonPanel, BorderLayout.SOUTH)

        val splitPane = JBSplitter(true, 0.7f)
        splitPane.firstComponent = treeContainer
        splitPane.secondComponent = bottomPanel

        refreshTree()
        panel.add(splitPane, BorderLayout.CENTER)

        return panel
    }

    private fun createReceivePanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val snippetList = JBList(listModel).apply {
            emptyText.text = "No snippets parsed yet."
            cellRenderer = object : ColoredListCellRenderer<ParsedSnippet>() {
                override fun customizeCellRenderer(
                    list: JList<out ParsedSnippet>, value: ParsedSnippet, index: Int, selected: Boolean, hasFocus: Boolean
                ) {
                    append(value.filePath, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${value.language}, ${value.code.lines().size} lines)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        val diffButton = JButton("Review & Apply Diff").apply {
            isEnabled = false
        }

        snippetList.addListSelectionListener {
            val snippet = snippetList.selectedValue
            diffButton.isEnabled = snippet != null

            if (snippet != null) {
                val projectPath = project.guessProjectDir()?.path ?: return@addListSelectionListener
                val targetFile = File(projectPath, snippet.filePath)

                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
                if (virtualFile != null) {
                    OpenFileDescriptor(project, virtualFile).navigate(true)
                }
            }
        }

        diffButton.addActionListener {
            val snippet = snippetList.selectedValue ?: return@addActionListener
            showDiff(snippet)
        }

        val parseButton = JButton("Parse Markdown").apply {
            addActionListener { parseMarkdownAndPopulateList(responseArea.text) }
        }

        val splitPane = JBSplitter(true, 0.5f)
        splitPane.firstComponent = JBScrollPane(responseArea)

        val bottomContainer = JPanel(BorderLayout())
        bottomContainer.add(JBScrollPane(snippetList), BorderLayout.CENTER)

        val buttonPanel = JPanel(java.awt.GridLayout(1, 2, 5, 0)).apply {
            border = JBUI.Borders.empty(5)
            add(parseButton)
            add(diffButton)
        }
        bottomContainer.add(buttonPanel, BorderLayout.SOUTH)
        splitPane.secondComponent = bottomContainer

        panel.add(splitPane, BorderLayout.CENTER)
        return panel
    }

    private fun parseMarkdownAndPopulateList(markdownText: String) {
        listModel.clear()
        if (markdownText.isNotBlank()) {
            val snippets = MarkdownResponseParser.parse(markdownText)
            snippets.forEach { listModel.addElement(it) }
            if (snippets.isEmpty()) {
                Messages.showInfoMessage("No code blocks found in the response.", "Parse Result")
            }
        }
    }

    private fun showDiff(snippet: ParsedSnippet) {
        val projectPath = project.guessProjectDir()?.path ?: return
        val targetFile = File(projectPath, snippet.filePath)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)

        val diffContentFactory = DiffContentFactory.getInstance()
        val fileType = virtualFile?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(snippet.language)

        val leftContent = if (virtualFile != null) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document != null) diffContentFactory.create(project, document)
            else diffContentFactory.create(project, "", fileType)
        } else {
            diffContentFactory.create(project, "", fileType)
        }

        val rightContent = diffContentFactory.create(project, snippet.code, fileType)

        val request = SimpleDiffRequest(
            "Apply AI Snippet: ${snippet.filePath}",
            leftContent,
            rightContent,
            "Local Code",
            "AI Snippet"
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    @Suppress("UnsafeVfsRecursion")
    private fun buildFileTree(dir: VirtualFile, showSelectedOnly: Boolean, searchQuery: String): DefaultMutableTreeNode? {
        if (FileFilterUtil.isIgnored(project, dir)) return null

        val node = DefaultMutableTreeNode(dir)
        val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        var hasValidChildren = false

        val dirMatchesSearch = searchQuery.isBlank() || dir.name.contains(searchQuery, ignoreCase = true)

        for (child in children) {
            if (FileFilterUtil.isIgnored(project, child)) continue

            if (child.isDirectory) {
                val childNode = buildFileTree(child, showSelectedOnly, searchQuery)
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
                    node.add(DefaultMutableTreeNode(child))
                    hasValidChildren = true
                }
            }
        }

        // Use the fast memory state to check if the directory should be visible
        val level = getComputedLevel(node)
        val isSelected = level != ContextLevel.NONE
        val passesSelection = !showSelectedOnly || isSelected

        if (!hasValidChildren) {
            if (!passesSelection) return null
            if (searchQuery.isNotBlank() && !dirMatchesSearch) return null
            if (dir.children.isNotEmpty()) return null
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

    private fun containsDifferentDescendant(node: DefaultMutableTreeNode, parentLevel: ContextLevel): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val childLevel = getComputedLevel(child)

            if (childLevel != parentLevel) return true
            if (containsDifferentDescendant(child, parentLevel)) return true
        }
        return false
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