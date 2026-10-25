package com.github.bumblebee202111.intellijcontextbridge.context.core

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandRegistryService
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.FileStateRecord
import com.github.bumblebee202111.intellijcontextbridge.state.IntentMode
import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.vcs.VcsUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.Base64

object PayloadGenerator {

    private val FILE_MENTION_REGEX = Regex("(?:^|\\s)@([a-zA-Z0-9_./-]+)")

    private fun loadPrompt(filename: String): String {
        return PayloadGenerator::class.java.getResourceAsStream("/prompts/$filename")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException("Missing required prompt resource: $filename")
    }

    private fun getRelativePath(file: VirtualFile, projectDir: VirtualFile?): String {
        return if (projectDir != null) {
            VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
        } else {
            file.path
        }
    }

    fun getFileHash(project: Project, contextState: ContextState, file: VirtualFile, level: ContextLevel): String {
        ProgressManager.checkCanceled()
        if (file.isDirectory) return "EMPTY_DIR"
        contextState.getCachedHash(file, level)?.let { return it }

        val hash = if (ContextCapabilityUtil.isTextFile(file)) {
            val extractedText = extractTextContent(project, file, level) ?: "OMITTED_NON_CODE_SKELETON"
            contextState.calculateHash(extractedText)
        } else if (level == ContextLevel.COMPLETE) {
            contextState.calculateHash("${file.modificationStamp}_${file.length}")
        } else {
            "OMITTED_BINARY_SKELETON"
        }

        return contextState.cacheHash(file, level, hash)
    }

    fun isFileDeduped(
        project: Project,
        projectDir: VirtualFile?,
        contextState: ContextState,
        file: VirtualFile,
        level: ContextLevel,
        dedupCache: Map<String, FileStateRecord>
    ): Boolean {
        if (projectDir == null) return false
        val relativePath = getRelativePath(file, projectDir)
        val cachedRecord = dedupCache[relativePath] ?: return false
        if (cachedRecord.level != level) return false

        return try {
            getFileHash(project, contextState, file, level) == cachedRecord.hash
        } catch (e: Exception) {
            false
        }
    }

    private fun extractTextContent(project: Project, file: VirtualFile, level: ContextLevel): String? {
        return if (level == ContextLevel.COMPLETE) {
            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
        } else {
            PsiSkeletonExtractor.extract(project, file)
        }
    }

    suspend fun generatePayload(
        project: Project,
        contextState: ContextState,
        userPrompt: String,
        intentMode: IntentMode,
        isCommit: Boolean = false,
        dynamicVariables: Map<String, String> = emptyMap()
    ): PayloadGenerationResult {
        val projectDir = project.guessProjectDir()
        val attachments = mutableListOf<AiAttachment>()

        val currentCache = contextState.getDedupCache()
        val newTurn = UserTurn(prompt = userPrompt)

        // 1. System Instructions
        val systemInstructionsText = loadPrompt("system_base.md")

        val commandRegistry = project.service<CommandRegistryService>()
        val (rawCommands, remainingPrompt) = commandRegistry.parseLeadingCommands(userPrompt)
        val appliedCommands = rawCommands.map { cmd ->
            if (cmd.promptBody.contains("{{vcs_diff}}")) {
                val diff = dynamicVariables["vcs_diff"] ?: readAction { VcsUtil.getProjectDiff(project) }
                cmd.copy(promptBody = cmd.promptBody.replace("{{vcs_diff}}", diff))
            } else {
                cmd
            }
        }

        if (projectDir != null && remainingPrompt.contains('@')) {
            readAction {
                for (match in FILE_MENTION_REGEX.findAll(remainingPrompt)) {
                    ProgressManager.checkCanceled()
                    val rawPath = match.groupValues[1].trimEnd('.', ',', ':', ';', ')', ']')
                    if (rawPath.isNotEmpty()) {
                        val mentionedFile = projectDir.findFileByRelativePath(rawPath)
                        if (mentionedFile != null && mentionedFile.exists()) {
                            contextState.applyStateRecursively(mentionedFile, ContextLevel.COMPLETE, checkIgnore = true)
                        }
                    }
                }
            }
        }

        val sortedFiles = contextState.fileStates.entries
            .filter { it.value != ContextLevel.NONE }
            .sortedBy { it.key.path }

        val fileSections = mutableListOf<String>()

        for ((file, level) in sortedFiles) {
            val section = readAction {
                ProgressManager.checkCanceled()
                if (!file.isValid) return@readAction null
                if (file.isDirectory && file.children.isNotEmpty()) return@readAction null

                val relativePath = getRelativePath(file, projectDir)
                val previousRecord = currentCache[relativePath]
                val isText = !file.isDirectory && ContextCapabilityUtil.isTextFile(file)

                val fastHash = if (isText) {
                    contextState.getCachedHash(file, level)
                } else {
                    getFileHash(project, contextState, file, level)
                }

                if (fastHash != null && previousRecord?.level == level && previousRecord.hash == fastHash) {
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, fastHash)
                    return@readAction null
                }

                if (file.isDirectory) {
                    val dirHash = fastHash ?: "EMPTY_DIR"
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, dirHash)
                    return@readAction "### \uD83D\uDCC1 `$relativePath/` (Empty Directory)\n*(This directory is empty)*\n"
                }

                val extension = file.extension?.lowercase() ?: ""

                if (!isText) {
                    val binaryHash = fastHash ?: getFileHash(project, contextState, file, level)
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, binaryHash)
                    val mime = getMimeType(extension)

                    return@readAction if (level == ContextLevel.COMPLETE) {
                        try {
                            if (mime != "application/octet-stream") {
                                val bytes = file.contentsToByteArray()
                                val base64 = Base64.getEncoder().encodeToString(bytes)
                                attachments.add(AiAttachment(file.name, mime, base64))
                                "### 🖼️ `$relativePath` (Attached Media)\n*(This file has been attached to the prompt natively)*\n"
                            } else {
                                "### 📦 `$relativePath` (Binary File)\n*(Opaque binary file, contents excluded)*\n"
                            }
                        } catch (e: Exception) {
                            "### ❌ `$relativePath` (Error reading file: ${e.message})\n"
                        }
                    } else {
                        if (mime != "application/octet-stream") {
                            "### 🖼️ `$relativePath` (Skeleton Media)\n"
                        } else {
                            "### 📦 `$relativePath` (Binary File)\n"
                        }
                    }
                }

