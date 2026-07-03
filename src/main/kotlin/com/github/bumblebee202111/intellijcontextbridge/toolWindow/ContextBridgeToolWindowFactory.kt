package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.parser.ParsedSnippet
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
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

        fun getContent(): javax.swing.JComponent {
            val tabbedPane = com.intellij.ui.components.JBTabbedPane()

            tabbedPane.addTab("1. Send Context", createComposerPanel())
            tabbedPane.addTab("2. Apply Diffs", createReceivePanel())

            return tabbedPane
        }

        private fun createComposerPanel(): JPanel {
            val panel = JPanel(BorderLayout())

            // --- 1. File Tree (Center) ---
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

                        val level = contextState.getLevel(file)
                        when (level) {
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

                    val currentLevel = contextState.getLevel(file)
                    val nextLevel = when (currentLevel) {
                        ContextLevel.NONE -> ContextLevel.SKELETON
                        ContextLevel.SKELETON -> ContextLevel.FULL
                        ContextLevel.FULL -> ContextLevel.NONE
                    }

                    applyStateRecursively(node, nextLevel)
                    tree.repaint()
                }
            })

            panel.add(JBScrollPane(tree), BorderLayout.CENTER)

            // --- 2. Input & Action Area (Bottom) ---
            val bottomPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(5)
            }

            val promptArea = JBTextArea().apply {
                rows = 4
                lineWrap = true
                wrapStyleWord = true
                emptyText.text = "Type your prompt here..."
            }
            bottomPanel.add(JBScrollPane(promptArea), BorderLayout.CENTER)

            val buttonPanel = JPanel(java.awt.GridLayout(1, 2, 5, 0)).apply {
                border = JBUI.Borders.emptyTop(5)
            }

            val clearButton = JButton("New Chat").apply {
                toolTipText = "Clear all selected files and start a new session"
            }
            clearButton.addActionListener {
                contextState.clear()
                promptArea.text = ""
                tree.repaint()
            }

            val copyButton = JButton("Copy to Clipboard").apply {
                toolTipText = "Generate Markdown payload and copy to clipboard"
            }
            copyButton.addActionListener {
                val userPrompt = promptArea.text
                val payload = PayloadGenerator.generatePayload(project, contextState, userPrompt)
                CopyPasteManager.getInstance().setContents(StringSelection(payload))

                val originalText = copyButton.text
                copyButton.text = "Copied!"
                copyButton.isEnabled = false

                Timer(1500) {
                    copyButton.text = originalText
                    copyButton.isEnabled = true
                }.apply {
                    isRepeats = false
                }.start()
            }

            buttonPanel.add(clearButton)
            buttonPanel.add(copyButton)

            bottomPanel.add(buttonPanel, BorderLayout.SOUTH)
            panel.add(bottomPanel, BorderLayout.SOUTH)

            return panel
        }

        private fun createReceivePanel(): JPanel {
            val panel = JPanel(BorderLayout())

            // 1. Text Area for pasting the AI response
            val responseArea = JBTextArea().apply {
                lineWrap = true
                wrapStyleWord = true
                emptyText.text = "Paste the AI's Markdown response here..."
                margin = JBUI.insets(5)
            }

            // 2. List Model to hold parsed snippets
            val listModel = DefaultListModel<ParsedSnippet>()
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

            // 3. Actions
            val diffButton = JButton("Diff Snippet vs. Editor Selection").apply {
                isEnabled = false
                toolTipText = "Highlight code in your editor, select a snippet, and click this to merge."
            }

            snippetList.addListSelectionListener {
                val snippet = snippetList.selectedValue
                diffButton.isEnabled = snippet != null

                if (snippet != null) {
                    // Auto-find and open the file in the editor!
                    val projectPath = project.guessProjectDir()?.path ?: return@addListSelectionListener
                    val targetFile = File(projectPath, snippet.filePath)

                    val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
                    if (virtualFile != null) {
                        // Opens the file and brings it to the front
                        OpenFileDescriptor(project, virtualFile).navigate(true)
                    }
                }
            }

            diffButton.addActionListener {
                val snippet = snippetList.selectedValue ?: return@addActionListener
                showDiff(snippet)
            }

            val parseButton = JButton("Parse Markdown").apply {
                addActionListener {
                    listModel.clear()
                    val markdownText = responseArea.text
                    if (markdownText.isNotBlank()) {
                        val snippets = com.github.bumblebee202111.intellijcontextbridge.parser.MarkdownResponseParser.parse(markdownText)
                        snippets.forEach { listModel.addElement(it) }

                        if (snippets.isEmpty()) {
                            Messages.showInfoMessage("No code blocks found in the response.", "Parse Result")
                        }
                    }
                }
            }

            // 4. Layout Assembly
            // true = vertical split, 0.5f = 50/50 proportion
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

        private fun showDiff(snippet: ParsedSnippet) {
            // 1. Get the currently active editor
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                Messages.showErrorDialog("Please open a file in the editor first.", "No Active Editor")
                return
            }

            val document = editor.document
            val selectionModel = editor.selectionModel

            // 2. Determine the TextRange (Selection or Caret position)
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd
            val textRange = TextRange(start, end)

            // 1. Build the "Proposed" full file text in memory
            val currentText = document.text
            val proposedText = currentText.substring(0, start) + snippet.code + currentText.substring(end)

            val diffContentFactory = DiffContentFactory.getInstance()
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
            val fileType = virtualFile?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(snippet.language)

            // 2. LEFT SIDE: The live, editable document
            val leftContent = diffContentFactory.create(project, document)

            // 3. RIGHT SIDE: The proposed new text (read-only string)
            val rightContent = diffContentFactory.create(project, proposedText, fileType)

            // 4. Show the full-file Diff Request
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
                if (child.name.startsWith(".") || child.name == "build") continue

                if (child.isDirectory) {
                    node.add(buildFileTree(child))
                } else {
                    node.add(DefaultMutableTreeNode(child))
                }
            }
            return node
        }

        private fun applyStateRecursively(node: DefaultMutableTreeNode, level: ContextLevel) {
            val file = node.userObject as? VirtualFile ?: return
            contextState.setLevel(file, level)

            for (i in 0 until node.childCount) {
                val childNode = node.getChildAt(i) as? DefaultMutableTreeNode
                if (childNode != null) {
                    applyStateRecursively(childNode, level)
                }
            }
        }
    }
}