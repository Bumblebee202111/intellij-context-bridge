package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.SwingConstants

class ContextBridgeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contextBridgeWindow = ContextBridgeToolWindow()
        val content = ContentFactory.getInstance().createContent(contextBridgeWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class ContextBridgeToolWindow {
        fun getContent() = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            // A temporary placeholder until we build the JTree in Step 4
            val label = JBLabel("Context Bridge File Tree will go here", SwingConstants.CENTER)
            add(label, BorderLayout.CENTER)
        }
    }
}