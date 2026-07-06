package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.DiffReceiverPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import javax.swing.SwingUtilities

class ContextBridgeToolWindow(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

    fun getContent(): JComponent {
        // Initialize State and Server
        contextState.loadConfig()
        server.start()

        val tabbedPane = com.intellij.ui.components.JBTabbedPane()

        val composerPanel = ContextComposerPanel(project)
        val receiverPanel = DiffReceiverPanel(project)

        tabbedPane.addTab("1. Send Context", composerPanel.content)
        tabbedPane.addTab("2. Apply Diffs", receiverPanel.content)

        // Route incoming WebSocket messages directly to the DiffReceiverPanel
        server.onMessageReceived = { markdownText ->
            SwingUtilities.invokeLater {
                tabbedPane.selectedIndex = 1
                receiverPanel.handleIncomingMarkdown(markdownText)
            }
        }

        return tabbedPane
    }
}