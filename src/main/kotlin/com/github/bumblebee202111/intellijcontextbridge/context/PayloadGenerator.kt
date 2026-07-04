package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.Base64

object PayloadGenerator {

    // List of extensions we treat as text. Everything else is treated as a binary/attachment.
    private val textExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "md", "gradle", "yaml", "yml",
        "sh", "py", "js", "ts", "html", "htm", "css", "txt", "csv", "properties", "pro"
    )

    fun generatePayload(project: Project, contextState: ContextState, userPrompt: String): AiPayload {
        val projectDir = project.guessProjectDir()
        val attachments = mutableListOf<AiAttachment>()

        val markdownText =  buildString {
            // 1. System Directives
            appendLine("<system_directives>")
            appendLine("You are an AI coding assistant connected via a stateful IDE bridge. Adhere strictly to this protocol:")
            appendLine("1. **Context Memory**: Files provided in previous prompts may be omitted from the current `<project_context>` if they are unchanged. Rely on your conversation history.")
            appendLine("2. **Skeleton Files**: Files marked as `(Skeleton)` contain only structural APIs and comments. Internal logic and method bodies are explicitly stripped.")
            appendLine("3. **REQUEST_FULL Protocol**: If you must read or modify the internal logic of a `(Skeleton)` file to fulfill the user's request, you MUST halt your response immediately and strictly output: `REQUEST_FULL: [filepath]`. Do not hallucinate or guess the missing bodies. Wait for the user to provide the Full file.")
            appendLine("4. **Code Generation & Formatting**: ")
            appendLine("   - You MUST precede every code block with its exact file path as a header: `### 📄 path/to/file.ext`")
            appendLine("   - Do NOT output the entire file unless creating a new one.")
            appendLine("   - Output the specific methods/classes you modified. Include a few unchanged surrounding lines (the basic skeleton) to provide context for a reliable side-by-side diff.")
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
                val relativePath = if (projectDir != null) VfsUtilCore.getRelativePath(file, projectDir) ?: file.path else file.path

                // Skip directories ONLY if they have children (non-empty).
                // Empty directories are kept to preserve project structure intent.
                if (file.isDirectory) {
                    if (file.children.isNotEmpty()) continue

                    appendLine("### \uD83D\uDCC1 `$relativePath/` (Empty Directory)")
                    appendLine("*(This directory is empty)*\n")
                    continue
                }

                val extension = file.extension?.lowercase() ?: ""
                val isTextFile = textExtensions.contains(extension) || !file.fileType.isBinary

                if (!isTextFile) {
                    val mime = getMimeType(extension)
                    // --- ATTACHMENT & BINARY HANDLING ---
                    if (level == ContextLevel.FULL) {
                        try {
                            val bytes = file.contentsToByteArray()
                            val base64 = Base64.getEncoder().encodeToString(bytes)
                            val mime = getMimeType(extension)

                            // Only add to attachments if it's supported by AI Studio (Images, Audio, Video, PDF)
                            if (mime != "application/octet-stream") {
                                val bytes = file.contentsToByteArray()
                                val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                                attachments.add(AiAttachment(file.name, mime, base64))
                                appendLine("### 🖼️ `$relativePath` (Attached Media)")
                                appendLine("*(This file has been attached to the prompt natively)*\n")
                            } else {
                                appendLine("### 📦 `$relativePath` (Binary File)")
                                appendLine("*(Opaque binary file, contents excluded)*\n")
                            }
                        } catch (e: Exception) {
                            appendLine("### ❌ `$relativePath` (Error reading file: ${e.message})\n")
                        }
                    } else {
                        // SKELETON MODE
                        if (mime != "application/octet-stream") {
                            appendLine("### 🖼️ `$relativePath` (Skeleton Media)\n")
                        } else {
                            appendLine("### 📦 `$relativePath` (Binary File)\n")
                        }
                    }
                    continue
                }

                // --- TEXT/CODE HANDLING (with Deduplication) ---
                var contentToAppend: String? = null
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
                        continue // Omit unchanged file
                    } else {
                        contextState.sentFileHashes[file] = Pair(level, currentHash)
                        contentToAppend = extractedText
                    }
                } catch (e: Exception) {
                    contentToAppend = "// Error reading file: ${e.message}"
                }

                if (contentToAppend == null) continue

                val levelTag = if (level == ContextLevel.FULL) "(Full)" else "(Skeleton)"
                appendLine("### \uD83D\uDCC4 `$relativePath` $levelTag")
                appendLine("```${getMarkdownLang(extension)}")
                appendLine(contentToAppend)
                appendLine("```\n")
            }

            appendLine("</project_context>")
            appendLine()

            // 3. User Prompt
            appendLine("<user_prompt>")
            appendLine(userPrompt.trim())
            appendLine("</user_prompt>")
        }

        return AiPayload(text = markdownText, attachments = attachments)
    }

    private fun getMimeType(extension: String): String {
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream" // Unsupported binary
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