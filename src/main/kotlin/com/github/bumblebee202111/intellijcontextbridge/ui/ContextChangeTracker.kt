package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Extracts the IDE event listener boilerplate out of the UI panel.
 * Listens for file system, editor, and document changes to trigger UI refreshes.
 */
class ContextChangeTracker(
    private val project: Project,
    private val contextState: ContextState,
    private val onRefreshNeeded: () -> Unit
) {
    init {
        val connection = project.messageBus.connect()

        // 1. VFS Changes
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                var needsRefresh = false
                val projectDir = project.guessProjectDir() ?: return

                for (event in events) {
                    val file = event.file
                    if (file != null && VfsUtilCore.isAncestor(projectDir, file, false)) {
                        needsRefresh = true
                        break
                    }
                }

                if (needsRefresh) {
                    contextState.fileStates.keys.removeIf { !it.isValid }
                    onRefreshNeeded()
                }
            }
        })

        // 2. Editor Focus Changes
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { onRefreshNeeded() }
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) { onRefreshNeeded() }
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) { onRefreshNeeded() }
        })

        // 3. Document Changes
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document)
                if (file != null && contextState.fileStates.containsKey(file)) {
                    onRefreshNeeded()
                }
            }
        }, project)
    }
}