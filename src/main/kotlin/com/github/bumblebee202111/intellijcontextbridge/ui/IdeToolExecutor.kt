package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.parser.DeleteFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.EditFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.RenameFileTool
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import kotlinx.coroutines.launch
import java.io.File

object IdeToolExecutor {

    fun openEditDiff(project: Project, contextState: ContextState, action: EditFileTool) {
        contextState.scope.launch {
            val resolved = readAction {
                val vf = contextState.resolveFile(action.filePath)
                val targetFile = if (vf != null) {
                    File(vf.path)
                } else {
                    val rawFile = File(action.filePath)
                    if (rawFile.isAbsolute) {
                        rawFile
                    } else {
                        val projectPath = project.guessProjectDir()?.path ?: return@readAction null
                        File(projectPath, action.filePath)
                    }
                }
                val ft = vf?.fileType ?: FileTypeManager.getInstance().getFileTypeByExtension(action.language)
                val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
                Triple(targetFile, vf, ft to doc)
            } ?: return@launch

            val (targetFile, existingVf, typeAndDoc) = resolved
            val (initialFileType, existingDoc) = typeAndDoc

            ApplicationManager.getApplication().invokeLater({
                var virtualFile = existingVf
                var document = existingDoc
                var fileType = initialFileType

                if (virtualFile == null) {
                    val result = Messages.showYesNoDialog(
                        project,
                        "File '${action.filePath}' does not exist. Create it?",
                        "Create File",
                        Messages.getQuestionIcon()
                    )
                    if (result == Messages.YES) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            try {
                                val parentDir = VfsUtil.createDirectoryIfMissing(targetFile.parentFile.absolutePath)
                                virtualFile = parentDir?.createChildData(this, targetFile.name)
                                document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                                fileType = virtualFile?.fileType ?: initialFileType
                            } catch (e: Exception) {
                                thisLogger().error("Failed to create file", e)
                            }
                        }
                    } else return@invokeLater
                }

                val diffContentFactory = DiffContentFactory.getInstance()
                val leftContent = if (document != null) {
                    diffContentFactory.create(project, document!!)
                } else {
                    diffContentFactory.create(project, "", fileType)
                }

                val rightContent = diffContentFactory.create(project, action.code, fileType)
                val title = if (action.explanation.isNotBlank()) {
                    "Apply AI Snippet: ${action.filePath} - ${action.explanation}"
                } else "Apply AI Snippet: ${action.filePath}"

                val request = SimpleDiffRequest(title, leftContent, rightContent, "Local Code", "AI Snippet")
                DiffManager.getInstance().showDiff(project, request)
            }, ModalityState.nonModal())
        }
    }

    fun executeDeleteTool(project: Project, contextState: ContextState, action: DeleteFileTool) {
        contextState.scope.launch {
            val psiFile = readAction {
                contextState.resolveFile(action.filePath)?.let { PsiManager.getInstance(project).findFile(it) }
            }
            ApplicationManager.getApplication().invokeLater({
                if (psiFile != null && psiFile.isValid) {
                    SafeDeleteHandler.invoke(project, arrayOf(psiFile), true)
                } else {
                    Messages.showErrorDialog("File not found: ${action.filePath}", "Delete Error")
                }
            }, ModalityState.nonModal())
        }
    }

    fun executeRenameTool(project: Project, contextState: ContextState, action: RenameFileTool) {
        contextState.scope.launch {
            val psiFile = readAction {
                contextState.resolveFile(action.sourcePath)?.let { PsiManager.getInstance(project).findFile(it) }
            }
            val targetName = File(action.targetPath).name
            ApplicationManager.getApplication().invokeLater({
                if (psiFile != null && psiFile.isValid) {
                    RenameProcessor(project, psiFile, targetName, false, false).run()
                } else {
                    Messages.showErrorDialog("Source file not found: ${action.sourcePath}", "Rename Error")
                }
            }, ModalityState.nonModal())
        }
    }
}