package com.github.bumblebee202111.intellijcontextbridge.commands

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class CommandRegistryService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    companion object {
        private val LEADING_COMMAND_REGEX = Regex("^/([a-zA-Z0-9_-]+)(?:\\s+|$)")
    }

    private val commands = ConcurrentHashMap<String, SlashCommand>()

    private val builtInManifest = listOf(
        "plan.md", "impl.md", "debug.md", "review.md", "review-bugs.md", "review-simplify.md", "review-arch.md", "review-product.md", "test.md", "lore.md", "research.md", "commit.md", "cmd.md"
    )

    init {
        refreshCommands()

        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                val projectPath = project.guessProjectDir()?.path ?: return
                val commandsPath = "$projectPath/.contextbridge/commands"
                
                var needsRefresh = false
                for (event in events) {
                    if (event.path.startsWith(commandsPath)) {
                        needsRefresh = true
                        break
                    }
                }

                if (needsRefresh) {
                    refreshCommands()
                }
            }
        })
    }

    fun getCommands(): List<SlashCommand> {
        return commands.values.sortedBy { it.name }
    }

    fun getCommand(name: String): SlashCommand? {
        return commands[name]
    }

    fun parseLeadingCommands(prompt: String): Pair<List<SlashCommand>, String> {
        var remaining = prompt.trimStart()
        val matched = mutableListOf<SlashCommand>()

        while (remaining.isNotEmpty()) {
            val match = LEADING_COMMAND_REGEX.find(remaining) ?: break
            val cmd = getCommand(match.groupValues[1]) ?: break
            matched.add(cmd)
            remaining = remaining.substring(match.range.last + 1).trimStart()
        }

        return matched to remaining
    }

    fun isAtCommandPosition(textBefore: String): Boolean {
        if (textBefore.isNotEmpty() && !textBefore.last().isWhitespace()) return false
        val (_, remaining) = parseLeadingCommands(textBefore)
        return remaining.isEmpty()
    }

    private fun refreshCommands() {
        scope.launch {
            val newCommands = mutableMapOf<String, SlashCommand>()

            for (fileName in builtInManifest) {
                try {
                    val content = CommandRegistryService::class.java.getResourceAsStream("/commands/$fileName")
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

                    if (content != null) {
                        val fallbackName = fileName.removeSuffix(".md")
                        val command = CommandParser.parse(content, fallbackName)
                        if (command != null) newCommands[command.name] = command
                    }
                } catch (e: Exception) {
                    thisLogger().warn("Failed to load built-in command: $fileName", e)
                }
            }

            readAction {
                val projectDir = project.guessProjectDir() ?: return@readAction
                val commandsDir = projectDir.findChild(".contextbridge")?.findChild("commands") ?: return@readAction

                if (commandsDir.isDirectory) {
                    for (child in commandsDir.children) {
                        if (!child.isDirectory && child.extension == "md") {
                            try {
                                val content = VfsUtilCore.loadText(child)
                                val fallbackName = child.nameWithoutExtension
                                val command = CommandParser.parse(content, fallbackName)
                                
                                if (command != null) newCommands[command.name] = command
                            } catch (e: Exception) {
                                thisLogger().warn("Failed to load local command: ${child.name}", e)
                            }
                        }
                    }
                }
            }

            commands.clear()
            commands.putAll(newCommands)
        }
    }

    override fun dispose() {
        commands.clear()
    }
}