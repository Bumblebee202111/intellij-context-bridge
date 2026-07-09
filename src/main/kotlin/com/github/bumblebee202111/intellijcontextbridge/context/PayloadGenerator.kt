package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.FileStateRecord
import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.Base64

object PayloadGenerator {

    fun generatePayload(project: Project, contextState: ContextState, userPrompt: String): AiPayload {
        val projectDir = project.guessProjectDir()
        val attachments = mutableListOf<AiAttachment>()

        val dedupedFilesTracker = mutableSetOf<VirtualFile>()

        val currentCache = contextState.getDedupCache()
        val newTurn = UserTurn(prompt = userPrompt)

        // 1. System Instructions (Separated from the main prompt)
        val systemInstructionsText = buildString {
            appendLine("You are an expert AI coding assistant natively integrated into an IntelliJ IDE. Adhere strictly to these protocols:")
            appendLine()
            appendLine("1. **Context Awareness**: Files are provided as either `(Full)` or `(Skeleton)`. Skeletons have their internal logic stripped. If you need the full logic of a Skeleton file, halt and output: `REQUEST_FULL: [filepath]`.")
            appendLine("2. **When Generating Code**: If your response includes code modifications or new files, you MUST follow these formatting rules:")
            appendLine("   - **Strict Ordering**: Output your comprehensive explanation and reasoning FIRST, followed by the code.")
            appendLine("   - **File Headers**: Precede every markdown code block with its exact file path header: `### \uD83D\uDCC4 path/to/file.ext`.")
            appendLine("   - **No Chatty Code**: Never add conversational comments, `// MODIFIED`, or changelogs inside the code block itself. The code must be clean and ready to compile.")
            appendLine("   - **The Skeleton Patch Protocol**: To ensure the IDE's diff engine aligns correctly, you must output the ENTIRE file structure for modified files.")
            appendLine("     - For methods, classes, or structural blocks you are NOT modifying: Write the exact signature/declaration and replace the body with `// ...` (or language-equivalent comment). Do NOT omit unchanged signatures; they act as structural anchors for the diff viewer.")
            appendLine("     - For unchanged properties, fields, or variables: Leave them exactly as they are. Do not use `// ...` for simple values.")
            appendLine("     - For elements you ARE modifying (or new elements): Write the full updated logic.")
            appendLine()
            appendLine("Example Output:")
            appendLine("I have updated the service to also save users to the database. I kept the caching logic intact to ensure reads remain fast.")
            appendLine()
            appendLine("### \uD83D\uDCC4 src/main/kotlin/com/example/core/UserService.kt")
            appendLine("```kotlin")
            appendLine("package com.example.core")
            appendLine()
            appendLine("import com.example.database.Database")
            appendLine("import com.example.model.User")
            appendLine()
            appendLine("class UserService(private val db: Database) {")
            appendLine("    private val cache = mutableMapOf<String, User>()")
            appendLine()
            appendLine("    fun getUser(id: String): User? {")
            appendLine("        // ...")
            appendLine("    }")
            appendLine()
            appendLine("    fun updateUser(user: User) {")
            appendLine("        cache[user.id] = user")
            appendLine("        db.save(user)")
            appendLine("    }")
            appendLine()
            appendLine("    fun deleteUser(id: String) {")
            appendLine("        // ...")
            appendLine("    }")
            appendLine("}")
            appendLine("```")
        }

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

                    appendLine("### \uD83D\uDCC1 `$relativePath/` (Empty Directory)")
                    appendLine("*(This directory is empty)*\n")
                    continue
                }

                val extension = file.extension?.lowercase() ?: ""
                val isTextFile = ContextCapabilityUtil.textExtensions.contains(extension) || !file.fileType.isBinary

                if (!isTextFile) {
                    val mime = getMimeType(extension)
                    // --- ATTACHMENT & BINARY HANDLING ---
                    if (level == ContextLevel.FULL) {
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
                var contentToAppend: String? = null
                var isNonCodeSkeleton = false
                try {
                    // 1. Extract the content based on the level
                    val extractedText = if (level == ContextLevel.FULL) {
                        VfsUtilCore.loadText(file)
                    } else {
                        val skeleton = PsiSkeletonExtractor.extract(project, file)
                        if (skeleton == null) {
                            isNonCodeSkeleton = true
                        }
                        skeleton
                    }

                    // 2. Hash the extracted text
                    val currentHash = contextState.calculateHash(extractedText ?: "OMITTED_NON_CODE_SKELETON")
                    val previousRecord = currentCache[relativePath]

                    // 3. Compare Level AND Hash
                    if (previousRecord != null && previousRecord.level == level && previousRecord.hash == currentHash) {
                        dedupedFilesTracker.add(file)
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)
                        continue
                    } else {
                        newTurn.sentFiles[relativePath] = FileStateRecord(level, currentHash)
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