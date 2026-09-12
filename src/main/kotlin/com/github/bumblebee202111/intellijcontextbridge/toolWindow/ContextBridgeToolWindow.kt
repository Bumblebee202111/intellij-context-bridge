package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.parser.AgentTool
import com.github.bumblebee202111.intellijcontextbridge.parser.ReadFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.ToolParser
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.services.CommitBridgeService
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

                if (markdownText.startsWith("[COMMIT]")) {
                    val commitMessage = markdownText.removePrefix("[COMMIT]").trim()
                    project.getService(CommitBridgeService::class.java).injectCommitMessage(commitMessage)
                    return@addMessageListener
                }

                SwingUtilities.invokeLater {
                    // 1. Parse unified tools
                    val tools = ToolParser.parse(markdownText)
                    val readRequests = tools.filterIsInstance<ReadFileTool>()
                    val mutations = tools.filterIsInstance<AgentTool.Mutation>()

                    var handledReadFile = false
                    if (readRequests.isNotEmpty()) {
                        val allPaths = readRequests.flatMap { it.paths }.distinct()
                        val combinedReason = readRequests.joinToString("\n") { it.explanation }.trim()
                        composerPanel.handleReadFileToolCall(allPaths, combinedReason)
                        handledReadFile = true
                    }

                    // 2. Pass markdown and mutations to the Diff Panel
                    val hasSnippets = receiverPanel.handleIncomingMarkdown(markdownText, mutations)

                    // 3. Smart Tab Switching
                    if (hasSnippets) {
                        tabbedPane.selectedIndex = 1
                    } else if (handledReadFile) {
                        tabbedPane.selectedIndex = 0
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