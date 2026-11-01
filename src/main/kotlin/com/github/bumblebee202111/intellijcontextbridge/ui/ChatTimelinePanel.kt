package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.parser.AgentTool
import com.github.bumblebee202111.intellijcontextbridge.parser.DeleteFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.EditFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.FillCommitMessageTool
import com.github.bumblebee202111.intellijcontextbridge.parser.ReadFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.RenameFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.ToolParser
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.state.ModelTurn
import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import com.github.bumblebee202111.intellijcontextbridge.vcs.CommitBridgeService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.Rectangle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import kotlin.collections.remove
import kotlin.text.contains
import kotlin.text.format

private class WidthTrackingPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private sealed interface TimelineItem {
    val timestamp: Long
    val sortOrder: Int

    data class User(val turn: UserTurn, val isLatestUserTurn: Boolean) : TimelineItem {
        override val timestamp: Long get() = turn.timestamp
        override val sortOrder: Int get() = 0
    }

    data class Model(val turn: ModelTurn) : TimelineItem {
        override val timestamp: Long get() = turn.timestamp
        override val sortOrder: Int get() = 1
    }
}

class ChatTimelinePanel(
    private val project: Project,
    private val onStageReadFiles: (paths: List<String>, switchToTree: Boolean) -> Unit,
    private val onSendRequestedFiles: (paths: List<String>) -> Unit,
    private val onHistoryChanged: () -> Unit,
    private val onFocusChat: () -> Unit
) {
    private val contextState = project.service<ContextState>()
    private val scope = contextState.scope
    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    private val viewedEditTools = mutableSetOf<String>()
    private val expandedContextTurns = mutableSetOf<String>()
    private val rawModeTurns = mutableSetOf<String>()

    private val timelineContainer = WidthTrackingPanel(VerticalLayout(JBUI.scale(10))).apply {
        border = JBUI.Borders.empty(8)
    }

    private val scrollPane = JBScrollPane(timelineContainer).apply {
        border = JBUI.Borders.empty()
    }

    private val emptyPanel = JBPanelWithEmptyText(BorderLayout()).apply {
        emptyText.text = "No conversation turns yet."
        emptyText.appendLine("Send context below or click 'Paste Response' to parse an AI reply.")
    }

    val content: JPanel = JPanel(BorderLayout()).apply {
        add(emptyPanel, BorderLayout.CENTER)
    }

    fun refresh(scrollToBottom: Boolean = false) {
        val state = contextState.getState()
        val userTurns = state.turns.toList()
        val modelTurns = state.modelTurns.toList()
        timelineContainer.removeAll()

        if (userTurns.isEmpty() && modelTurns.isEmpty()) {
            content.removeAll()
            content.add(emptyPanel, BorderLayout.CENTER)
            content.revalidate()
            content.repaint()
            return
        }

        if (scrollPane.parent != content) {
            content.removeAll()
            content.add(scrollPane, BorderLayout.CENTER)
        }

        val items = buildList {
            userTurns.forEachIndexed { index, turn ->
                add(TimelineItem.User(turn, isLatestUserTurn = index == userTurns.lastIndex))
            }
            modelTurns.forEach { turn ->
                add(TimelineItem.Model(turn))
            }
        }.sortedWith(compareBy<TimelineItem> { it.timestamp }.thenBy { it.sortOrder })

        for (item in items) {
            when (item) {
                is TimelineItem.User -> timelineContainer.add(buildUserTurnCard(item.turn, item.isLatestUserTurn))
                is TimelineItem.Model -> timelineContainer.add(buildAiTurnCard(item.turn))
            }
        }

        content.revalidate()
        content.repaint()

        if (scrollToBottom) {
            SwingUtilities.invokeLater {
                val verticalBar = scrollPane.verticalScrollBar
                verticalBar.value = verticalBar.maximum
            }
        }
    }

    fun processIncomingMarkdown(markdownText: String, fromWebSocket: Boolean) {
        if (markdownText.isBlank()) return

        val tools = ToolParser.parse(markdownText)
        val commitTools = tools.filterIsInstance<FillCommitMessageTool>()
        commitTools.forEach {
            project.getService(CommitBridgeService::class.java).injectCommitMessage(it.message)
        }

        val nonCommitTools = tools.filterNot { it is FillCommitMessageTool }
        if (fromWebSocket && commitTools.isNotEmpty() && nonCommitTools.isEmpty()) {
            return
        }

        contextState.addModelTurn(markdownText)

        val readTools = nonCommitTools.filterIsInstance<ReadFileTool>()
        if (readTools.isNotEmpty()) {
            val allPaths = readTools.flatMap { it.paths }.distinct()
            onStageReadFiles(allPaths, false)
        }

        refresh(scrollToBottom = true)
        onFocusChat()
    }

    private fun buildUserTurnCard(turn: UserTurn, isLatestTurn: Boolean): JComponent {
        val card = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            background = JBColor.namedColor("EditorPane.background", UIUtil.getPanelBackground())
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true),
                JBUI.Borders.empty(8, 10)
            )
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            val modeText = if (turn.mode == IntentMode.EDIT) "⚡ EDIT" else "💬 ASK"
            val modeBadge = JBLabel(modeText).apply {
                font = JBFont.small().asBold()
                foreground = if (turn.mode == IntentMode.EDIT) {
                    JBColor(0x2E7D32, 0x81C784)
                } else {
                    JBUI.CurrentTheme.Label.foreground()
                }
            }
            val timeLabel = JBLabel(dateFormat.format(Date(turn.timestamp))).apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            add(modeBadge)
            add(timeLabel)
        }

        val deleteLink = ActionLink("Delete") {
            contextState.removeTurn(turn.id)
            if (isLatestTurn) {
                scope.launch {
                    readAction {
                        contextState.restoreFromLastTurn()
                    }
                    ApplicationManager.getApplication().invokeLater({
                        refresh(scrollToBottom = false)
                        onHistoryChanged()
                    }, ModalityState.nonModal())
                }
            } else {
                refresh(scrollToBottom = false)
                onHistoryChanged()
            }
        }.apply {
            font = JBFont.small()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            toolTipText = "Delete this turn from session history"
        }

        header.add(leftHeader, BorderLayout.WEST)
        header.add(deleteLink, BorderLayout.EAST)
        card.add(header)

        if (turn.prompt.isNotBlank()) {
            card.add(createWrappedTextArea(turn.prompt))
        } else {
            card.add(JBLabel("(No prompt text)").apply {
                font = JBFont.regular().asItalic()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            })
        }

        if (turn.sentFiles.isNotEmpty()) {
            card.add(buildContextSummarySection(turn))
        }

        return card
    }

    private fun buildContextSummarySection(turn: UserTurn): JComponent {
        val container = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
        }

        val records = turn.sentFiles.entries.sortedBy { it.key }
        val sentComplete = records.count { !it.value.wasDeduped && it.value.level == ContextLevel.COMPLETE }
        val sentSkeleton = records.count { !it.value.wasDeduped && it.value.level == ContextLevel.SKELETON }
        val totalSent = sentComplete + sentSkeleton
        val cachedCount = records.count { it.value.wasDeduped }

        val summaryParts = mutableListOf<String>()
        if (sentComplete > 0) summaryParts.add("$sentComplete Complete")
        if (sentSkeleton > 0) summaryParts.add("$sentSkeleton Skeleton")
        val sentBreakdown = if (summaryParts.isNotEmpty()) " (${summaryParts.joinToString(", ")})" else ""
        val cachedSuffix = if (cachedCount > 0) " · $cachedCount cached" else ""

        val isExpanded = expandedContextTurns.contains(turn.id)
        val arrow = if (isExpanded) "▾" else "▸"
        val toggleLink = ActionLink("$arrow Context: $totalSent sent$sentBreakdown$cachedSuffix") {
            if (expandedContextTurns.contains(turn.id)) {
                expandedContextTurns.remove(turn.id)
            } else {
                expandedContextTurns.add(turn.id)
            }
            refresh(scrollToBottom = false)
        }.apply {
            font = JBFont.small()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        container.add(toggleLink)

        if (isExpanded) {
            val filesPanel = JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(12)
            }
            for ((path, record) in records) {
                val levelStr = if (record.level == ContextLevel.COMPLETE) "Complete" else "Skeleton"
                val statusStr = if (record.wasDeduped) "$levelStr, Cached" else levelStr
                val rowLabel = JBLabel("$path ($statusStr)").apply {
                    font = JBFont.small()
                    foreground = if (record.wasDeduped) {
                        JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    } else {
                        JBUI.CurrentTheme.Label.foreground()
                    }
                }
                filesPanel.add(rowLabel)
            }
            container.add(filesPanel)
        }

        return container
    }

    private fun buildAiTurnCard(turn: ModelTurn): JComponent {
        val card = JPanel(VerticalLayout(JBUI.scale(8))).apply {
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0x4CAF50, 0x388E3C), 1, true),
                JBUI.Borders.empty(8, 10)
            )
        }

        val isRaw = rawModeTurns.contains(turn.id)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            val titleLabel = JBLabel("🤖 AI Response").apply {
                font = JBFont.small().asBold()
            }
            val timeLabel = JBLabel(dateFormat.format(Date(turn.timestamp))).apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            add(titleLabel)
            add(timeLabel)
        }

        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            val rawToggle = ActionLink(if (isRaw) "Show Formatted" else "Raw Markdown") {
                if (rawModeTurns.contains(turn.id)) {
                    rawModeTurns.remove(turn.id)
                } else {
                    rawModeTurns.add(turn.id)
                }
                refresh(scrollToBottom = false)
            }.apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }

            val deleteLink = ActionLink("Delete") {
                contextState.removeModelTurn(turn.id)
                refresh(scrollToBottom = false)
            }.apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                toolTipText = "Delete this AI response from timeline"
            }

            add(rawToggle)
            add(deleteLink)
        }

        header.add(leftHeader, BorderLayout.WEST)
        header.add(rightHeader, BorderLayout.EAST)
        card.add(header)

        if (isRaw) {
            card.add(createWrappedTextArea(turn.markdown))
            return card
        }

        val cleanProse = ToolParser.stripToolBlocks(turn.markdown)
        if (cleanProse.isNotBlank()) {
            card.add(createWrappedTextArea(cleanProse))
        }

        val tools = ToolParser.parse(turn.markdown)
        val readTools = tools.filterIsInstance<ReadFileTool>()
        if (readTools.isNotEmpty()) {
            val allPaths = readTools.flatMap { it.paths }.distinct()
            val combinedReason = readTools.mapNotNull { it.explanation.takeIf(String::isNotBlank) }.joinToString("; ")
            card.add(buildReadToolCard(allPaths, combinedReason))
        }

        for ((toolIdx, tool) in tools.withIndex()) {
            when (tool) {
                is ReadFileTool -> Unit
                is EditFileTool -> card.add(buildEditToolCard(turn.id, toolIdx, tool))
                is DeleteFileTool -> card.add(buildDeleteToolCard(tool))
                is RenameFileTool -> card.add(buildRenameToolCard(tool))
                is FillCommitMessageTool -> card.add(buildCommitToolCard(tool))
            }
        }

        return card
    }

    private fun buildReadToolCard(paths: List<String>, reason: String): JComponent {
        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val title = JBLabel("📄 Requested ${paths.size} ${if (paths.size == 1) "file" else "files"}", AllIcons.Nodes.ConfigFolder, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
        }
        topRow.add(title, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val inspectBtn = JButton("Inspect in Tree").apply {
                isOpaque = false
                addActionListener {
                    onStageReadFiles(paths, true)
                }
            }
            val sendBtn = JButton("Approve & Send").apply {
                putClientProperty("JButton.buttonType", "defaultButton")
                addActionListener {
                    onSendRequestedFiles(paths)
                }
            }
            add(inspectBtn)
            add(sendBtn)
        }
        topRow.add(buttonsPanel, BorderLayout.EAST)
        box.add(topRow)

        if (reason.isNotBlank()) {
            box.add(createWrappedTextArea(reason, isSecondary = true))
        }

        val pathsPanel = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
        }
        for (path in paths) {
            pathsPanel.add(JBLabel("• $path").apply {
                font = JBFont.small()
            })
        }
        box.add(pathsPanel)

        return box
    }

    private fun buildEditToolCard(turnId: String, toolIdx: Int, tool: EditFileTool): JComponent {
        val toolKey = "$turnId:$toolIdx:${tool.filePath}"
        val isViewed = viewedEditTools.contains(toolKey)

        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val fileName = File(tool.filePath).name
        val fileTypeIcon = FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.Actions.Edit
        val pathLabel = JBLabel(tool.filePath, fileTypeIcon, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
        }
        topRow.add(pathLabel, BorderLayout.CENTER)

        val diffBtn = JButton(if (isViewed) "✓ Diff Opened" else "Open Diff", AllIcons.Actions.Diff).apply {
            addActionListener {
                viewedEditTools.add(toolKey)
                text = "✓ Diff Opened"
                openEditDiff(tool)
            }
        }
        topRow.add(diffBtn, BorderLayout.EAST)
        box.add(topRow)

        if (tool.explanation.isNotBlank()) {
            box.add(createWrappedTextArea(tool.explanation, isSecondary = true))
        }

        return box
    }

    private fun buildDeleteToolCard(tool: DeleteFileTool): JComponent {
        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val title = JBLabel("Delete ${tool.filePath}", AllIcons.Actions.GC, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
            foreground = JBUI.CurrentTheme.Label.errorForeground()
        }
        topRow.add(title, BorderLayout.CENTER)

        val deleteBtn = JButton("Safe Delete...").apply {
            addActionListener {
                executeDeleteTool(tool)
            }
        }
        topRow.add(deleteBtn, BorderLayout.EAST)
        box.add(topRow)

        if (tool.explanation.isNotBlank()) {
            box.add(createWrappedTextArea(tool.explanation, isSecondary = true))
        }

        return box
    }

    private fun buildRenameToolCard(tool: RenameFileTool): JComponent {
        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val title = JBLabel("${tool.sourcePath} → ${tool.targetPath}", AllIcons.Actions.RefactoringBulb, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
        }
        topRow.add(title, BorderLayout.CENTER)

        val renameBtn = JButton("Rename...").apply {
            addActionListener {
                executeRenameTool(tool)
            }
        }
        topRow.add(renameBtn, BorderLayout.EAST)
        box.add(topRow)

        if (tool.explanation.isNotBlank()) {
            box.add(createWrappedTextArea(tool.explanation, isSecondary = true))
        }

        return box
    }

    private fun buildCommitToolCard(tool: FillCommitMessageTool): JComponent {
        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val title = JBLabel("Commit Message", AllIcons.Actions.Commit, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
        }
        topRow.add(title, BorderLayout.CENTER)

        val applyBtn = JButton("Fill Commit Message").apply {
            addActionListener {
                project.getService(CommitBridgeService::class.java).injectCommitMessage(tool.message)
            }
        }
        topRow.add(applyBtn, BorderLayout.EAST)
        box.add(topRow)

        box.add(createWrappedTextArea(tool.message, isSecondary = true))
        return box
    }

    private fun createToolCardContainer(): JPanel {
        return JPanel(VerticalLayout(JBUI.scale(4))).apply {
            background = JBColor.namedColor("EditorPane.background", UIUtil.getPanelBackground())
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true),
                JBUI.Borders.empty(6, 8)
            )
        }
    }

    private fun createWrappedTextArea(text: String, isSecondary: Boolean = false): JBTextArea {
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = if (isSecondary) JBFont.small() else JBFont.regular()
            foreground = if (isSecondary) {
                JBUI.CurrentTheme.ContextHelp.FOREGROUND
            } else {
                JBUI.CurrentTheme.Label.foreground()
            }
        }
    }

    private fun openEditDiff(action: EditFileTool) {
        scope.launch {
            val resolved = readAction {
                val vf = contextState.resolveFile(action.filePath)
                val targetFile = if (vf != null) {
                    File(vf.path)
                } else {
                    val rawFile = File(action.filePath)
                    if (rawFile.isAbsolute) {
                        rawFile
                    } else {
                        val projectPath = project.guessProjectDir()?.path ?: return@readAction null
                        File(projectPath, action.filePath)
                    }
                }
                val ft = vf?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(action.language)
                val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                Triple(targetFile, vf, ft to doc)
            } ?: return@launch

            val (targetFile, existingVf, typeAndDoc) = resolved
            val (initialFileType, existingDoc) = typeAndDoc

            ApplicationManager.getApplication().invokeLater({
                var virtualFile = existingVf
                var document = existingDoc
                var fileType = initialFileType

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
                                document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                                fileType = virtualFile?.fileType ?: initialFileType
                            } catch (e: Exception) {
                                thisLogger().error("Failed to create file", e)
                            }
                        }
                    } else return@invokeLater
                }

                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = if (document != null) {
                    diffContentFactory.create(project, document!!)
                } else {
                    diffContentFactory.create(project, "", fileType)
                }

                val rightContent = diffContentFactory.create(project, action.code, fileType)
                val title = if (action.explanation.isNotBlank()) {
                    "Apply AI Snippet: ${action.filePath} - ${action.explanation}"
                } else "Apply AI Snippet: ${action.filePath}"

                val request = SimpleDiffRequest(title, leftContent, rightContent, "Local Code", "AI Snippet")
                DiffManager.getInstance().showDiff(project, request)
            }, ModalityState.nonModal())
        }
    }

    private fun executeDeleteTool(action: DeleteFileTool) {
        scope.launch {
            val psiFile = readAction {
                contextState.resolveFile(action.filePath)?.let { PsiManager.getInstance(project).findFile(it) }
            }
            ApplicationManager.getApplication().invokeLater({
                if (psiFile != null && psiFile.isValid) {
                    SafeDeleteHandler.invoke(project, arrayOf(psiFile), true)
                } else {
                    Messages.showErrorDialog("File not found: ${action.filePath}", "Delete Error")
                }
            }, ModalityState.nonModal())
        }
    }

    private fun executeRenameTool(action: RenameFileTool) {
        scope.launch {
            val psiFile = readAction {
                contextState.resolveFile(action.sourcePath)?.let { PsiManager.getInstance(project).findFile(it) }
            }
            val targetName = File(action.targetPath).name
            ApplicationManager.getApplication().invokeLater({
                if (psiFile != null && psiFile.isValid) {
                    RenameProcessor(project, psiFile, targetName, false, false).run()
                } else {
                    Messages.showErrorDialog("Source file not found: ${action.sourcePath}", "Rename Error")
                }
            }, ModalityState.nonModal())
        }
    }
}