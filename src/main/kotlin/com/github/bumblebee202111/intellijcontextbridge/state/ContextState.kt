package com.github.bumblebee202111.intellijcontextbridge.state

import com.github.bumblebee202111.intellijcontextbridge.context.AiContextConfig
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
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
    NONE, SKELETON, FULL, MIXED
}

@Service(Service.Level.PROJECT)
class ContextState(private val project: Project) {
    val fileStates = mutableMapOf<VirtualFile, ContextLevel>()
    val sentFileHashes = mutableMapOf<VirtualFile, Pair<ContextLevel, String>>()
    val promptHistory = mutableListOf<String>()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun getLevel(file: VirtualFile): ContextLevel {
        return fileStates[file] ?: ContextLevel.NONE
    }

    fun applyStateRecursively(file: VirtualFile, level: ContextLevel) {
        if (FileFilterUtil.isIgnored(project, file)) return

        if (file.isDirectory) {
            if (file.children.isEmpty()) {
                // Empty directories can hold state, but are capped at SKELETON
                val cappedLevel = if (level == ContextLevel.FULL) ContextLevel.SKELETON else level
                if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                    fileStates.remove(file)
                } else {
                    fileStates[file] = cappedLevel
                }
            } else {
                fileStates.remove(file) // Populated folders don't hold state, only leaves do
                file.children.forEach { applyStateRecursively(it, level) }
            }
        } else {
            // Leaf nodes cap based on their capability
            val maxLevel = ContextCapabilityUtil.getMaxLevel(file)
            val cappedLevel = if (level == ContextLevel.FULL && maxLevel == ContextLevel.SKELETON) ContextLevel.SKELETON else level

            if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                fileStates.remove(file)
            } else {
                fileStates[file] = cappedLevel
            }
        }
    }

    fun loadConfig() {
        val projectDir = project.guessProjectDir() ?: return
        val configFile = projectDir.findChild(".aicontext")

        val config = if (configFile != null && configFile.exists()) {
            try {
                val configText = VfsUtilCore.loadText(configFile)
                jsonParser.decodeFromString<AiContextConfig>(configText)
            } catch (e: Exception) {
                thisLogger().warn("Failed to parse .aicontext: ${e.message}")
                AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
            }
        } else {
            AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
        }

        config.skeleton.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.SKELETON)
        }

        config.full.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.FULL)
        }
    }

    fun calculateHash(content: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun addPromptToHistory(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        if (promptHistory.isEmpty() || promptHistory.last() != trimmed) {
            promptHistory.add(trimmed)
        }
    }

    fun clear() {
        fileStates.clear()
        sentFileHashes.clear()
    }
}