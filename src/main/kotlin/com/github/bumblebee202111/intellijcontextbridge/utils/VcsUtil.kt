package com.github.bumblebee202111.intellijcontextbridge.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.StringWriter

object VcsUtil {
    fun getProjectDiff(project: Project, changes: List<Change>? = null): String {
        val basePath = project.basePath ?: return ""
        val targetChanges = changes ?: ChangeListManager.getInstance(project).allChanges.toList()

        if (targetChanges.isEmpty()) return "No changes found."

        return try {
            val patches = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.diff.impl.patch.FilePatch>> {
                IdeaTextPatchBuilder.buildPatch(project, targetChanges, basePath, false)
            }

            val stringWriter = StringWriter()
            UnifiedDiffWriter.write(project, patches, stringWriter, "\n", null)
            var diffString = stringWriter.toString()

            if (diffString.length > 50000) {
                thisLogger().info("VcsUtil: Truncating diff from ${diffString.length} to 50000 characters.")
                diffString = diffString.take(50000) + "\n\n... [DIFF TRUNCATED]"
            }
            diffString
        } catch (e: Exception) {
            thisLogger().error("VcsUtil: Failed to generate diff", e)
            "Error generating diff: ${e.message}"
        }
    }
}