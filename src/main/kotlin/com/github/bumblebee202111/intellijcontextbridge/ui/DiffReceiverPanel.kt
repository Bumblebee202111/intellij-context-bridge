package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.parser.AgentAction
import com.github.bumblebee202111.intellijcontextbridge.parser.DeleteAction
import com.github.bumblebee202111.intellijcontextbridge.parser.EditAction
import com.github.bumblebee202111.intellijcontextbridge.parser.MarkdownResponseParser
import com.github.bumblebee202111.intellijcontextbridge.parser.RenameAction
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

class DiffReceiverPanel(private val project: Project) {

    private val responseArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Paste the AI's Markdown response here..."
        margin = JBUI.insets(5)
    }
    private val listModel = DefaultListModel<AgentAction>()
    
    private val actionList = JBList(listModel).apply {
        emptyText.text = "No actions parsed yet."
        cellRenderer = object : ColoredListCellRenderer<AgentAction>() {
            override fun customizeCellRenderer(
                list: JList<out AgentAction>, value: AgentAction, index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                when (value) {
                    is EditAction -> {
                        append("[EDIT] ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append(value.filePath, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is DeleteAction -> {
                        append("[DELETE] ", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        append(value.filePath, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    is RenameAction -> {
                        append("[RENAME] ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("${value.sourcePath} -> ${value.targetPath}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
                if (!value.explanation.isNullOrBlank()) {
                    append(" - ${value.explanation}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }

    val content: JPanel = JPanel(BorderLayout())

    init {
        val executeButton = JButton("Review / Execute Action").apply {
            isEnabled = false
        }

        actionList.addListSelectionListener {
            val action = actionList.selectedValue
            executeButton.isEnabled = action != null

            if (action is EditAction) {
                val projectPath = project.guessProjectDir()?.path ?: return@addListSelectionListener
                val targetFile = File(projectPath, action.filePath)

                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
                if (virtualFile != null) {
                    OpenFileDescriptor(project, virtualFile).navigate(true)
                }
            }
        }

        actionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    actionList.selectedValue?.let { executeAction(it) }
                }
            }
        })

        actionList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    actionList.selectedValue?.let { executeAction(it) }
                    e.consume()
                }
            }
        })

        executeButton.addActionListener {
            val action = actionList.selectedValue ?: return@addActionListener
            executeAction(action)
        }

        val parseButton = JButton("Parse Markdown").apply {
            addActionListener { parseMarkdownAndPopulateList(responseArea.text) }
        }

        val splitPane = JBSplitter(true, 0.5f)
        splitPane.firstComponent = JBScrollPane(responseArea)

        val bottomContainer = JPanel(BorderLayout())
        bottomContainer.add(JBScrollPane(actionList), BorderLayout.CENTER)

        val buttonPanel = JPanel(java.awt.GridLayout(1, 2, 5, 0)).apply {
            border = JBUI.Borders.empty(5)
            add(parseButton)
            add(executeButton)
        }
        bottomContainer.add(buttonPanel, BorderLayout.SOUTH)
        splitPane.secondComponent = bottomContainer

        content.add(splitPane, BorderLayout.CENTER)
    }

    fun handleIncomingMarkdown(markdownText: String): Boolean {
        responseArea.text = markdownText
        return parseMarkdownAndPopulateList(markdownText)
    }

    private fun parseMarkdownAndPopulateList(markdownText: String): Boolean {
        listModel.clear()
        if (markdownText.isNotBlank()) {
            val actions = MarkdownResponseParser.parse(markdownText)
            actions.forEach { listModel.addElement(it) }
            if (actions.isEmpty()) {
                Messages.showInfoMessage("No actions found in the response.", "Parse Result")
                return false
            }
            return true
        }
        return false
    }

    private fun executeAction(action: AgentAction) {
        val projectPath = project.guessProjectDir()?.path ?: return

        when (action) {
            is EditAction -> {
                val targetFile = File(projectPath, action.filePath)
                var virtualFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)

                if (virtualFile == null) {
                    val result = Messages.showYesNoDialog(
                        project,
                        "File '${action.filePath}' does not exist. Create it?",
                        "Create File",
                        Messages.getQuestionIcon()
                    )
                    if (result == Messages.YES) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            try {
                                val parentDir = VfsUtil.createDirectoryIfMissing(targetFile.parentFile.absolutePath)
                                virtualFile = parentDir?.createChildData(this, targetFile.name)
                            } catch (e: Exception) {
                                thisLogger().error("Failed to create file", e)
                            }
                        }
                    } else return
                }

                val diffContentFactory = DiffContentFactory.getInstance()
                val fileType = virtualFile?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(action.language)

                val leftContent = if (virtualFile != null) {
                    val document = FileDocumentManager.getInstance().getDocument(virtualFile!!)
                    if (document != null) diffContentFactory.create(project, document)
                    else diffContentFactory.create(project, "", fileType)
                } else {
                    diffContentFactory.create(project, "", fileType)
                }

                val rightContent = diffContentFactory.create(project, action.code, fileType)
                val title = if (!action.explanation.isNullOrBlank()) {
                    "Apply AI Snippet: ${action.filePath} - ${action.explanation}"
                } else "Apply AI Snippet: ${action.filePath}"

                val request = SimpleDiffRequest(title, leftContent, rightContent, "Local Code", "AI Snippet")
                DiffManager.getInstance().showDiff(project, request)
            }
            is DeleteAction -> {
                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(projectPath, action.filePath))
                if (virtualFile != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile != null) {
                        SafeDeleteHandler.invoke(project, arrayOf(psiFile), true)
                    }
                } else {
                    Messages.showErrorDialog("File not found: ${action.filePath}", "Delete Error")
                }
            }
            is RenameAction -> {
                val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(projectPath, action.sourcePath))
                val targetFile = File(projectPath, action.targetPath)
                if (virtualFile != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile != null) {
                        RenameProcessor(project, psiFile, targetFile.name, false, false).run()
                    }
                } else {
                    Messages.showErrorDialog("Source file not found: ${action.sourcePath}", "Rename Error")
                }
            }
        }

        // Auto-advance to the next item in the list
        val currentIndex = actionList.selectedIndex
        if (currentIndex != -1 && currentIndex < listModel.size() - 1) {
            actionList.selectedIndex = currentIndex + 1
            actionList.ensureIndexIsVisible(currentIndex + 1)
        }
    }
}