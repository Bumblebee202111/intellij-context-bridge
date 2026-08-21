package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.parser.ToolCallParser
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.DiffReceiverPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.SessionHistoryPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ContextBridgeToolWindow(private val project: Project) : Disposable {

    fun getContent(): JComponent {
        try {
            val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
            val contextState = project.getService(ContextState::class.java)
            server.start()

            val tabbedPane = JBTabbedPane()

            val composerPanel = ContextComposerPanel(project)
            Disposer.register(this, composerPanel)

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

            server.addMessageListener(this) { tabId, markdownText ->
                if (tabId != contextState.activeTabId) return@addMessageListener

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

    override fun dispose() {
        // Cleaned up automatically by Disposer hierarchy
    }
}