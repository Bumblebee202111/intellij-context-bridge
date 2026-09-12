package com.github.bumblebee202111.intellijcontextbridge.actions

import com.github.bumblebee202111.intellijcontextbridge.context.AiPayload
import com.github.bumblebee202111.intellijcontextbridge.context.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.context.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.services.CommitBridgeService
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.VcsUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GenerateCommitMessageAction : AnAction("Generate AI Commit Message", "Generate commit message using Gemini", AllIcons.Actions.Commit) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            thisLogger().warn("GenerateCommitMessageAction: project is null")
            return
        }

        val commitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (commitMessageI == null) {
            thisLogger().warn("GenerateCommitMessageAction: commitMessageI is null")
            return
        }

        // Extract the checked files using the modern CommitWorkflowHandler
        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val selectedChanges = workflowHandler?.ui?.getIncludedChanges()
            ?: (commitMessageI as? CheckinProjectPanel)?.selectedChanges?.toList()
            ?: e.getData(VcsDataKeys.CHANGES)?.toList()
            ?: emptyList()

        if (selectedChanges.isEmpty()) {
            thisLogger().info("GenerateCommitMessageAction: selectedChanges is empty. No files checked.")
            return
        }

        project.service<CommitBridgeService>().registerPanel(commitMessageI)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val diffString = VcsUtil.getProjectDiff(project, selectedChanges)
                val contextState = project.service<ContextState>()

                val payload = PayloadGenerator.generatePayload(
                    project = project,
                    contextState = contextState,
                    userPrompt = "/commit",
                    intentMode = IntentMode.EDIT,
                    isCommit = true,
                    dynamicVariables = mapOf("vcs_diff" to diffString)
                )

                val activeTabId = contextState.activeTabId
                val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

                if (activeTabId != null) {
                    val jsonString = Json.encodeToString(payload)
                    server.sendToTab(activeTabId, jsonString)
                } else {
                    thisLogger().warn("GenerateCommitMessageAction: activeTabId is null. The user has not bound a tab in the Context Bridge panel.")
                }
            } catch (ex: Exception) {
                thisLogger().error("GenerateCommitMessageAction: Failed to generate or send diff", ex)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val commitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        e.presentation.isEnabledAndVisible = commitMessageI != null
    }
}