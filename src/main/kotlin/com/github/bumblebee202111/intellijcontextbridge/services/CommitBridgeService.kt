package com.github.bumblebee202111.intellijcontextbridge.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import java.lang.ref.WeakReference

@Service(Service.Level.PROJECT)
class CommitBridgeService(private val project: Project) {
    private var lastCommitPanel: WeakReference<CommitMessageI>? = null

    fun registerPanel(panel: CommitMessageI) {
        lastCommitPanel = WeakReference(panel)
    }

    fun injectCommitMessage(message: String) {
        val panel = lastCommitPanel?.get() ?: return
        ApplicationManager.getApplication().invokeLater {
            panel.setCommitMessage(message)
        }
    }
}