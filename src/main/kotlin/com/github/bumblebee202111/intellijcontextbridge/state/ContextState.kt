package com.github.bumblebee202111.intellijcontextbridge.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

enum class ContextLevel {
    NONE, SKELETON, FULL
}

@Service(Service.Level.PROJECT)
class ContextState(private val project: Project) {
    // Stores the selected state for each file
    val fileStates = mutableMapOf<VirtualFile, ContextLevel>()

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

    fun clear() {
        fileStates.clear()
    }
}