package com.github.bumblebee202111.intellijcontextbridge.utils

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

object FileFilterUtil {
    private val EXTERNAL_HEAVY_DIR_NAMES = setOf(
        ".git", ".gradle", ".idea", ".kotlin", ".intellijPlatform",
        ".venv", "venv", "node_modules", "build", "dist", "out", "target",
        "Pods", "DerivedData", ".build", ".dart_tool", ".next", ".nuxt", "__pycache__"
    )

    fun isHiddenFromTree(file: VirtualFile): Boolean {
        if (file.isDirectory && file.name == ".git") return true
        return FileTypeManager.getInstance().isFileIgnored(file)
    }

    fun isProjectExcluded(project: Project, file: VirtualFile): Boolean {
        return ProjectFileIndex.getInstance(project).isExcluded(file)
    }

    fun isExternalHeavyDir(file: VirtualFile): Boolean {
        return file.isDirectory && file.name in EXTERNAL_HEAVY_DIR_NAMES
    }

    fun isBulkExcluded(project: Project, file: VirtualFile, isExternal: Boolean = false): Boolean {
        if (isHiddenFromTree(file)) return true
        return if (isExternal) {
            isExternalHeavyDir(file)
        } else {
            ProjectFileIndex.getInstance(project).isExcluded(file) ||
                ChangeListManager.getInstance(project).isIgnoredFile(file)
        }
    }
}