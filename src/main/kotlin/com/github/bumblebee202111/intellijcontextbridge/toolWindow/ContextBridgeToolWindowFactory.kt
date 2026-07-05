package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ContextBridgeToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contextBridgeWindow = ContextBridgeToolWindow(project)
        val content = ContentFactory.getInstance().createContent(contextBridgeWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}