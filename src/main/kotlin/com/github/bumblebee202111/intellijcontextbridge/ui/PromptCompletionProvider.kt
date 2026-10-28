package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandRegistryService
import com.github.bumblebee202111.intellijcontextbridge.commands.SlashCommand
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import javax.swing.Icon

sealed interface PromptCompletionItem : Comparable<PromptCompletionItem> {
    data class Command(val slashCommand: SlashCommand) : PromptCompletionItem {
        override fun compareTo(other: PromptCompletionItem): Int = when (other) {
            is Command -> slashCommand.name.compareTo(other.slashCommand.name)
            is FileMention -> -1
        }
    }

    data class FileMention(
        val file: VirtualFile,
        val relativePath: String,
        val fileName: String,
        val isDirectory: Boolean,
        val icon: Icon
    ) : PromptCompletionItem {
        override fun compareTo(other: PromptCompletionItem): Int = when (other) {
            is Command -> 1
            is FileMention -> relativePath.compareTo(other.relativePath)
        }
    }
}

private class PromptPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean {
        return when {
            myPrefix.startsWith("@") -> {
                if (!name.startsWith("@")) return false
                val query = myPrefix.removePrefix("@")
                val target = name.removePrefix("@")
                query.isEmpty() || target.contains(query, ignoreCase = true)
            }
            myPrefix.startsWith("/") -> {
                name.startsWith("/") && name.startsWith(myPrefix, ignoreCase = true)
            }
            else -> false
        }
    }

    override fun isStartMatch(name: String): Boolean {
        return when {
            myPrefix.startsWith("@") -> {
                if (!name.startsWith("@")) return false
                val query = myPrefix.removePrefix("@")
                val target = name.removePrefix("@")
                query.isEmpty() || target.startsWith(query, ignoreCase = true)
            }
            myPrefix.startsWith("/") -> {
                name.startsWith("/") && name.startsWith(myPrefix, ignoreCase = true)
            }
            else -> false
        }
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher {
        return PromptPrefixMatcher(prefix)
    }
}

