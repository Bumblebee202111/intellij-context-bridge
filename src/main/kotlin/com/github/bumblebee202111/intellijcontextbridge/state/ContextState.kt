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
    // Stores the selected state for each file
    val fileStates = mutableMapOf<VirtualFile, ContextLevel>()

    // Stores the hash of the file content last time it was sent as FULL
    val sentFullFileHashes = mutableMapOf<VirtualFile, String>()

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

    // Helper to calculate MD5 hash of file content
    fun calculateHash(content: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun clear() {
        fileStates.clear()
        sentFullFileHashes.clear()
    }
}