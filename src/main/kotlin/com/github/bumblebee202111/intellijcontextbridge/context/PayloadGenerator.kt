package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.FileStateRecord
import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.Base64

enum class IntentMode {
    ASK, EDIT
}

object PayloadGenerator {

    private fun loadPrompt(filename: String): String {
        return PayloadGenerator::class.java.getResourceAsStream("/prompts/$filename")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException("Missing required prompt resource: $filename")
    }

    fun generatePayload(project: Project, contextState: ContextState, userPrompt: String, intentMode: IntentMode): AiPayload {
        val projectDir = project.guessProjectDir()
        val attachments = mutableListOf<AiAttachment>()

        val dedupedFilesTracker = mutableSetOf<VirtualFile>()

        val currentCache = contextState.getDedupCache()
        val newTurn = UserTurn(prompt = userPrompt)

        // 1. System Instructions
        val systemInstructionsText = loadPrompt("system_base.md")

        val markdownText =  buildString {
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
                if (file.isDirectory) {
                    if (file.children.isNotEmpty()) continue

                    val currentHash = "EMPTY_DIR"
                    val previousRecord = currentCache[relativePath]

                    if (previousRecord != null && previousRecord.level == level && previousRecord.hash == currentHash) {
                        dedupedFilesTracker.add(file)
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)
                        continue
                    }
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)

                    appendLine("### \uD83D\uDCC1 `$relativePath/` (Empty Directory)")
                    appendLine("*(This directory is empty)*\n")
                    continue
                }

                val extension = file.extension?.lowercase() ?: ""
                val isTextFile = ContextCapabilityUtil.textExtensions.contains(extension) || !file.fileType.isBinary

                if (!isTextFile) {
                    val mime = getMimeType(extension)

                    // Retrieve the cached hash instantly
                    val currentHash = contextState.getFileHash(project, file, level)
                    val previousRecord = currentCache[relativePath]

                    if (previousRecord != null && previousRecord.level == level && previousRecord.hash == currentHash) {
                        dedupedFilesTracker.add(file)
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)
                        continue
                    }
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)

                    // --- ATTACHMENT & BINARY HANDLING ---
                    if (level == ContextLevel.COMPLETE) {
                        try {
                            if (mime != "application/octet-stream") {
                                val bytes = file.contentsToByteArray()
                                val base64 = Base64.getEncoder().encodeToString(bytes)
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
                        if (mime != "application/octet-stream") {
                            appendLine("### 🖼️ `$relativePath` (Skeleton Media)\n")
                        } else {
                            appendLine("### 📦 `$relativePath` (Binary File)\n")
                        }
                    }
                    continue
                }

                // --- TEXT/CODE HANDLING (with Deduplication) ---
                var contentToAppend: String?
                var isNonCodeSkeleton = false
                try {
                    // Retrieve the cached hash instantly
                    val currentHash = contextState.getFileHash(project, file, level)
                    val previousRecord = currentCache[relativePath]

                    if (previousRecord != null && previousRecord.level == level && previousRecord.hash == currentHash) {
                        dedupedFilesTracker.add(file)
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)
                        continue
                    } else {
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)

                        // Extract the content because it was NOT deduplicated
                        val extractedText = if (level == ContextLevel.COMPLETE) {
                            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
                        } else {
                            val skeleton = PsiSkeletonExtractor.extract(project, file)
                            if (skeleton == null) {
                                isNonCodeSkeleton = true
                            }
                            skeleton
                        }
                        contentToAppend = extractedText
                    }
                } catch (e: Exception) {
                    contentToAppend = "// Error reading file: ${e.message}"
                }

                if (isNonCodeSkeleton) {
                    appendLine("### \uD83D\uDCC4 `$relativePath` (Skeleton)")
                    appendLine("*(File exists, contents omitted in Skeleton mode)*\n")
                    continue
                }

                if (contentToAppend == null) continue

                val levelTag = if (level == ContextLevel.COMPLETE) "" else " (Skeleton)"
                appendLine("### \uD83D\uDCC4 `$relativePath`$levelTag")
                appendLine("```${getMarkdownLang(extension)}")
                appendLine(contentToAppend)
                appendLine("```\n")
            }

            appendLine("</project_context>")
            appendLine()

            // 3. User Prompt
            val modeString = if (intentMode == IntentMode.ASK) "ASK" else "EDIT"
            appendLine("<user_prompt mode=\"$modeString\">")
            appendLine(userPrompt.trim())
            appendLine("</user_prompt>")
        }

        val payload = AiPayload(
            systemInstructions = systemInstructionsText,
            text = markdownText,
            attachments = attachments
        )
        payload.dedupedFiles = dedupedFilesTracker
        payload.turn = newTurn
        return payload
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