class PromptCompletionProvider(
    private val project: Project,
    private val onCommandSelected: (SlashCommand) -> Unit,
    private val onFileMentionSelected: (VirtualFile) -> Unit
) : TextFieldWithAutoCompletionListProvider<PromptCompletionItem>(emptyList()) {

    @Volatile
    private var cachedFileMentionItems: List<PromptCompletionItem.FileMention> = emptyList()

    fun refreshItems() {
        val commandItems = project.service<CommandRegistryService>()
            .getCommands()
            .map { PromptCompletionItem.Command(it) }
        setItems(commandItems + cachedFileMentionItems)
    }

    fun updateFileMentionItems(items: List<PromptCompletionItem.FileMention>) {
        cachedFileMentionItems = items
        refreshItems()
    }

    @Suppress("UnsafeVfsRecursion")
    fun collectFileMentionItems(
        projectDir: VirtualFile?,
        externalRoots: Collection<VirtualFile> = emptyList()
    ): List<PromptCompletionItem.FileMention> {
        val result = mutableListOf<PromptCompletionItem.FileMention>()

        fun addItem(file: VirtualFile, relativePath: String) {
            val isDir = file.isDirectory
            val icon = if (isDir) {
                AllIcons.Nodes.Folder
            } else {
                file.fileType.icon ?: AllIcons.FileTypes.Any_type
            }
            result.add(
                PromptCompletionItem.FileMention(
                    file = file,
                    relativePath = relativePath,
                    fileName = file.name,
                    isDirectory = isDir,
                    icon = icon
                )
            )
        }

        fun visit(dir: VirtualFile, baseDir: VirtualFile, prefix: String = "") {
            ProgressManager.checkCanceled()
            for (child in dir.children) {
                ProgressManager.checkCanceled()
                if (FileFilterUtil.isIgnored(project, child)) continue
                val rel = VfsUtilCore.getRelativePath(child, baseDir) ?: continue
                val fullRel = if (prefix.isEmpty()) rel else "$prefix/$rel"
                addItem(child, fullRel)
                if (child.isDirectory) {
                    visit(child, baseDir, prefix)
                }
            }
        }

        if (projectDir != null && projectDir.isValid) {
            visit(projectDir, projectDir)
        }

        for (extRoot in externalRoots) {
            ProgressManager.checkCanceled()
            if (!extRoot.isValid) continue
            addItem(extRoot, extRoot.name)
            if (extRoot.isDirectory) {
                visit(extRoot, extRoot, prefix = extRoot.name)
            }
        }

        return result
    }

    override fun acceptChar(c: Char): CharFilter.Result? {
        return if (c.isLetterOrDigit() || c == '/' || c == '.' || c == '-' || c == '_' || c == '@') {
            CharFilter.Result.ADD_TO_PREFIX
        } else {
            CharFilter.Result.HIDE_LOOKUP
        }
    }

    override fun createPrefixMatcher(prefix: String): PrefixMatcher {
        return PromptPrefixMatcher(prefix)
    }

    override fun getItems(
        prefix: String?,
        cached: Boolean,
        parameters: CompletionParameters?
    ): Collection<PromptCompletionItem> {
        if (prefix == null) return emptyList()
        return when {
            prefix.startsWith("@") -> {
                super.getItems(prefix, cached, parameters)
                    .filterIsInstance<PromptCompletionItem.FileMention>()
            }
            prefix.startsWith("/") -> {
                val isValidPos = if (parameters != null) {
                    val docText = parameters.originalFile.text
                    val tokenStart = (parameters.offset - prefix.length).coerceAtLeast(0)
                    project.service<CommandRegistryService>()
                        .isAtCommandPosition(docText.substring(0, tokenStart))
                } else {
                    true
                }
                if (isValidPos) {
                    super.getItems(prefix, cached, parameters)
                        .filterIsInstance<PromptCompletionItem.Command>()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    override fun getLookupString(item: PromptCompletionItem): String = when (item) {
        is PromptCompletionItem.Command -> "/${item.slashCommand.name}"
        is PromptCompletionItem.FileMention -> "@${item.fileName}"
    }

    override fun getTailText(item: PromptCompletionItem): String = when (item) {
        is PromptCompletionItem.Command -> " - ${item.slashCommand.description}"
        is PromptCompletionItem.FileMention -> {
            if (item.relativePath.contains('/')) {
                " (${item.relativePath.substringBeforeLast('/')})"
            } else {
                ""
            }
        }
    }

    override fun getTypeText(item: PromptCompletionItem): String = when (item) {
        is PromptCompletionItem.Command -> item.slashCommand.mode.name
        is PromptCompletionItem.FileMention -> if (item.isDirectory) "Folder" else ""
    }

    override fun compare(item1: PromptCompletionItem, item2: PromptCompletionItem): Int =
        item1.compareTo(item2)

    override fun createLookupBuilder(item: PromptCompletionItem): LookupElementBuilder {
        return when (item) {
            is PromptCompletionItem.Command -> {
                super.createLookupBuilder(item)
                    .withIcon(AllIcons.Nodes.Function)
                    .withInsertHandler { context, _ ->
                        val start = context.startOffset
                        val end = context.tailOffset
                        val replacement = "/${item.slashCommand.name} "
                        context.document.replaceString(start, end, replacement)
                        context.editor.caretModel.moveToOffset(start + replacement.length)

                        ApplicationManager.getApplication().invokeLater {
                            onCommandSelected(item.slashCommand)
                        }
                    }
            }

            is PromptCompletionItem.FileMention -> {
                super.createLookupBuilder(item)
                    .withLookupString("@${item.relativePath}")
                    .withIcon(item.icon)
                    .withInsertHandler { context, _ ->
                        val start = context.startOffset
                        val end = context.tailOffset
                        val replacement = "@${item.relativePath} "
                        context.document.replaceString(start, end, replacement)
                        context.editor.caretModel.moveToOffset(start + replacement.length)

                        ApplicationManager.getApplication().invokeLater {
                            onFileMentionSelected(item.file)
                        }
                    }
            }
        }
    }
}