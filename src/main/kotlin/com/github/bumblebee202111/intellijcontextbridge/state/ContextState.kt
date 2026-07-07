package com.github.bumblebee202111.intellijcontextbridge.state

import com.github.bumblebee202111.intellijcontextbridge.context.AiContextConfig
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ContextLevel {
    NONE, SKELETON, FULL, MIXED
}

data class FileStateRecord(
    var level: ContextLevel = ContextLevel.NONE,
    var hash: String = ""
)

data class UserTurn(
    var id: String = UUID.randomUUID().toString(),
    var timestamp: Long = System.currentTimeMillis(),
    var prompt: String = "",
    var sentFiles: MutableMap<String, FileStateRecord> = mutableMapOf()
)

@Service(Service.Level.PROJECT)
@State(name = "ContextBridgeState", storages = [Storage("ContextBridge.xml")])
class ContextState(private val project: Project) : PersistentStateComponent<ContextState.State> {

    class State {
        var turns: MutableList<UserTurn> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // Thread-safe map for background configuration loading and UI rendering
    val fileStates = ConcurrentHashMap<VirtualFile, ContextLevel>()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun getLevel(file: VirtualFile): ContextLevel {
        return fileStates[file] ?: ContextLevel.NONE
    }

    fun applyStateRecursively(file: VirtualFile, level: ContextLevel, checkIgnore: Boolean = true) {
        // Skip slow index operations if we already know the file is valid (e.g., from a UI click)
        if (checkIgnore && FileFilterUtil.isIgnored(project, file)) return

        if (file.isDirectory) {
            if (file.children.isEmpty()) {
                val cappedLevel = if (level == ContextLevel.FULL) ContextLevel.SKELETON else level
                if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                    fileStates.remove(file)
                } else {
                    fileStates[file] = cappedLevel
                }
            } else {
                fileStates.remove(file)
                file.children.forEach { applyStateRecursively(it, level, checkIgnore) }
            }
        } else {
            val maxLevel = ContextCapabilityUtil.getMaxLevel(file)
            val cappedLevel = if (level == ContextLevel.FULL && maxLevel == ContextLevel.SKELETON) ContextLevel.SKELETON else level

            if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                fileStates.remove(file)
            } else {
                fileStates[file] = cappedLevel
            }
        }
    }

    fun loadConfig() {
        val projectDir = project.guessProjectDir() ?: return
        val configFile = projectDir.findChild(".aicontext")

        val config = if (configFile != null && configFile.exists()) {
            try {
                val configText = VfsUtilCore.loadText(configFile)
                jsonParser.decodeFromString<AiContextConfig>(configText)
            } catch (e: Exception) {
                thisLogger().warn("Failed to parse .aicontext: ${e.message}")
                AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
            }
        } else {
            AiContextConfig(skeleton = listOf("."), full = listOf("README.md"))
        }

        config.skeleton.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.SKELETON, checkIgnore = true)
        }

        config.full.forEach { path ->
            val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
            if (file != null && file.exists()) applyStateRecursively(file, ContextLevel.FULL, checkIgnore = true)
        }
    }

    fun calculateHash(content: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun addTurn(turn: UserTurn) {
        myState.turns.add(turn)
    }

    fun removeTurn(turnId: String) {
        myState.turns.removeAll { it.id == turnId }
    }

    fun getLastTurn(): UserTurn? = myState.turns.lastOrNull()

    fun removeLastTurn(): UserTurn? {
        return if (myState.turns.isNotEmpty()) myState.turns.removeLast() else null
    }

    fun getPromptHistory(): List<String> {
        return myState.turns.map { it.prompt }.filter { it.isNotBlank() }.distinct()
    }

    fun getDedupCache(): Map<String, FileStateRecord> {
        val cache = mutableMapOf<String, FileStateRecord>()
        for (turn in myState.turns) {
            for ((path, record) in turn.sentFiles) {
                cache[path] = record
            }
        }
        return cache
    }

    fun clear() {
        fileStates.clear()
        myState.turns.clear()
    }
}