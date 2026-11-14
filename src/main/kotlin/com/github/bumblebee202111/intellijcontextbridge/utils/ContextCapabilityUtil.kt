package com.github.bumblebee202111.intellijcontextbridge.utils

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.intellij.openapi.vfs.VirtualFile

object ContextCapabilityUtil {
    val textExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "md", "gradle", "yaml", "yml",
        "sh", "py", "js", "ts", "vue", "html", "htm", "css", "txt", "csv", "properties", "pro"
    )

    fun isTextFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase() ?: ""
        return textExtensions.contains(extension) || !file.fileType.isBinary
    }

    fun getMediaMimeType(extension: String): String? {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> null
        }
    }

    fun isSupportedMedia(extension: String): Boolean {
        return getMediaMimeType(extension) != null
    }

    /**
     * Determines the maximum allowed context level for a specific file.
     */
    fun getMaxLevel(file: VirtualFile): ContextLevel {
        if (file.isDirectory) return ContextLevel.SKELETON

        val extension = file.extension?.lowercase() ?: ""
        if (isTextFile(file)) return ContextLevel.COMPLETE
        if (isSupportedMedia(extension)) return ContextLevel.COMPLETE

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
                ContextLevel.COMPLETE -> ContextLevel.SKELETON // Fallback
            }
        } else {
            when (current) {
                ContextLevel.NONE, ContextLevel.MIXED -> ContextLevel.COMPLETE
                ContextLevel.COMPLETE -> ContextLevel.SKELETON
                ContextLevel.SKELETON -> ContextLevel.COMPLETE // Cycle back to COMPLETE, never NONE
            }
        }
    }
}