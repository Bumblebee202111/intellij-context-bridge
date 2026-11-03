package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.server.BridgeSessionService
import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ContextBridgeToolWindow(private val project: Project) : Disposable {

    fun getContent(): JComponent {
        try {
            val bridgeSession = project.service<BridgeSessionService>()

            val composerPanel = ContextComposerPanel(project)
            Disposer.register(this, composerPanel)

            bridgeSession.addMessageListener(this) { markdownText ->
                SwingUtilities.invokeLater {
                    composerPanel.processIncomingMarkdown(markdownText, fromWebSocket = true)
                }
            }

            return composerPanel.content

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