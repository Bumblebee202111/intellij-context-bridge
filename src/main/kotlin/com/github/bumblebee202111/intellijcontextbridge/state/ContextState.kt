package com.github.bumblebee202111.intellijcontextbridge.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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