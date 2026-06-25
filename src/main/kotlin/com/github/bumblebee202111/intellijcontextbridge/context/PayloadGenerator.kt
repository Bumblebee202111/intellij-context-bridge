package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object PayloadGenerator {

    fun generatePayload(project: Project, files: Map<VirtualFile, ContextLevel>, userPrompt: String): String {
        val projectDir = project.guessProjectDir()

        return buildString {
            // 1. System Directives
            appendLine("<system_directives>")
            appendLine("You are an AI coding assistant connected via an IDE bridge.")
            appendLine("Review the `<project_context>` below. Files are marked as either (Skeleton) or (Full).")
            appendLine("Skeleton files contain only public APIs, signatures, and KDocs.")
            appendLine("If you require the complete body of a Skeleton file to fulfill the user's request, you MUST halt your response and strictly output: `REQUEST_FULL: [filepath]`.")
            appendLine("</system_directives>")
            appendLine()

            // 2. Project Context
            appendLine("<project_context>")
            appendLine()

            // Sort files alphabetically by path for deterministic output
            val sortedFiles = files.entries
                .filter { it.value != ContextLevel.NONE }
                .sortedBy { it.key.path }

            for ((file, level) in sortedFiles) {
                // Get clean relative path (e.g., "app/src/main/MainActivity.kt")
                val relativePath = if (projectDir != null) {
                    VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
                } else {
                    file.path
                }

                if (file.isDirectory) {
                    appendLine("### \uD83D\uDCC1 `$relativePath/` (Skeleton)")
                    appendLine("*(Directory contents omitted, use as reference for paths)*")
                    appendLine()
                    continue
                }

                val levelTag = if (level == ContextLevel.FULL) "(Full)" else "(Skeleton)"
                appendLine("### \uD83D\uDCC4 `$relativePath` $levelTag")

                val extension = file.extension?.lowercase() ?: ""
                val lang = getMarkdownLang(extension)

                appendLine("```$lang")

                val content = try {
                    if (level == ContextLevel.FULL) {
                        VfsUtilCore.loadText(file)
                    } else {
                        PsiSkeletonExtractor.extract(project, file)
                    }
                } catch (e: Exception) {
                    "// Error reading file: ${e.message}"
                }

                appendLine(content)
                appendLine("```")
                appendLine()
            }

            appendLine("</project_context>")
            appendLine()

            // 3. User Prompt
            appendLine("<user_prompt>")
            appendLine(userPrompt.trim())
            appendLine("</user_prompt>")
        }
    }

    private fun getMarkdownLang(extension: String): String {
        return when (extension) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "json" -> "json"
            "md" -> "markdown"
            "gradle" -> "groovy"
            "yaml", "yml" -> "yaml"
            "sh" -> "bash"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "html", "htm" -> "html"
            "css" -> "css"
            else -> ""
        }
    }
}