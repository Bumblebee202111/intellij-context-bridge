package com.github.bumblebee202111.intellijcontextbridge.state

import com.github.bumblebee202111.intellijcontextbridge.context.AiContextConfig
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class ContextLevel {
    NONE, SKELETON, FULL
}

@Service(Service.Level.PROJECT)
class ContextState(private val project: Project) {
    val fileStates = mutableMapOf<VirtualFile, ContextLevel>()

    val sentFileHashes = mutableMapOf<VirtualFile, Pair<ContextLevel, String>>()

    // Store prompt history
    val promptHistory = mutableListOf<String>()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun getLevel(file: VirtualFile): ContextLevel {
        return fileStates[file] ?: ContextLevel.NONE
    }

    fun setLevel(file: VirtualFile, level: ContextLevel) {
        if (level == ContextLevel.NONE) {
            fileStates.remove(file)
        } else {
            fileStates[file] = level
        }
    }

    // Apply state to a file/folder and all its children
    fun applyStateRecursively(file: VirtualFile, level: ContextLevel) {
        // UNIFIED FILTER: Respect .gitignore and IDE exclusions
        if (FileFilterUtil.isIgnored(project, file)) return

        setLevel(file, level)

        if (file.isDirectory) {
            file.children.forEach { child ->
                applyStateRecursively(child, level)
            }
        }
    }

    // Load defaults from .aicontext
    fun loadConfig() {
        val projectDir = project.guessProjectDir() ?: return
        val configFile = projectDir.findChild(".aicontext")

        val config = if (configFile != null && configFile.exists()) {
            try {
                val configText = VfsUtilCore.loadText(configFile)
                jsonParser.decodeFromString<AiContextConfig>(configText)
            } catch (e: Exception) {
                thisLogger().warn("Failed to parse .aicontext: ${e.message}")
                // Fallback to default if their JSON is malformed
                AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
            }
        } else {
            // Out-of-the-box Default Behavior
            AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
        }

        // 1. Apply Skeletons first
        config.skeleton.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.SKELETON)
        }

        // 2. Apply Full (overrides Skeletons)
        config.full.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.FULL)
        }
        thisLogger().info("Successfully loaded AI Context configuration.")
    }

    fun calculateHash(content: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Add prompt to history safely
    fun addPromptToHistory(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        // Don't add if it's the exact same as the most recent one
        if (promptHistory.isEmpty() || promptHistory.last() != trimmed) {
            promptHistory.add(trimmed)
        }
    }

    fun clear() {
        fileStates.clear()
        sentFileHashes.clear()
        // We intentionally DO NOT clear promptHistory here!
    }
}