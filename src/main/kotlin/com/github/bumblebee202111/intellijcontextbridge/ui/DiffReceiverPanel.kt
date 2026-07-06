package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.parser.MarkdownResponseParser
import com.github.bumblebee202111.intellijcontextbridge.parser.ParsedSnippet
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import javax.swing.*

class DiffReceiverPanel(private val project: Project) {

    private val responseArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Paste the AI's Markdown response here..."
        margin = JBUI.insets(5)
    }
    private val listModel = DefaultListModel<ParsedSnippet>()
    
    val content: JPanel = JPanel(BorderLayout())

    init {
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

        content.add(splitPane, BorderLayout.CENTER)
    }

    fun handleIncomingMarkdown(markdownText: String) {
        responseArea.text = markdownText
        parseMarkdownAndPopulateList(markdownText)
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
}