package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.parser.DeleteFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.EditFileTool
import com.github.bumblebee202111.intellijcontextbridge.parser.RenameFileTool
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffChange
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import kotlinx.coroutines.launch
import java.awt.Point
import java.io.File

object IdeToolExecutor {

    private val PLACEHOLDER_LINE_REGEX = Regex(
        """^\s*(?://+\s*\.\.\..*|/\*\s*\.\.\..*\*/|<!--\s*\.\.\..*-->|#+\s*\.\.\..*|\.\.\.)\s*$"""
    )

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
                request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(createApplyVisibleChangesAction()))
                DiffManager.getInstance().showDiff(project, request)
            }, ModalityState.nonModal())
        }
    }

    private fun createApplyVisibleChangesAction(): AnAction {
        return object : DumbAwareAction(
            "Apply Visible",
            "Apply visible changes on the current screen, skipping '// ...' placeholders",
            AllIcons.Actions.Checked
        ) {
            init {
                templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val viewer = e.getData(DiffDataKeys.DIFF_VIEWER) as? SimpleDiffViewer
                e.presentation.isEnabledAndVisible =
                    viewer != null && DiffUtil.isEditable(viewer.editor1) && getApplicableVisibleChanges(viewer).isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                val viewer = e.getData(DiffDataKeys.DIFF_VIEWER) as? SimpleDiffViewer ?: return
                if (!DiffUtil.isEditable(viewer.editor1)) return
                val changesToApply = getApplicableVisibleChanges(viewer)
                if (changesToApply.isEmpty()) return

                DiffUtil.executeWriteCommand(
                    viewer.editor1.document,
                    e.project,
                    "Apply Visible AI Changes"
                ) {
                    for (change in changesToApply.asReversed()) {
                        if (change.isValid) {
                            viewer.replaceChange(change, Side.RIGHT)
                        }
                    }
                }
            }
        }
    }

    private fun getApplicableVisibleChanges(viewer: SimpleDiffViewer): List<SimpleDiffChange> {
        val allChanges = viewer.diffChanges
        if (allChanges.isEmpty()) return emptyList()

        val leftRange = getVisibleLineRange(viewer.editor1)
        val rightRange = getVisibleLineRange(viewer.editor2)
        val rightDoc = viewer.editor2.document

        return allChanges.filter { change ->
            change.isValid &&
                !hasPlaceholderLine(rightDoc, change) &&
                isChangeVisibleInViewport(change, leftRange, rightRange)
        }
    }

    private fun isChangeVisibleInViewport(
        change: SimpleDiffChange,
        leftVisible: IntRange,
        rightVisible: IntRange
    ): Boolean {
        return isRangeVisible(change.getStartLine(Side.LEFT), change.getEndLine(Side.LEFT), leftVisible) &&
            isRangeVisible(change.getStartLine(Side.RIGHT), change.getEndLine(Side.RIGHT), rightVisible)
    }

    private fun isRangeVisible(startLine: Int, endLine: Int, visible: IntRange): Boolean {
        val lastLine = if (endLine > startLine) endLine - 1 else startLine
        return (startLine >= visible.first && lastLine <= visible.last) ||
            (startLine <= visible.first && lastLine >= visible.last)
    }

    private fun getVisibleLineRange(editor: Editor): IntRange {
        val visibleArea = editor.scrollingModel.visibleArea
        if (visibleArea.height <= 0) return 0..editor.document.lineCount
        val startLine = editor.xyToLogicalPosition(Point(visibleArea.x, visibleArea.y)).line
        val bottomY = (visibleArea.y + visibleArea.height - 1).coerceAtLeast(visibleArea.y)
        val endLine = editor.xyToLogicalPosition(Point(visibleArea.x, bottomY)).line
        return startLine..endLine
    }

    private fun hasPlaceholderLine(doc: Document, change: SimpleDiffChange): Boolean {
        val startLine = change.getStartLine(Side.RIGHT)
        val endLine = change.getEndLine(Side.RIGHT)
        for (line in startLine until endLine) {
            if (line < 0 || line >= doc.lineCount) continue
            val text = getLineText(doc, line)
            if (PLACEHOLDER_LINE_REGEX.matches(text)) return true
        }
        return false
    }

    private fun getLineText(doc: Document, line: Int): String {
        val startOffset = doc.getLineStartOffset(line)
        val endOffset = doc.getLineEndOffset(line)
        return doc.getText(TextRange(startOffset, endOffset))
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