package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.ui.ContextComposerPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class ContextBridgeToolWindow(private val project: Project) : Disposable {

    fun getContent(): JComponent {
        try {
            val composerPanel = ContextComposerPanel(project)
            Disposer.register(this, composerPanel)
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