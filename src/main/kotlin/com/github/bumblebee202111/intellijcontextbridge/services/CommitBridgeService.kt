package com.github.bumblebee202111.intellijcontextbridge.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.StringSelection
import java.lang.ref.WeakReference
import java.util.ArrayDeque

@Service(Service.Level.PROJECT)
class CommitBridgeService(private val project: Project) {
    private var lastCommitPanel: WeakReference<CommitMessageI>? = null

    fun registerPanel(panel: CommitMessageI) {
        lastCommitPanel = WeakReference(panel)
    }

    fun injectCommitMessage(message: String) {
        ApplicationManager.getApplication().invokeLater {
            var panel = lastCommitPanel?.get()

            // 1. Try the captured weak reference first
            if (panel != null) {
                try {
                    panel.setCommitMessage(message)
                    return@invokeLater
                } catch (e: Exception) {
                    panel = null
                }
            }

            // 2. Downward Component Search across candidate Tool Windows ("Commit", "Version Control")
            try {
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val candidateWindowIds = listOf("Commit", "Version Control")

                for (windowId in candidateWindowIds) {
                    val toolWindow = toolWindowManager.getToolWindow(windowId) ?: continue

                    // Recursively inspect child hierarchy
                    val found = findDescendantOfType<CommitMessageI>(toolWindow.component)
                        ?: toolWindow.contentManager.contents.firstNotNullOfOrNull { content ->
                            findDescendantOfType<CommitMessageI>(content.component)
                        }

                    if (found != null) {
                        found.setCommitMessage(message)
                        lastCommitPanel = WeakReference(found)
                        return@invokeLater
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("CommitBridgeService: Downward component search failed", e)
            }

            // 3. Fallback: Copy to clipboard and notify the user
            CopyPasteManager.getInstance().setContents(StringSelection(message))
            Messages.showInfoMessage(
                project,
                "Commit panel not found. The generated commit message has been copied to your clipboard.",
                "Commit Message Generated"
            )
        }
    }

    private inline fun <reified T> findDescendantOfType(root: Component?): T? {
        if (root == null) return null
        val queue = ArrayDeque<Component>()
        queue.add(root)

        while (!queue.isEmpty()) {
            val current = queue.poll()
            if (current is T) return current
            if (current is Container) {
                for (child in current.components) {
                    if (child != null) {
                        queue.add(child)
                    }
                }
            }
        }
        return null
    }
}