                try {
                    val extractedText = extractTextContent(project, file, level)
                    val currentHash = fastHash ?: contextState.cacheHash(
                        file,
                        level,
                        contextState.calculateHash(extractedText ?: "OMITTED_NON_CODE_SKELETON")
                    )
                    newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)

                    if (previousRecord?.level == level && previousRecord.hash == currentHash) {
                        return@readAction null
                    }

                    if (level != ContextLevel.COMPLETE && extractedText == null) {
                        "### \uD83D\uDCC4 `$relativePath` (Skeleton)\n*(File exists, contents omitted in Skeleton mode)*\n"
                    } else if (extractedText != null) {
                        val levelTag = if (level == ContextLevel.COMPLETE) "" else " (Skeleton)"
                        "### \uD83D\uDCC4 `$relativePath`$levelTag\n```${getMarkdownLang(extension)}\n$extractedText\n```\n"
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    val levelTag = if (level == ContextLevel.COMPLETE) "" else " (Skeleton)"
                    "### \uD83D\uDCC4 `$relativePath`$levelTag\n```${getMarkdownLang(extension)}\n// Error reading file: ${e.message}\n```\n"
                }
            }

            if (section != null) {
                fileSections.add(section)
            }
        }

        val markdownText = buildString {
            // 2. Project Context
            appendLine("<project_context>")
            appendLine()

            for (section in fileSections) {
                appendLine(section)
            }

            appendLine("</project_context>")
            appendLine()

            if (appliedCommands.isNotEmpty()) {
                appendLine("<applied_commands>")
                for (cmd in appliedCommands) {
                    appendLine("  <command name=\"${cmd.name}\">")
                    appendLine("    ${cmd.promptBody.trim().replace("\n", "\n    ")}")
                    appendLine("  </command>")
                }
                appendLine("</applied_commands>")
                appendLine()
            }

            // 3. User Prompt
            val modeString = if (intentMode == IntentMode.ASK) "ASK" else "EDIT"
            appendLine("<user_prompt mode=\"$modeString\">")
            if (remainingPrompt.isNotBlank()) {
                appendLine(remainingPrompt)
            }
            appendLine("</user_prompt>")
        }

        val payload = AiPayload(
            systemInstructions = systemInstructionsText,
            text = markdownText,
            attachments = attachments,
            isCommit = isCommit
        )
        return PayloadGenerationResult(
            payload = payload,
            turn = newTurn
        )
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