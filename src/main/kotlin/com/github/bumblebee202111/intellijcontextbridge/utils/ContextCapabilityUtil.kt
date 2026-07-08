package com.github.bumblebee202111.intellijcontextbridge.utils

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.intellij.openapi.vfs.VirtualFile

object ContextCapabilityUtil {
    val textExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "md", "gradle", "yaml", "yml",
        "sh", "py", "js", "ts", "html", "htm", "css", "txt", "csv", "properties", "pro"
    )

    fun isSupportedMedia(extension: String): Boolean {
        return when (extension.lowercase()) {
            "png", "jpg", "jpeg", "webp", "heic", "pdf", "mp4", "mp3", "wav" -> true
            else -> false
        }
    }

    /**
     * Determines the maximum allowed context level for a specific file.
     */
    fun getMaxLevel(file: VirtualFile): ContextLevel {
        if (file.isDirectory) return ContextLevel.SKELETON

        val extension = file.extension?.lowercase() ?: ""
        val isTextFile = textExtensions.contains(extension) || !file.fileType.isBinary

        if (isTextFile) return ContextLevel.FULL
        if (isSupportedMedia(extension)) return ContextLevel.FULL

        return ContextLevel.SKELETON // Opaque binaries
    }

    /**
     * Cycles to the next logical state based on the file's maximum capability.
     * Left-click/Spacebar toggles active states ONLY. It never cycles to NONE.
     */
    fun getNextLevel(current: ContextLevel, maxLevel: ContextLevel): ContextLevel {
        return if (maxLevel == ContextLevel.SKELETON) {
            when (current) {
                ContextLevel.NONE, ContextLevel.MIXED -> ContextLevel.SKELETON
                ContextLevel.SKELETON -> ContextLevel.SKELETON // Never cycle to NONE
                ContextLevel.FULL -> ContextLevel.SKELETON // Fallback
            }
        } else {
            when (current) {
                ContextLevel.NONE, ContextLevel.MIXED -> ContextLevel.FULL
                ContextLevel.FULL -> ContextLevel.SKELETON
                ContextLevel.SKELETON -> ContextLevel.FULL // Cycle back to FULL, never NONE
            }
        }
    }
}