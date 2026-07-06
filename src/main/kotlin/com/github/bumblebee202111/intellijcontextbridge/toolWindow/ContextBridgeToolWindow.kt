package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.github.bumblebee202111.intellijcontextbridge.ui.DiffReceiverPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ContextBridgeToolWindow(private val project: Project) {
    private val contextState = project.service<ContextState>()
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

    fun getContent(): JComponent {
        try {
            // Lazy load these inside the try/catch so if they crash, we catch it!
            val contextState = project.service<ContextState>()
            val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
        contextState.loadConfig()
        server.start()

        val tabbedPane = JBTabbedPane()

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

        } catch (e: Throwable) {
            // If the UI ever crashes again, it will print the stack trace on the screen instead of a blank panel!
            val errorPanel = JPanel(BorderLayout())
            val errorArea = JTextArea("CRASH DETECTED:\n\n${e.stackTraceToString()}").apply {
                isEditable = false
                foreground = java.awt.Color.RED
            }
            errorPanel.add(JBScrollPane(errorArea), BorderLayout.CENTER)
            return errorPanel
        }
    }
}