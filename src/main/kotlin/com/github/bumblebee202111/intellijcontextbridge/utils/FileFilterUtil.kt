package com.github.bumblebee202111.intellijcontextbridge.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

object FileFilterUtil {
    /**
     * Returns true if the file should be ignored based on .gitignore or IDE exclusion rules.
     */
    fun isIgnored(project: Project, file: VirtualFile): Boolean {
        // 1. Check if it's explicitly excluded by the IDE (e.g., 'build' folders)
        if (ProjectFileIndex.getInstance(project).isExcluded(file)) return true

        // 2. Check if it's ignored by Git (.gitignore)
        if (ChangeListManager.getInstance(project).isIgnoredFile(file)) return true

        // 3. Hide hidden directories (like .git) just to keep the tree clean, but allow hidden files (like .aicontext)
        if (file.isDirectory && file.name.startsWith(".")) return true

        return false
    }
}