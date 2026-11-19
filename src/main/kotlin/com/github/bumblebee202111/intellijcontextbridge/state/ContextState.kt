package com.github.bumblebee202111.intellijcontextbridge.state

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandContextAction
import com.github.bumblebee202111.intellijcontextbridge.commands.SlashCommand
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.remove

data class SeedGraphCacheEntry(
    val modificationStamp: Long,
    val outgoingFiles: Set<VirtualFile>,
    val incomingScores: Map<VirtualFile, Int>
)

private data class HashCacheEntry(
    val stamp: Long,
    val level: ContextLevel,
    val hash: String
)

data class StageFilesResult(
    val affectedFiles: Set<VirtualFile>,
    val externalRootsChanged: Boolean
)

@Service(Service.Level.PROJECT)
@State(name = "ContextBridgeState", storages = [Storage("ContextBridge.xml")])
class ContextState(
    private val project: Project,
    val scope: CoroutineScope
) : PersistentStateComponent<ContextState.State> {

    companion object {
        private val PRIMARY_ROOT_MARKERS = setOf(".git", ".idea", ".vscode")
        private val SECONDARY_ROOT_MARKERS = setOf(
            "settings.gradle",
            "settings.gradle.kts",
            "Package.swift",
            "pubspec.yaml",
            "package.json",
            "Cargo.toml",
            "go.mod",
            "pyproject.toml",
            "pom.xml"
        )
        private val SECONDARY_ROOT_SUFFIXES = listOf(".xcodeproj", ".xcworkspace")
        private val USER_COMMON_DIR_NAMES = setOf("Desktop", "Documents", "Downloads")
    }

    class State {
        var turns: MutableList<UserTurn> = CopyOnWriteArrayList()
        var modelTurns: MutableList<ModelTurn> = CopyOnWriteArrayList()
        var externalRootPaths: MutableList<String> = CopyOnWriteArrayList()
        var boundTabTitle: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Ensure thread-safety after deserialization
        if (myState.turns !is CopyOnWriteArrayList) {
            myState.turns = CopyOnWriteArrayList(myState.turns)
        }
        if (myState.modelTurns !is CopyOnWriteArrayList) {
            myState.modelTurns = CopyOnWriteArrayList(myState.modelTurns)
        }
        if (myState.externalRootPaths !is CopyOnWriteArrayList) {
            myState.externalRootPaths = CopyOnWriteArrayList(myState.externalRootPaths)
        }
    }

    // Thread-safe map for background configuration loading and UI rendering
    val fileStates = ConcurrentHashMap<VirtualFile, ContextLevel>()

    // User-attached external files and directories outside the project root
    val externalRoots: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    // Files staged from AI read requests, clipboard paste, drag-and-drop, or external tools
    val stagedFiles: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    private val contextChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    // Transient cache to prevent re-hashing unmodified files during UI renders
    private val hashCache = ConcurrentHashMap<VirtualFile, HashCacheEntry>()

    // Transient cache for per-seed PSI graph results (outgoing imports + incoming ReferencesSearch scores)
    private val seedGraphCache = ConcurrentHashMap<VirtualFile, SeedGraphCacheEntry>()

    @Volatile
    private var isInitialized = false

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun ensureInitialized() {
        if (!isInitialized) {
            restoreExternalRoots()
            restoreFromLastTurn()
            isInitialized = true
        }
    }

    private fun restoreExternalRoots() {
        val fs = LocalFileSystem.getInstance()
        for (path in myState.externalRootPaths) {
            ProgressManager.checkCanceled()
            val file = fs.findFileByPath(path)
            if (file != null && file.isValid && file.exists()) {
                externalRoots.add(file)
            }
        }
        syncExternalRootPaths()
    }

    private fun syncExternalRootPaths() {
        val paths = externalRoots.filter { it.isValid }.map { it.path }.sorted()
        myState.externalRootPaths.clear()
        myState.externalRootPaths.addAll(paths)
    }

    fun removeExternalRoot(file: VirtualFile): Boolean {
        val removed = externalRoots.remove(file)
        if (removed) {
            syncExternalRootPaths()
        }
        return removed
    }

    fun clearExternalRoots(): Boolean {
        if (externalRoots.isEmpty()) return false
        externalRoots.clear()
        myState.externalRootPaths.clear()
        return true
    }

    fun removeStagedFile(file: VirtualFile): Boolean {
        return stagedFiles.remove(file)
    }

    fun clearStagedFiles(): Boolean {
        if (stagedFiles.isEmpty()) return false
        stagedFiles.clear()
        return true
    }

    fun addContextChangeListener(parentDisposable: Disposable, listener: () -> Unit) {
        contextChangeListeners.add(listener)
        Disposer.register(parentDisposable) { contextChangeListeners.remove(listener) }
    }

    fun notifyContextChanged() {
        contextChangeListeners.forEach { it() }
    }

    fun getLevel(file: VirtualFile): ContextLevel {
        return fileStates[file] ?: ContextLevel.NONE
    }

    fun getRelativePath(file: VirtualFile): String {
        val projectDir = project.guessProjectDir()
        return if (projectDir != null) {
            VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
        } else {
            file.path
        }
    }

    fun resolveFile(path: String): VirtualFile? {
        val normalized = path.replace('\\', '/')
        val projectDir = project.guessProjectDir()
        if (projectDir != null) {
            projectDir.findFileByRelativePath(normalized)?.let { return it }
        }
        LocalFileSystem.getInstance().findFileByPath(normalized)?.let { return it }
        for (extRoot in externalRoots) {
            if (!extRoot.isValid) continue
            if (extRoot.name == normalized) return extRoot
            if (extRoot.isDirectory) {
                extRoot.findFileByRelativePath(normalized)?.let { return it }
                if (normalized.startsWith("${extRoot.name}/")) {
                    val subPath = normalized.removePrefix("${extRoot.name}/")
                    extRoot.findFileByRelativePath(subPath)?.let { return it }
                }
            }
        }
        return null
    }

    suspend fun resolveFileRefreshing(path: String): VirtualFile? {
        val existing = readAction { resolveFile(path) }
        if (existing != null && existing.exists()) return existing

        val normalized = path.replace('\\', '/')
        val projectPath = readAction { project.guessProjectDir()?.path }
        val candidateIo = listOfNotNull(
            projectPath?.let { File(it, normalized) },
            File(normalized).takeIf { it.isAbsolute }
        ).firstOrNull { it.exists() } ?: return null

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(candidateIo)
    }

    suspend fun resolvePathsRefreshing(paths: List<String>): List<VirtualFile> {
        val resolved = LinkedHashSet<VirtualFile>()
        for (path in paths) {
            resolveFileRefreshing(path)?.takeIf { it.isValid && it.exists() }?.let { resolved.add(it) }
        }
        return resolved.toList()
    }

    private fun isExternalFile(file: VirtualFile): Boolean {
        val projectDir = project.guessProjectDir() ?: return true
        return !VfsUtilCore.isAncestor(projectDir, file, false)
    }

    private fun hasSecondaryRootMarker(dir: VirtualFile): Boolean {
        if (SECONDARY_ROOT_MARKERS.any { dir.findChild(it) != null }) return true
        return dir.children.any { child ->
            SECONDARY_ROOT_SUFFIXES.any { suffix -> child.name.endsWith(suffix) }
        }
    }

    private fun findExternalProjectRoot(file: VirtualFile, projectDir: VirtualFile?): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        val userHome = System.getProperty("user.home")?.let { fs.findFileByPath(it) }
        val tempDir = System.getProperty("java.io.tmpdir")?.let { fs.findFileByPath(it) }

        fun isBoundary(dir: VirtualFile): Boolean {
            if (dir.parent == null) return true
            if (dir.path == "/tmp" || dir.path == "/private/tmp") return true
            if (userHome != null && (dir == userHome || VfsUtilCore.isAncestor(dir, userHome, false))) return true
            if (userHome != null && dir.parent == userHome && dir.name in USER_COMMON_DIR_NAMES) return true
            if (tempDir != null && (dir == tempDir || VfsUtilCore.isAncestor(dir, tempDir, false))) return true
            if (projectDir != null && (dir == projectDir || VfsUtilCore.isAncestor(dir, projectDir, false))) return true
            return false
        }

        val ancestors = mutableListOf<VirtualFile>()
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null && !isBoundary(current)) {
            ProgressManager.checkCanceled()
            ancestors.add(current)
            current = current.parent
        }

        for (dir in ancestors) {
            ProgressManager.checkCanceled()
            if (PRIMARY_ROOT_MARKERS.any { dir.findChild(it) != null }) {
                return dir
            }
        }

        val heavyIdx = ancestors.indexOfLast { FileFilterUtil.isExternalHeavyDir(it) }
        for (i in ancestors.lastIndex downTo (heavyIdx + 1)) {
            ProgressManager.checkCanceled()
            val dir = ancestors[i]
            if (hasSecondaryRootMarker(dir)) {
                return dir
            }
        }

        return null
    }

    private fun mountExternalRoot(root: VirtualFile): Boolean {
        var changed = false
        if (root.isDirectory) {
            if (externalRoots.removeIf { existing ->
                    existing != root && VfsUtilCore.isAncestor(root, existing, true)
                }
            ) {
                changed = true
            }
        }
        val coveredByAncestor = externalRoots.any { existing ->
            existing == root || (existing.isDirectory && VfsUtilCore.isAncestor(existing, root, false))
        }
        if (!coveredByAncestor && externalRoots.add(root)) {
            changed = true
        }
        return changed
    }

    fun addOrStageFiles(
        files: Collection<VirtualFile>,
        stage: Boolean = true,
        clearStaged: Boolean = false,
        promoteDirsToProjectRoot: Boolean = true,
        selectExternalDirs: Boolean = false
    ): StageFilesResult {
        val projectDir = project.guessProjectDir()
        val affectedFiles = LinkedHashSet<VirtualFile>()
        var externalChanged = false

        fun collectAndSelectDir(dir: VirtualFile, isExternal: Boolean) {
            val skipBulkExcluded = !FileFilterUtil.isBulkExcluded(project, dir, isExternal)
            fun visit(vf: VirtualFile) {
                ProgressManager.checkCanceled()
                if (!vf.isValid || FileFilterUtil.isHiddenFromTree(vf)) return
                if (skipBulkExcluded && vf != dir && FileFilterUtil.isBulkExcluded(project, vf, isExternal)) return
                if (vf.isDirectory) {
                    vf.children.forEach(::visit)
                } else {
                    applyStateRecursively(vf, ContextLevel.COMPLETE, checkIgnore = false, isExternal = isExternal)
                    affectedFiles.add(vf)
                }
            }
            visit(dir)
        }

        for (file in files) {
            ProgressManager.checkCanceled()
            if (!file.isValid || FileFilterUtil.isHiddenFromTree(file)) continue
            val isInProject = projectDir != null && VfsUtilCore.isAncestor(projectDir, file, false)
            if (isInProject) {
                if (file.isDirectory) {
                    collectAndSelectDir(file, isExternal = false)
                } else {
                    applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = false, isExternal = false)
                    affectedFiles.add(file)
                }
            } else {
                val mountRoot = if (!file.isDirectory || promoteDirsToProjectRoot) {
                    findExternalProjectRoot(file, projectDir) ?: file
                } else {
                    file
                }
                if (mountExternalRoot(mountRoot)) {
                    externalChanged = true
                }
                if (!file.isDirectory) {
                    applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = false, isExternal = true)
                    affectedFiles.add(file)
                } else if (selectExternalDirs) {
                    collectAndSelectDir(file, isExternal = true)
                }
            }
        }

        if (externalChanged) {
            syncExternalRootPaths()
        }
        if (stage && affectedFiles.isNotEmpty()) {
            if (clearStaged) {
                stagedFiles.clear()
            }
            stagedFiles.addAll(affectedFiles)
        }

        return StageFilesResult(affectedFiles, externalChanged)
    }

    fun applyCommandContext(
        command: SlashCommand,
        targetMap: MutableMap<VirtualFile, ContextLevel> = fileStates
    ) {
        if (command.contextAction == CommandContextAction.REPLACE) {
            targetMap.clear()
        }

        for (path in command.includeFiles) {
            ProgressManager.checkCanceled()
            val file = resolveFile(path)
            if (file != null && file.exists()) {
                applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = file.isDirectory, targetMap = targetMap)
            }
        }
    }

    fun applyFileMention(
        file: VirtualFile,
        targetMap: MutableMap<VirtualFile, ContextLevel> = fileStates
    ) {
        if (file.isValid) {
            applyStateRecursively(file, ContextLevel.COMPLETE, checkIgnore = file.isDirectory, targetMap = targetMap)
        }
    }

    fun applyStateRecursively(
        file: VirtualFile,
        level: ContextLevel,
        checkIgnore: Boolean = true,
        targetMap: MutableMap<VirtualFile, ContextLevel> = fileStates,
        isExternal: Boolean = isExternalFile(file)
    ) {
        ProgressManager.checkCanceled()
        if (FileFilterUtil.isHiddenFromTree(file)) return
        if (checkIgnore && FileFilterUtil.isBulkExcluded(project, file, isExternal)) return

        if (file.isDirectory) {
            if (file.children.isEmpty()) {
                val cappedLevel = if (level == ContextLevel.COMPLETE) ContextLevel.SKELETON else level
                if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                    targetMap.remove(file)
                } else {
                    targetMap[file] = cappedLevel
                }
            } else {
                targetMap.remove(file)
                file.children.forEach { applyStateRecursively(it, level, checkIgnore, targetMap, isExternal) }
            }
        } else {
            val maxLevel = ContextCapabilityUtil.getMaxLevel(file)
            val cappedLevel = if (level == ContextLevel.COMPLETE && maxLevel == ContextLevel.SKELETON) ContextLevel.SKELETON else level

            if (cappedLevel == ContextLevel.NONE || cappedLevel == ContextLevel.MIXED) {
                targetMap.remove(file)
            } else {
                targetMap[file] = cappedLevel
            }
        }
    }

    fun loadConfig() {
        val projectDir = project.guessProjectDir() ?: return
        val configFile = projectDir.findChild(".aicontext")
        val defaultConfig = AiContextConfig(skeleton = listOf("."), complete = listOf("README.md"))

        val config = if (configFile != null && configFile.exists()) {
            try {
                val configText = VfsUtilCore.loadText(configFile)
                jsonParser.decodeFromString<AiContextConfig>(configText)
            } catch (e: Exception) {
                thisLogger().warn("Failed to parse .aicontext: ${e.message}")
                defaultConfig
            }
        } else {
            defaultConfig
        }

        fun applyConfigPaths(paths: List<String>, level: ContextLevel) {
            for (path in paths) {
                ProgressManager.checkCanceled()
                val file = if (path == "." || path == "/") projectDir else projectDir.findFileByRelativePath(path)
                if (file != null && file.exists()) {
                    applyStateRecursively(file, level, checkIgnore = file.isDirectory, isExternal = false)
                }
            }
        }

        applyConfigPaths(config.skeleton, ContextLevel.SKELETON)
        applyConfigPaths(config.complete, ContextLevel.COMPLETE)

        for (extRoot in externalRoots) {
            ProgressManager.checkCanceled()
            if (extRoot.isValid && !extRoot.isDirectory) {
                applyStateRecursively(extRoot, ContextLevel.COMPLETE, checkIgnore = false, isExternal = true)
            }
        }
    }

    fun restoreFromLastTurn() {
        val lastTurn = getLastTurn()
        if (lastTurn == null) {
            fileStates.clear()
            loadConfig()
            return
        }
        restoreFromTurn(lastTurn)
    }

    fun restoreFromTurn(turn: UserTurn) {
        val projectDir = project.guessProjectDir()

        fileStates.clear()
        var externalChanged = false
        for ((path, record) in turn.sentFiles) {
            ProgressManager.checkCanceled()
            val file = resolveFile(path)
            if (file != null && file.exists()) {
                fileStates[file] = record.level
                if (projectDir == null || !VfsUtilCore.isAncestor(projectDir, file, false)) {
                    val mountRoot = findExternalProjectRoot(file, projectDir) ?: file
                    if (mountExternalRoot(mountRoot)) {
                        externalChanged = true
                    }
                }
            }
        }
        if (externalChanged) {
            syncExternalRootPaths()
        }
    }

    fun pruneInvalidFiles() {
        fileStates.keys.removeIf { !it.isValid }
        if (externalRoots.removeIf { !it.isValid }) {
            syncExternalRootPaths()
        }
        stagedFiles.removeIf { !it.isValid }
        hashCache.keys.removeIf { !it.isValid }
        seedGraphCache.entries.removeIf { (seed, entry) ->
            !seed.isValid ||
                entry.outgoingFiles.any { !it.isValid } ||
                entry.incomingScores.keys.any { !it.isValid }
        }
    }

    private fun calculateHash(content: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getCachedHash(file: VirtualFile, level: ContextLevel): String? {
        val currentStamp = file.modificationStamp
        val cached = hashCache[file]
        return if (cached != null && cached.stamp == currentStamp && cached.level == level) {
            cached.hash
        } else {
            null
        }
    }

    fun cacheHash(file: VirtualFile, level: ContextLevel, hash: String): String {
        hashCache[file] = HashCacheEntry(file.modificationStamp, level, hash)
        return hash
    }

    fun cacheContentHash(file: VirtualFile, level: ContextLevel, content: String): String {
        return cacheHash(file, level, calculateHash(content))
    }

    fun getCachedSeedGraph(file: VirtualFile): SeedGraphCacheEntry? {
        val currentStamp = file.modificationStamp
        val cached = seedGraphCache[file]
        return if (cached != null && cached.modificationStamp == currentStamp) {
            cached
        } else {
            null
        }
    }

    fun cacheSeedGraph(file: VirtualFile, outgoingFiles: Set<VirtualFile>, incomingScores: Map<VirtualFile, Int>) {
        seedGraphCache[file] = SeedGraphCacheEntry(
            modificationStamp = file.modificationStamp,
            outgoingFiles = outgoingFiles,
            incomingScores = incomingScores
        )
    }

    fun addTurn(turn: UserTurn) {
        myState.turns.add(turn)
    }

    fun addModelTurn(markdown: String): ModelTurn {
        val now = System.currentTimeMillis()
        val existing = myState.modelTurns.find { it.markdown == markdown }
        if (existing != null) {
            existing.timestamp = now
            myState.modelTurns.remove(existing)
            myState.modelTurns.add(existing)
            return existing
        }
        val modelTurn = ModelTurn(timestamp = now, markdown = markdown)
        myState.modelTurns.add(modelTurn)
        return modelTurn
    }

    fun removeModelTurn(turnId: String) {
        myState.modelTurns.removeAll { it.id == turnId }
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
        isInitialized = false
        fileStates.clear()
        externalRoots.clear()
        myState.externalRootPaths.clear()
        stagedFiles.clear()
        myState.turns.clear()
        myState.modelTurns.clear()
        hashCache.clear()
        seedGraphCache.clear()
    }
}