package com.github.bumblebee202111.intellijcontextbridge.actions

import com.github.bumblebee202111.intellijcontextbridge.context.AiPayload
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.github.bumblebee202111.intellijcontextbridge.services.CommitBridgeService
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.StringWriter

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

        thisLogger().info("GenerateCommitMessageAction: Found ${selectedChanges.size} selected changes.")

        project.service<CommitBridgeService>().registerPanel(commitMessageI)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val basePath = project.basePath
                if (basePath == null) {
                    thisLogger().warn("GenerateCommitMessageAction: basePath is null")
                    return@executeOnPooledThread
                }

                val patches = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.diff.impl.patch.FilePatch>> {
                    IdeaTextPatchBuilder.buildPatch(project, selectedChanges, basePath, false)
                }

                val stringWriter = StringWriter()
                UnifiedDiffWriter.write(project, patches, stringWriter, "\n", null)
                var diffString = stringWriter.toString()

                thisLogger().info("GenerateCommitMessageAction: Diff generated, length = ${diffString.length}")

                // Truncate if too large to prevent token explosion
                if (diffString.length > 50000) {
                    thisLogger().info("GenerateCommitMessageAction: Truncating diff from ${diffString.length} to 50000 characters.")
                    diffString = diffString.take(50000) + "\n\n... [DIFF TRUNCATED]"
                }

                val prompt = """
                    <git_diff>
                    $diffString
                    </git_diff>
                    <user_prompt mode="EDIT">
                    Analyze the provided git diff and generate a concise commit message using the Conventional Commits format.
                    You MUST wrap your response exactly in a `<commit_message>` XML tag.
                    Do NOT output markdown code blocks. Do NOT output explanations.
                    </user_prompt>
                """.trimIndent()

                val payload = AiPayload(
                    systemInstructions = "You are an expert AI coding assistant.",
                    text = prompt,
                    attachments = emptyList(),
                    isCommit = true
                )

                val contextState = project.service<ContextState>()
                val activeTabId = contextState.activeTabId
                val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)

                if (activeTabId != null) {
                    thisLogger().info("GenerateCommitMessageAction: Sending payload to bound tab ID: $activeTabId")
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