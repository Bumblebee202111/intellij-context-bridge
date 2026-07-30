package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.parser.ToolCallParser
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.DiffReceiverPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.SessionHistoryPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ContextBridgeToolWindow(private val project: Project) {

    fun getContent(): JComponent {
        try {
            val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
            server.start()

            val tabbedPane = JBTabbedPane()

            val composerPanel = ContextComposerPanel(project)
            val receiverPanel = DiffReceiverPanel(project)
            val historyPanel = SessionHistoryPanel(project) {
                composerPanel.refreshUi()
            }

            tabbedPane.addTab("1. Send Context", composerPanel.content)
            tabbedPane.addTab("2. Apply Diffs", receiverPanel.content)
            tabbedPane.addTab("3. Session History", historyPanel.content)

            tabbedPane.addChangeListener {
                if (tabbedPane.selectedIndex == 0) composerPanel.refreshUi()
                if (tabbedPane.selectedIndex == 2) historyPanel.refresh()
            }

            server.onMessageReceived = { markdownText ->
                SwingUtilities.invokeLater {
                    val toolCall = ToolCallParser.parse(markdownText)
                    if (toolCall != null && toolCall.name == "read_file") {
                        tabbedPane.selectedIndex = 0
                        composerPanel.handleReadFileToolCall(toolCall.paths, toolCall.reason)
                    } else {
                        tabbedPane.selectedIndex = 1
                        receiverPanel.handleIncomingMarkdown(markdownText)
                    }
                }
            }

            return tabbedPane

        } catch (e: Throwable) {
            val errorPanel = JPanel(BorderLayout())
            val errorArea = JTextArea("CRASH DETECTED:\n\n${e.stackTraceToString()}").apply {
                isEditable = false
                foreground = JBColor.RED
            }
            errorPanel.add(JBScrollPane(errorArea), BorderLayout.CENTER)
            return errorPanel
        }
    }
}