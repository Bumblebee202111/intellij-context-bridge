package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore

object PayloadGenerator {

    fun generatePayload(project: Project, contextState: ContextState, userPrompt: String): String {
        val projectDir = project.guessProjectDir()

        return buildString {
            // 1. System Directives
            appendLine("<system_directives>")
            appendLine("You are an AI coding assistant connected via a stateful IDE bridge. Adhere strictly to this protocol:")
            appendLine("1. **Context Memory**: Files provided in previous prompts may be omitted from the current `<project_context>` if they are unchanged. Rely on your conversation history.")
            appendLine("2. **Skeleton Files**: Files marked as `(Skeleton)` contain only structural APIs and comments. Internal logic and method bodies are explicitly stripped.")
            appendLine("3. **REQUEST_FULL Protocol**: If you must read or modify the internal logic of a `(Skeleton)` file to fulfill the user's request, you MUST halt your response immediately and strictly output: `REQUEST_FULL: [filepath]`. Do not hallucinate or guess the missing bodies. Wait for the user to provide the Full file.")
            appendLine("</system_directives>")
            appendLine()

            // 2. Project Context
            appendLine("<project_context>")
            appendLine()

            // Sort files alphabetically by path for deterministic output
            val sortedFiles = contextState.fileStates.entries
                .filter { it.value != ContextLevel.NONE }
                .sortedBy { it.key.path }

            for ((file, level) in sortedFiles) {
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

                // --- UNIVERSAL DEDUPLICATION LOGIC ---
                var contentToAppend: String?

                try {
                    // 1. Extract the content based on the level
                    val extractedText = if (level == ContextLevel.FULL) {
                        VfsUtilCore.loadText(file)
                    } else {
                        PsiSkeletonExtractor.extract(project, file)
                    }

                    // 2. Hash the extracted text
                    val currentHash = contextState.calculateHash(extractedText)
                    val previousRecord = contextState.sentFileHashes[file]

                    // 3. Compare Level AND Hash
                    if (previousRecord != null && previousRecord.first == level && previousRecord.second == currentHash) {
                        // The exact same content was already sent at this level. Omit entirely!
                        continue
                    } else {
                        // It's new, or the level changed (e.g., Skeleton -> Full), or the content changed.
                        contextState.sentFileHashes[file] = Pair(level, currentHash)
                        contentToAppend = extractedText
                    }
                } catch (e: Exception) {
                    contentToAppend = "// Error reading file: ${e.message}"
                }

                // --- APPEND TO PAYLOAD ---
                val levelTag = if (level == ContextLevel.FULL) "(Full)" else "(Skeleton)"
                appendLine("### \uD83D\uDCC4 `$relativePath` $levelTag")

                val extension = file.extension?.lowercase() ?: ""
                val lang = getMarkdownLang(extension)

                appendLine("```$lang")
                appendLine(contentToAppend)
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