package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.parser.MarkdownResponseParser
import com.github.bumblebee202111.intellijcontextbridge.parser.ParsedSnippet
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ContextBridgeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contextBridgeWindow = ContextBridgeToolWindow(project)
        val content = ContentFactory.getInstance().createContent(contextBridgeWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class ContextBridgeToolWindow(private val project: Project) {
        private val contextState = project.service<ContextState>()
        private val server = ApplicationManager.getApplication().getService(
            ContextBridgeServer::class.java)

        // History tracking state
        private var historyIndex = -1
        private var draftPrompt = ""

        // UI Components we need to access from WebSocket callbacks
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
            // Load default config on startup
            contextState.loadConfig()

            // Start the WebSocket Server
            server.start()

            // Handle Browser Connect/Disconnect
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

            // Handle Incoming AI Responses
            server.onMessageReceived = { markdownText ->
                SwingUtilities.invokeLater {
                    // 1. Switch to the "Apply Diffs" tab
                    tabbedPane.selectedIndex = 1

                    // 2. Paste the text
                    responseArea.text = markdownText

                    // 3. Auto-parse the snippets
                    parseMarkdownAndPopulateList(markdownText)
                }
            }
        }

        fun getContent(): JComponent {
            tabbedPane.addTab("1. Send Context", createComposerPanel())
            tabbedPane.addTab("2. Apply Diffs", createReceivePanel())
            return tabbedPane
        }

        private fun createComposerPanel(): JPanel {
            val panel = JPanel(BorderLayout())

            // --- 1. File Tree ---
            val rootDir = project.guessProjectDir()
            val rootNode = if (rootDir != null) buildFileTree(rootDir) else DefaultMutableTreeNode("No Project Root")
            val treeModel = DefaultTreeModel(rootNode)
            val tree = Tree(treeModel)

            tree.cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(
                    tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
                ) {
                    val node = value as? DefaultMutableTreeNode ?: return
                    val file = node.userObject as? VirtualFile
                    if (file != null) {
                        icon = file.fileType.icon
                        append(file.name)
                        when (contextState.getLevel(file)) {
                            ContextLevel.SKELETON -> append("  [S]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                            ContextLevel.FULL -> append("  [F]", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            ContextLevel.NONE -> {}
                        }
                    } else {
                        append(value.toString())
                    }
                }
            }

            tree.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = tree.getRowForLocation(e.x, e.y)
                    if (row == -1) return
                    val path = tree.getPathForRow(row)
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val file = node.userObject as? VirtualFile ?: return

                    val nextLevel = when (contextState.getLevel(file)) {
                        ContextLevel.NONE -> ContextLevel.SKELETON
                        ContextLevel.SKELETON -> ContextLevel.FULL
                        ContextLevel.FULL -> ContextLevel.NONE
                    }
                    contextState.applyStateRecursively(file, nextLevel)
                    tree.repaint()
                }
            })

            panel.add(JBScrollPane(tree), BorderLayout.CENTER)

            // --- 2. Input & Actions ---
            val bottomPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(5) }
            val promptArea = JBTextArea().apply {
                rows = 4
                lineWrap = true
                wrapStyleWord = true
                emptyText.text = "Type your prompt here... (Ctrl+Up for history)"

                // KeyListener for History Navigation
                addKeyListener(object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        val isModifierDown = e.isControlDown || e.isMetaDown // Supports Ctrl (Win/Linux) and Cmd (Mac)

                        if (isModifierDown && e.keyCode == java.awt.event.KeyEvent.VK_UP) {
                            val history = contextState.promptHistory
                            if (history.isEmpty()) return

                            if (historyIndex == -1) {
                                // Save current text as draft before navigating back
                                draftPrompt = text
                                historyIndex = history.size - 1
                                text = history[historyIndex]
                            } else if (historyIndex > 0) {
                                historyIndex--
                                text = history[historyIndex]
                            }
                            e.consume() // Prevent default behavior

                        } else if (isModifierDown && e.keyCode == java.awt.event.KeyEvent.VK_DOWN) {
                            val history = contextState.promptHistory
                            if (historyIndex != -1) {
                                if (historyIndex < history.size - 1) {
                                    historyIndex++
                                    text = history[historyIndex]
                                } else if (historyIndex == history.size - 1) {
                                    // Reached the present, restore the draft
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

            val buttonPanel = JPanel(java.awt.GridLayout(1, 3, 5, 0)).apply {
                border = JBUI.Borders.emptyTop(5)
            }

            val clearButton = JButton("New Chat").apply {
                addActionListener {
                    contextState.clear()
                    contextState.loadConfig()
                    promptArea.text = ""
                    historyIndex = -1
                    draftPrompt = ""
                    tree.repaint()
                }
            }

            val copyButton = JButton("Copy").apply {
                addActionListener {
                    val promptText = promptArea.text
                    contextState.addPromptToHistory(promptText) // Save to history
                    historyIndex = -1 // Reset navigator
                    draftPrompt = ""

        val payloadObj = PayloadGenerator.generatePayload(project, contextState, promptText)

        // Clipboard only gets the Markdown text (attachments can't be copied this way)
        CopyPasteManager.getInstance().setContents(StringSelection(payloadObj.text))

        if (payloadObj.attachments.isNotEmpty()) {
            Messages.showWarningDialog(
                "You copied ${payloadObj.attachments.size} media file(s). They cannot be copied to the clipboard. Please use 'Send to AI Studio' or attach them manually.",
                "Media Files Skipped"
            )
        }

        val originalText = text
        text = "Copied!"
        isEnabled = false
        Timer(1500) { text = originalText; isEnabled = true }.apply { isRepeats = false }.start()
    }
}

            // Send via WebSocket Action
            sendWsButton.addActionListener {
                val promptText = promptArea.text
                contextState.addPromptToHistory(promptText) // Save to history
                historyIndex = -1 // Reset navigator
                draftPrompt = ""

    val payloadObj = PayloadGenerator.generatePayload(project, contextState, promptText)

    // Serialize the object to JSON!
    val jsonString = Json.encodeToString(payloadObj)
    server.broadcast(jsonString)

                val originalText = sendWsButton.text
                sendWsButton.text = "Sent!"
                sendWsButton.isEnabled = false
                Timer(1500) { sendWsButton.text = originalText; sendWsButton.isEnabled = true }.apply { isRepeats = false }.start()
            }

            buttonPanel.add(clearButton)
            buttonPanel.add(copyButton)
            buttonPanel.add(sendWsButton)

            bottomPanel.add(buttonPanel, BorderLayout.SOUTH)
            panel.add(bottomPanel, BorderLayout.SOUTH)

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

            val diffButton = JButton("Diff Snippet vs. Editor Selection").apply {
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
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                Messages.showErrorDialog("Please open a file in the editor first.", "No Active Editor")
                return
            }

            val document = editor.document
            val selectionModel = editor.selectionModel
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd

            val currentText = document.text
            val proposedText = currentText.substring(0, start) + snippet.code + currentText.substring(end)

            val diffContentFactory = DiffContentFactory.getInstance()
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            val fileType = virtualFile?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(snippet.language)

            val leftContent = diffContentFactory.create(project, document)
            val rightContent = diffContentFactory.create(project, proposedText, fileType)

            val request = SimpleDiffRequest(
                "Apply AI Snippet: ${snippet.filePath}",
                leftContent,
                rightContent,
                "Current Code",
                "Proposed Code (AI)"
            )

            DiffManager.getInstance().showDiff(project, request)
        }

        @Suppress("UnsafeVfsRecursion")
        private fun buildFileTree(dir: VirtualFile): DefaultMutableTreeNode {
            val node = DefaultMutableTreeNode(dir)
            val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            for (child in children) {
                // UNIFIED FILTER: Exact same logic as ContextState
                if (FileFilterUtil.isIgnored(project, child)) continue

                if (child.isDirectory) {
                    node.add(buildFileTree(child))
                } else {
                    node.add(DefaultMutableTreeNode(child))
                }
            }
            return node
        }
    }
}