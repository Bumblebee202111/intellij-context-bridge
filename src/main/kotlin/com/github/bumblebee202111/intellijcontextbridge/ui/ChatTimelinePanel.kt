package com.github.bumblebee202111.intellijcontextbridge.ui

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
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.Rectangle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities

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

    data class User(val turn: UserTurn) : TimelineItem {
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
    private val onStageReadFiles: (paths: List<String>) -> Unit,
    private val onSendRequestedFiles: (paths: List<String>) -> Unit,
    private val onEditTurn: (turn: UserTurn) -> Unit,
    private val onHistoryChanged: () -> Unit
) {
    private val contextState = project.service<ContextState>()
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
            userTurns.forEach { turn ->
                add(TimelineItem.User(turn))
            }
            modelTurns.forEach { turn ->
                add(TimelineItem.Model(turn))
            }
        }.sortedWith(compareBy<TimelineItem> { it.timestamp }.thenBy { it.sortOrder })

        for (item in items) {
            when (item) {
                is TimelineItem.User -> timelineContainer.add(buildUserTurnCard(item.turn))
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

    private fun buildUserTurnCard(turn: UserTurn): JComponent {
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

        val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false

            val editLink = ActionLink("Edit") {
                onEditTurn(turn)
            }.apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                toolTipText = "Restore this turn's prompt and context to the composer"
            }

            val deleteLink = ActionLink("Delete") {
                contextState.removeTurn(turn.id)
                refresh(scrollToBottom = false)
                onHistoryChanged()
            }.apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                toolTipText = "Delete this turn from session history"
            }

            add(editLink)
            add(deleteLink)
        }

        header.add(leftHeader, BorderLayout.WEST)
        header.add(rightHeader, BorderLayout.EAST)
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
            if (!expandedContextTurns.add(turn.id)) {
                expandedContextTurns.remove(turn.id)
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
                if (!rawModeTurns.add(turn.id)) {
                    rawModeTurns.remove(turn.id)
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
                    onStageReadFiles(paths)
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
        val fileName = File(tool.filePath).name
        val fileTypeIcon = FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.Actions.Edit

        val diffBtn = JButton(if (isViewed) "✓ Diff Opened" else "Open Diff", AllIcons.Actions.Diff).apply {
            addActionListener {
                viewedEditTools.add(toolKey)
                text = "✓ Diff Opened"
                IdeToolExecutor.openEditDiff(project, contextState, tool)
            }
        }

        return buildActionToolCard(
            title = tool.filePath,
            icon = fileTypeIcon,
            button = diffBtn,
            details = tool.explanation
        )
    }

    private fun buildDeleteToolCard(tool: DeleteFileTool): JComponent {
        val deleteBtn = JButton("Safe Delete...").apply {
            addActionListener {
                IdeToolExecutor.executeDeleteTool(project, contextState, tool)
            }
        }

        return buildActionToolCard(
            title = "Delete ${tool.filePath}",
            icon = AllIcons.Actions.GC,
            button = deleteBtn,
            details = tool.explanation,
            titleColor = JBUI.CurrentTheme.Label.errorForeground()
        )
    }

    private fun buildRenameToolCard(tool: RenameFileTool): JComponent {
        val renameBtn = JButton("Rename...").apply {
            addActionListener {
                IdeToolExecutor.executeRenameTool(project, contextState, tool)
            }
        }

        return buildActionToolCard(
            title = "${tool.sourcePath} → ${tool.targetPath}",
            icon = AllIcons.Actions.RefactoringBulb,
            button = renameBtn,
            details = tool.explanation
        )
    }

    private fun buildCommitToolCard(tool: FillCommitMessageTool): JComponent {
        val applyBtn = JButton("Fill Commit Message").apply {
            addActionListener {
                project.service<CommitBridgeService>().injectCommitMessage(tool.message)
            }
        }

        return buildActionToolCard(
            title = "Commit Message",
            icon = AllIcons.Actions.Commit,
            button = applyBtn,
            details = tool.message
        )
    }

    private fun buildActionToolCard(
        title: String,
        icon: Icon,
        button: JButton,
        details: String = "",
        titleColor: Color? = null
    ): JComponent {
        val box = createToolCardContainer()
        val topRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }

        val titleLabel = JBLabel(title, icon, JBLabel.LEFT).apply {
            font = JBFont.regular().asBold()
            if (titleColor != null) {
                foreground = titleColor
            }
        }
        topRow.add(titleLabel, BorderLayout.CENTER)
        topRow.add(button, BorderLayout.EAST)
        box.add(topRow)

        if (details.isNotBlank()) {
            box.add(createWrappedTextArea(details, isSecondary = true))
        }

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
}