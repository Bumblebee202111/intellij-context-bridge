package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.context.core.ContextSuggestionEngine
import com.github.bumblebee202111.intellijcontextbridge.context.core.PayloadGenerator
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ContextTreeManager(
    private val project: Project,
    private val contextState: ContextState,
    private val onVfsRefreshed: ((projectDir: VirtualFile?, externalRoots: Collection<VirtualFile>) -> Unit)? = null
) : Disposable {

    companion object {
        const val WORKSPACE_ROOT_NAME = "[Workspace]"
        const val STAGED_ROOT_NAME = "Staged Context"
        const val SUGGESTED_ROOT_NAME = "Suggested Context"
        const val EXTERNAL_ROOT_NAME = "External Context"
        private const val LOADING_NODE_TEXT = "Loading..."

        private val LINE_COL_SUFFIX_REGEX = Regex("""(?::\d+(?:[:-]\d+)?|#L\d+(?:-L?\d+)?)$""")
        private val LIST_PREFIX_REGEX = Regex("^(?:[-*•]|\\d+\\.)\\s+")
        private val GIT_STATUS_PREFIX_REGEX = Regex("^(?:[MADRCU?!]{1,2})\\s+")
    }

    private data class AggregatedState(
        val allMaxed: Boolean,
        val allSkeleton: Boolean,
        val allNone: Boolean,
        val hasLeaves: Boolean
    ) {
        val level: ContextLevel get() {
            if (!hasLeaves) return ContextLevel.NONE
            if (allNone) return ContextLevel.NONE
            if (allSkeleton) return ContextLevel.SKELETON
            if (allMaxed) return ContextLevel.COMPLETE
            return ContextLevel.MIXED
        }
    }

    private val nodeStateCache = ConcurrentHashMap<DefaultMutableTreeNode, AggregatedState>()
    private val expandedLazyDirs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var activeSectionKeys = emptySet<String>()

    private var currentStagedNode: DefaultMutableTreeNode? = null
    private var currentSuggestedNode: DefaultMutableTreeNode? = null
    private var currentProjectNode: DefaultMutableTreeNode? = null
    private var currentExternalNode: DefaultMutableTreeNode? = null

    var showSelectedOnly: Boolean = false
        set(value) {
            field = value
            requestRefresh(forceMainRebuild = true)
        }

    @Volatile
    private var lastDedupedFiles = emptySet<VirtualFile>()

    @Volatile
    private var vfsStructureDirty = true

    @Volatile
    private var mainTreeForceRebuild = true

    private var lastSuggestions: Set<VirtualFile>? = null
    private var lastStagedFiles: Set<VirtualFile>? = null
    private var treeUpdateJob: Job? = null

    private val uiRefreshTimer = Timer(300) {
        refreshTree()
    }.apply { isRepeats = false }

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Loading..."))
    val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "Loading project..."
        emptyText.appendSecondaryText(
            "Space/Click: Toggle | Enter: Open | Right-Click: Clear/Detach | Ctrl+V: Paste Files/Paths",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            null
        )
    }

    val content: JComponent = JBScrollPane(tree)

    init {
        tree.cellRenderer = ContextTreeCellRenderer(::getComputedLevel) { file -> lastDedupedFiles.contains(file) }
        tree.addMouseListener(createTreeMouseListener())
        tree.addKeyListener(createTreeKeyListener())
        tree.addTreeWillExpandListener(createLazyExpandListener())
        TreeSpeedSearch(tree)
        setupExternalFileDropTarget()
        setupPasteAction()
    }

    fun requestRefresh(vfsStructureChanged: Boolean = false, forceMainRebuild: Boolean = false) {
        if (vfsStructureChanged) vfsStructureDirty = true
        if (forceMainRebuild) mainTreeForceRebuild = true
        uiRefreshTimer.restart()
    }

    fun reset() {
        expandedLazyDirs.clear()
        requestRefresh(vfsStructureChanged = true, forceMainRebuild = true)
    }

    fun attachAndStageFiles(
        promoteDirsToProjectRoot: Boolean = true,
        filesProvider: suspend () -> Collection<VirtualFile>
    ) {
        contextState.scope.launch {
            val files = filesProvider()
            if (files.isEmpty()) return@launch
            val changed = readAction {
                val result = contextState.addOrStageFiles(
                    files = files,
                    stage = true,
                    clearStaged = false,
                    promoteDirsToProjectRoot = promoteDirsToProjectRoot
                )
                result.affectedFiles.isNotEmpty() || result.externalRootsChanged
            }
            if (changed) {
                ApplicationManager.getApplication().invokeLater({
                    requestRefresh(vfsStructureChanged = true, forceMainRebuild = true)
                }, ModalityState.nonModal())
            }
        }
    }

    private fun setupExternalFileDropTarget() {
        tree.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                return importFromTransferable(support.transferable)
            }
        }
    }

    private fun setupPasteAction() {
        val pasteShortcutSet = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE)?.shortcutSet ?: return
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val contents = CopyPasteManager.getInstance().contents ?: return
                importFromTransferable(contents)
            }
        }.registerCustomShortcutSet(pasteShortcutSet, tree, this)
    }

    private fun importFromTransferable(transferable: Transferable): Boolean {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val droppedFiles = runCatching {
                @Suppress("UNCHECKED_CAST")
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull()
            if (!droppedFiles.isNullOrEmpty()) {
                attachAndStageFiles(promoteDirsToProjectRoot = true) {
                    droppedFiles.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
                }
                return true
            }
        }

        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val rawText = runCatching {
                transferable.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()
            if (!rawText.isNullOrBlank()) {
                attachAndStageFiles(promoteDirsToProjectRoot = true) {
                    resolveFilesFromText(rawText)
                }
                return true
            }
        }

        return false
    }

    private suspend fun resolveFilesFromText(rawText: String): List<VirtualFile> {
        val resolved = LinkedHashSet<VirtualFile>()
        val userHome = System.getProperty("user.home")

        fun cleanToken(raw: String): String {
            var token = raw.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("`")
                .removePrefix("@")
                .trimEnd(',', ';')
            if (token.startsWith("file://")) {
                token = token.removePrefix("file://")
            } else if (token.startsWith("~/") && !userHome.isNullOrBlank()) {
                token = userHome.trimEnd('/', '\\') + token.removePrefix("~")
            }
            return token
        }

        suspend fun resolveCandidate(rawCandidate: String): VirtualFile? {
            val cleaned = cleanToken(rawCandidate)
            if (cleaned.isEmpty()) return null

            contextState.resolveFileRefreshing(cleaned)?.takeIf { it.isValid && it.exists() }?.let { return it }

            if (LINE_COL_SUFFIX_REGEX.containsMatchIn(cleaned)) {
                val withoutLineCol = cleaned.replace(LINE_COL_SUFFIX_REGEX, "")
                if (withoutLineCol.isNotEmpty()) {
                    contextState.resolveFileRefreshing(withoutLineCol)?.takeIf { it.isValid && it.exists() }?.let { return it }
                }
            }
            return null
        }

        for (rawLine in rawText.lineSequence()) {
            val strippedLine = rawLine.trim().replace(LIST_PREFIX_REGEX, "")
            if (strippedLine.isEmpty()) continue

            val direct = resolveCandidate(strippedLine) ?: run {
                if (GIT_STATUS_PREFIX_REGEX.containsMatchIn(strippedLine)) {
                    resolveCandidate(strippedLine.replace(GIT_STATUS_PREFIX_REGEX, ""))
                } else {
                    null
                }
            }

            if (direct != null) {
                resolved.add(direct)
                continue
            }

            if (strippedLine.any { it.isWhitespace() }) {
                for (part in strippedLine.split(Regex("\\s+"))) {
                    if (part.startsWith("@") || part.contains('/') || part.contains('\\') || part.contains('.')) {
                        resolveCandidate(part)?.let { resolved.add(it) }
                    }
                }
            }
        }

        return resolved.toList()
    }

    private fun createLazyExpandListener(): TreeWillExpandListener {
        return object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? NodeData ?: return
                val dir = data.file ?: return
                if (data.isExcluded && data.isDirectory) {
                    expandedLazyDirs.add(dir.path)
                }
                if (data.isLazyPlaceholder) {
                    loadLazyDirectoryNode(node, dir, isExternalPath(event.path))
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? NodeData ?: return
                val dir = data.file ?: return
                if (data.isExcluded && data.isDirectory) {
                    expandedLazyDirs.remove(dir.path)
                }
            }
        }
    }

    private fun loadLazyDirectoryNode(
        node: DefaultMutableTreeNode,
        dir: VirtualFile,
        isExternal: Boolean
    ) {
        val selectedOnly = showSelectedOnly
        contextState.scope.launch {
            val loadedChildren = readAction {
                if (!dir.isValid) return@readAction emptyList<DefaultMutableTreeNode>()
                val selectedAncestors = collectSelectedAncestors()
                val visibleChildren = dir.children
                    .filterNot { FileFilterUtil.isHiddenFromTree(it) }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                buildChildNodesFromSorted(
                    visibleChildren = visibleChildren,
                    showSelectedOnly = selectedOnly,
                    isExternal = isExternal,
                    isExcludedContext = true,
                    selectedAncestors = selectedAncestors
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (node.root !== tree.model.root) return@invokeLater
                val currentData = node.userObject as? NodeData ?: return@invokeLater
                if (!currentData.isLazyPlaceholder) return@invokeLater

                node.removeAllChildren()
                for (child in loadedChildren) {
                    node.add(child)
                }
                val isEmpty = loadedChildren.isEmpty()
                node.userObject = currentData.copy(
                    isLazyPlaceholder = false,
                    isEmptyDirectory = isEmpty,
                    maxLevel = if (isEmpty) ContextLevel.SKELETON else ContextLevel.COMPLETE
                )
                nodeStateCache.clear()
                (tree.model as? DefaultTreeModel)?.nodeStructureChanged(node)
            }, ModalityState.nonModal())
        }
    }

    private fun applyNodeStateAsync(action: () -> Boolean) {
        contextState.scope.launch {
            val structureChanged = readAction {
                action()
            }
            ApplicationManager.getApplication().invokeLater({
                tree.repaint()
                requestRefresh(vfsStructureChanged = structureChanged, forceMainRebuild = structureChanged)
            }, ModalityState.nonModal())
        }
    }

    private fun createTreeMouseListener(): MouseAdapter {
        return object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                val nodeData = node.userObject as? NodeData ?: return
                val file = nodeData.file

                if (SwingUtilities.isRightMouseButton(e)) {
                    applyNodeStateAsync {
                        applyStateToNode(node, ContextLevel.NONE)
                    }
                    e.consume()
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.clickCount == 1) {
                        if (e.x >= bounds.x && e.x < bounds.x + 28) {
                            applyNodeStateAsync {
                                applyStateToNode(node, getNextToggleLevel(node))
                            }
                            e.consume()
                        }
                    } else if (e.clickCount == 2) {
                        if (file != null && !nodeData.isDirectory) {
                            OpenFileDescriptor(project, file).navigate(true)
                            e.consume()
                        }
                    }
                }
            }
        }
    }

    private fun createTreeKeyListener(): KeyAdapter {
        return object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val paths = tree.selectionPaths ?: return
                val keyCode = e.keyCode

                if (keyCode == KeyEvent.VK_ENTER) {
                    for (path in paths) {
                        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                        val nodeData = node.userObject as? NodeData ?: continue
                        val file = nodeData.file
                        if (file != null && !nodeData.isDirectory) {
                            OpenFileDescriptor(project, file).navigate(true)
                        }
                    }
                    return
                }

                val levelResolver: ((DefaultMutableTreeNode) -> ContextLevel)? = when (keyCode) {
                    KeyEvent.VK_SPACE -> ::getNextToggleLevel
                    KeyEvent.VK_S -> { _ -> ContextLevel.SKELETON }
                    KeyEvent.VK_F -> { _ -> ContextLevel.COMPLETE }
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> { _ -> ContextLevel.NONE }
                    else -> null
                }

                if (levelResolver != null) {
                    applyNodeStateAsync {
                        var structureChanged = false
                        for (path in paths) {
                            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                            if (applyStateToNode(node, levelResolver(node))) {
                                structureChanged = true
                            }
                        }
                        structureChanged
                    }
                    e.consume()
                }
            }
        }
    }

    private fun refreshTree() {
        treeUpdateJob?.cancel()

        treeUpdateJob = contextState.scope.launch {
            readAction {
                contextState.ensureInitialized()
            }

            val suggestions = ContextSuggestionEngine.calculateSuggestions(project, contextState)

            val newDedupedFiles = mutableSetOf<VirtualFile>()
            val cache = contextState.getDedupCache()
            for ((file, currentLevel) in contextState.fileStates) {
                if (currentLevel == ContextLevel.NONE || currentLevel == ContextLevel.MIXED) continue

                val isDeduped = readAction {
                    ProgressManager.checkCanceled()
                    if (!file.isValid) return@readAction false
                    PayloadGenerator.isFileDeduped(project, contextState, file, currentLevel, cache)
                }

                if (isDeduped) {
                    newDedupedFiles.add(file)
                }
            }

            val projectDir = readAction { project.guessProjectDir() }
            val shouldRefreshVfs = vfsStructureDirty
            if (shouldRefreshVfs && onVfsRefreshed != null) {
                readAction {
                    onVfsRefreshed.invoke(projectDir, contextState.externalRoots)
                }
            }

            clearCache()

            val shouldRebuildMainTree = shouldRefreshVfs || mainTreeForceRebuild || showSelectedOnly
            val (projectNode, externalNode) = if (shouldRebuildMainTree) {
                readAction {
                    val selectedAncestors = collectSelectedAncestors()
                    buildProjectNode(projectDir, showSelectedOnly, selectedAncestors) to
                        buildExternalContextNode(showSelectedOnly, selectedAncestors)
                }
            } else {
                null to null
            }

            val shouldRebuildSuggestionTree = shouldRefreshVfs || suggestions != lastSuggestions
            val suggestionRootNode = if (shouldRebuildSuggestionTree) {
                readAction {
                    buildSuggestedFilesNode(projectDir, suggestions)
                }
            } else {
                null
            }

            val currentStaged = contextState.stagedFiles.toSet()
            val shouldRebuildStagedTree = shouldRefreshVfs || currentStaged != lastStagedFiles
            val stagedRootNode = if (shouldRebuildStagedTree) {
                readAction {
                    buildStagedFilesNode(projectDir, currentStaged)
                }
            } else {
                null
            }

            ApplicationManager.getApplication().invokeLater({
                if (shouldRefreshVfs) {
                    vfsStructureDirty = false
                }
                if (shouldRebuildMainTree) {
                    mainTreeForceRebuild = false
                }

                lastDedupedFiles = newDedupedFiles

                if (shouldRebuildSuggestionTree) {
                    lastSuggestions = suggestions
                }
                if (shouldRebuildStagedTree) {
                    lastStagedFiles = currentStaged
                }

                if (shouldRebuildMainTree || shouldRebuildSuggestionTree || shouldRebuildStagedTree) {
                    updateWorkspaceModel(
                        tree = tree,
                        updateStaged = shouldRebuildStagedTree,
                        stagedNode = stagedRootNode,
                        updateSuggested = shouldRebuildSuggestionTree,
                        suggestedNode = suggestionRootNode,
                        updateMain = shouldRebuildMainTree,
                        projectNode = projectNode,
                        externalNode = externalNode
                    )
                } else {
                    tree.repaint()
                }
            }, ModalityState.nonModal())
        }
    }

    private fun clearCache() {
        nodeStateCache.clear()
    }

    fun getComputedLevel(node: DefaultMutableTreeNode): ContextLevel {
        return getAggregatedState(node).level
    }

    private fun getNextToggleLevel(node: DefaultMutableTreeNode): ContextLevel {
        val currentLevel = getComputedLevel(node)
        val maxLevel = (node.userObject as? NodeData)?.maxLevel ?: ContextLevel.COMPLETE
        return ContextCapabilityUtil.getNextLevel(currentLevel, maxLevel)
    }

    private fun getAggregatedState(node: DefaultMutableTreeNode): AggregatedState {
        val nodeData = node.userObject as? NodeData
        val file = nodeData?.file

        if (nodeData != null && nodeData.isLazyPlaceholder) {
            return AggregatedState(allMaxed = true, allSkeleton = true, allNone = true, hasLeaves = false)
        }

        if (file != null && (!nodeData.isDirectory || nodeData.isEmptyDirectory)) {
            val level = contextState.getLevel(file)
            val maxLevel = nodeData.maxLevel
            return AggregatedState(
                allMaxed = (level == maxLevel),
                allSkeleton = (level == ContextLevel.SKELETON),
                allNone = (level == ContextLevel.NONE),
                hasLeaves = true
            )
        }

        nodeStateCache[node]?.let { return it }

        val parentIsExcluded = nodeData?.isExcluded == true
        var allMaxed = true
        var allSkeleton = true
        var allNone = true
        var hasLeaves = false

        val enumeration = node.children()
        while (enumeration.hasMoreElements()) {
            val child = enumeration.nextElement() as DefaultMutableTreeNode
            val childData = child.userObject as? NodeData ?: continue
            val childState = getAggregatedState(child)

            if (childState.hasLeaves && !(childData.isExcluded && !parentIsExcluded && childState.allNone)) {
                hasLeaves = true
                if (!childState.allMaxed) allMaxed = false
                if (!childState.allSkeleton) allSkeleton = false
                if (!childState.allNone) allNone = false
            }
        }

        val result = AggregatedState(allMaxed, allSkeleton, allNone, hasLeaves)
        nodeStateCache[node] = result
        return result
    }

    private fun isNodeInSection(node: DefaultMutableTreeNode, sectionName: String): Boolean {
        val pathNodes = node.path
        if (pathNodes.size < 2) return false
        val sectionNode = pathNodes[1] as? DefaultMutableTreeNode ?: return false
        return (sectionNode.userObject as? NodeData)?.displayName == sectionName
    }

    private fun isExternalPath(path: TreePath): Boolean {
        val sectionNode = path.path.getOrNull(1) as? DefaultMutableTreeNode ?: return false
        return (sectionNode.userObject as? NodeData)?.displayName == EXTERNAL_ROOT_NAME
    }

    private fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel): Boolean {
        val nodeData = node.userObject as? NodeData ?: return false
        val file = nodeData.file
        val currentLevel = getComputedLevel(node)
        var structureChanged = false

        if (level == ContextLevel.NONE && currentLevel == ContextLevel.NONE) {
            if (isNodeInSection(node, STAGED_ROOT_NAME)) {
                if (file != null && contextState.removeStagedFile(file)) {
                    structureChanged = true
                } else if (file == null && nodeData.displayName == STAGED_ROOT_NAME && contextState.clearStagedFiles()) {
                    structureChanged = true
                }
            } else {
                if (file != null && contextState.removeExternalRoot(file)) {
                    structureChanged = true
                } else if (file == null && nodeData.displayName == EXTERNAL_ROOT_NAME && contextState.clearExternalRoots()) {
                    structureChanged = true
                }
            }
        }

        if (file == null || nodeData.isDirectory) {
            val targetIsExcluded = nodeData.isExcluded
            val isFlatGroup = file == null && (nodeData.displayName == STAGED_ROOT_NAME || nodeData.displayName == SUGGESTED_ROOT_NAME)
            if (nodeData.isLazyPlaceholder && file != null) {
                contextState.applyStateRecursively(file, level, checkIgnore = false)
                structureChanged = true
            } else {
                fun applySubtree(current: DefaultMutableTreeNode) {
                    val data = current.userObject as? NodeData ?: return
                    if (current != node && data.isExcluded && !targetIsExcluded && level != ContextLevel.NONE && !isFlatGroup) {
                        return
                    }
                    if (data.isLazyPlaceholder) {
                        if ((level == ContextLevel.NONE || targetIsExcluded) && data.file != null) {
                            contextState.applyStateRecursively(data.file, level, checkIgnore = false)
                            if (level != ContextLevel.NONE) {
                                structureChanged = true
                            }
                        }
                        return
                    }
                    val currentFile = data.file
                    if (currentFile != null && (!data.isDirectory || data.isEmptyDirectory)) {
                        contextState.applyStateRecursively(currentFile, level, checkIgnore = false)
                    } else {
                        val children = current.children()
                        while (children.hasMoreElements()) {
                            applySubtree(children.nextElement() as DefaultMutableTreeNode)
                        }
                    }
                }
                applySubtree(node)
            }
        } else {
            contextState.applyStateRecursively(file, level, checkIgnore = false)
        }
        nodeStateCache.clear()
        return structureChanged
    }

    private fun getFileLocationHint(projectDir: VirtualFile?, file: VirtualFile): String? {
        val parent = file.parent ?: return null
        if (projectDir != null) {
            if (parent == projectDir) return null
            VfsUtilCore.getRelativePath(parent, projectDir)?.let { return it }
        }
        return parent.path
    }

    private fun buildFlatFileGroupNode(
        title: String,
        icon: Icon,
        projectDir: VirtualFile?,
        files: Collection<VirtualFile>
    ): DefaultMutableTreeNode? {
        val validFiles = files.filter { it.isValid && !it.isDirectory }
        if (validFiles.isEmpty()) return null

        val countText = if (validFiles.size == 1) "1 file" else "${validFiles.size} files"
        val groupNode = DefaultMutableTreeNode(
            NodeData(
                file = null,
                displayName = title,
                isDirectory = true,
                isEmptyDirectory = false,
                maxLevel = ContextLevel.COMPLETE,
                icon = icon,
                locationHint = countText
            )
        )

        for (file in validFiles) {
            ProgressManager.checkCanceled()
            val maxLevel = ContextCapabilityUtil.getMaxLevel(file)
            val fileIcon = file.fileType.icon ?: AllIcons.FileTypes.Any_type
            val isExternal = projectDir == null || !VfsUtilCore.isAncestor(projectDir, file, false)
            val isExcluded = FileFilterUtil.isBulkExcluded(project, file, isExternal)
            groupNode.add(
                DefaultMutableTreeNode(
                    NodeData(
                        file = file,
                        displayName = file.name,
                        isDirectory = false,
                        isEmptyDirectory = false,
                        maxLevel = maxLevel,
                        icon = fileIcon,
                        locationHint = getFileLocationHint(projectDir, file),
                        isExcluded = isExcluded
                    )
                )
            )
        }

        return groupNode
    }

    private fun buildStagedFilesNode(
        projectDir: VirtualFile?,
        stagedFiles: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        return buildFlatFileGroupNode(
            title = STAGED_ROOT_NAME,
            icon = AllIcons.Nodes.ConfigFolder,
            projectDir = projectDir,
            files = stagedFiles
        )
    }

    private fun buildSuggestedFilesNode(
        projectDir: VirtualFile?,
        suggestions: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        return buildFlatFileGroupNode(
            title = SUGGESTED_ROOT_NAME,
            icon = AllIcons.Actions.IntentionBulb,
            projectDir = projectDir,
            files = suggestions
        )
    }

    private fun collectSelectedAncestors(): Set<VirtualFile> {
        val ancestors = HashSet<VirtualFile>()
        for (file in contextState.fileStates.keys) {
            var parent = file.parent
            while (parent != null && ancestors.add(parent)) {
                parent = parent.parent
            }
        }
        return ancestors
    }

    private fun buildProjectNode(
        projectDir: VirtualFile?,
        showSelectedOnly: Boolean,
        selectedAncestors: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        if (projectDir == null) return null
        val projectNode = buildFileTree(
            dir = projectDir,
            showSelectedOnly = showSelectedOnly,
            isRoot = true,
            isExternal = false,
            isExcludedContext = false,
            selectedAncestors = selectedAncestors
        ) ?: DefaultMutableTreeNode(
            NodeData(
                file = projectDir,
                displayName = projectDir.name,
                isDirectory = true,
                isEmptyDirectory = true,
                maxLevel = ContextLevel.SKELETON,
                icon = AllIcons.Nodes.Folder,
                locationHint = projectDir.path
            )
        )
        val data = projectNode.userObject as? NodeData
        if (data != null && data.locationHint == null) {
            projectNode.userObject = data.copy(locationHint = projectDir.path)
        }
        return projectNode
    }

    private fun buildExternalContextNode(
        showSelectedOnly: Boolean,
        selectedAncestors: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        val validExternalRoots = contextState.externalRoots
            .filter { it.isValid }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }, { it.path }))

        if (validExternalRoots.isEmpty()) return null

        val externalChildNodes = mutableListOf<DefaultMutableTreeNode>()
        for (extRoot in validExternalRoots) {
            ProgressManager.checkCanceled()
            val locationHint = extRoot.parent?.path
            if (extRoot.isDirectory) {
                val dirNode = buildFileTree(
                    dir = extRoot,
                    showSelectedOnly = showSelectedOnly,
                    isRoot = true,
                    isExternal = true,
                    isExcludedContext = false,
                    selectedAncestors = selectedAncestors
                )
                if (dirNode != null) {
                    val data = dirNode.userObject as? NodeData
                    if (data != null) {
                        dirNode.userObject = data.copy(locationHint = locationHint)
                    }
                    externalChildNodes.add(dirNode)
                }
            } else {
                if (!showSelectedOnly || contextState.getLevel(extRoot) != ContextLevel.NONE) {
                    val maxLevel = ContextCapabilityUtil.getMaxLevel(extRoot)
                    val icon = extRoot.fileType.icon ?: AllIcons.FileTypes.Any_type
                    externalChildNodes.add(
                        DefaultMutableTreeNode(
                            NodeData(
                                file = extRoot,
                                displayName = extRoot.name,
                                isDirectory = false,
                                isEmptyDirectory = false,
                                maxLevel = maxLevel,
                                icon = icon,
                                locationHint = locationHint
                            )
                        )
                    )
                }
            }
        }

        if (externalChildNodes.isEmpty()) return null

        val externalContainerNode = DefaultMutableTreeNode(
            NodeData(
                file = null,
                displayName = EXTERNAL_ROOT_NAME,
                isDirectory = true,
                isEmptyDirectory = false,
                maxLevel = ContextLevel.COMPLETE,
                icon = AllIcons.Nodes.PpLibFolder
            )
        )
        for (child in externalChildNodes) {
            externalContainerNode.add(child)
        }
        return externalContainerNode
    }

    private fun buildChildNodesFromSorted(
        visibleChildren: List<VirtualFile>,
        showSelectedOnly: Boolean,
        isExternal: Boolean,
        isExcludedContext: Boolean,
        selectedAncestors: Set<VirtualFile>
    ): List<DefaultMutableTreeNode> {
        val validChildNodes = mutableListOf<DefaultMutableTreeNode>()

        for (child in visibleChildren) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) {
                val childNode = buildFileTree(
                    dir = child,
                    showSelectedOnly = showSelectedOnly,
                    isRoot = false,
                    isExternal = isExternal,
                    isExcludedContext = isExcludedContext,
                    selectedAncestors = selectedAncestors
                )
                if (childNode != null) {
                    validChildNodes.add(childNode)
                }
            } else if (!showSelectedOnly || contextState.getLevel(child) != ContextLevel.NONE) {
                val maxLevel = ContextCapabilityUtil.getMaxLevel(child)
                val icon = child.fileType.icon ?: AllIcons.FileTypes.Any_type
                val isChildExcluded = isExcludedContext || FileFilterUtil.isBulkExcluded(project, child, isExternal)
                validChildNodes.add(
                    DefaultMutableTreeNode(
                        NodeData(
                            file = child,
                            displayName = child.name,
                            isDirectory = false,
                            isEmptyDirectory = false,
                            maxLevel = maxLevel,
                            icon = icon,
                            isExcluded = isChildExcluded
                        )
                    )
                )
            }
        }

        return validChildNodes
    }

    @Suppress("UnsafeVfsRecursion")
    private fun buildFileTree(
        dir: VirtualFile,
        showSelectedOnly: Boolean,
        isRoot: Boolean = false,
        isExternal: Boolean = false,
        isExcludedContext: Boolean = false,
        selectedAncestors: Set<VirtualFile> = emptySet()
    ): DefaultMutableTreeNode? {
        ProgressManager.checkCanceled()
        if (!isRoot && FileFilterUtil.isHiddenFromTree(dir)) return null

        val isDirExcluded = isExcludedContext || (!isRoot && FileFilterUtil.isBulkExcluded(project, dir, isExternal))
        val dirIcon = if (!isExternal && FileFilterUtil.isProjectExcluded(project, dir)) {
            AllIcons.Modules.ExcludeRoot
        } else {
            AllIcons.Nodes.Folder
        }

        if (isDirExcluded) {
            val hasSelectedDescendant = dir in selectedAncestors || contextState.getLevel(dir) != ContextLevel.NONE
            val shouldExpandNow = dir.path in expandedLazyDirs || hasSelectedDescendant
            if (!shouldExpandNow) {
                if (showSelectedOnly) return null
                val hasVisibleChildren = dir.children.any { !FileFilterUtil.isHiddenFromTree(it) }
                val node = DefaultMutableTreeNode(
                    NodeData(
                        file = dir,
                        displayName = dir.name,
                        isDirectory = true,
                        isEmptyDirectory = !hasVisibleChildren,
                        maxLevel = if (hasVisibleChildren) ContextLevel.COMPLETE else ContextLevel.SKELETON,
                        icon = dirIcon,
                        isExcluded = true,
                        isLazyPlaceholder = hasVisibleChildren
                    )
                )
                if (hasVisibleChildren) {
                    node.add(DefaultMutableTreeNode(LOADING_NODE_TEXT))
                }
                return node
            }
        }

        val visibleChildren = dir.children
            .filterNot { FileFilterUtil.isHiddenFromTree(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val validChildNodes = buildChildNodesFromSorted(
            visibleChildren = visibleChildren,
            showSelectedOnly = showSelectedOnly,
            isExternal = isExternal,
            isExcludedContext = isDirExcluded,
            selectedAncestors = selectedAncestors
        )

        if (validChildNodes.isEmpty()) {
            val passesSelection = !showSelectedOnly || contextState.getLevel(dir) != ContextLevel.NONE
            if (!passesSelection || visibleChildren.isNotEmpty()) return null
        }

        // Bottom-up compaction: Merge non-excluded directories if they contain exactly 1 valid non-excluded directory child
        if (!isRoot && !isDirExcluded && validChildNodes.size == 1) {
            val singleChildNode = validChildNodes[0]
            val singleChildData = singleChildNode.userObject as? NodeData
            if (singleChildData != null && singleChildData.file != null && singleChildData.isDirectory && !singleChildData.isExcluded) {
                val compactedDisplayName = "${dir.name}.${singleChildData.displayName}"
                singleChildNode.userObject = singleChildData.copy(displayName = compactedDisplayName)
                return singleChildNode
            }
        }

        val isEmptyDir = visibleChildren.isEmpty()
        val node = DefaultMutableTreeNode(
            NodeData(
                file = dir,
                displayName = dir.name,
                isDirectory = true,
                isEmptyDirectory = isEmptyDir,
                maxLevel = if (isEmptyDir) ContextLevel.SKELETON else ContextLevel.COMPLETE,
                icon = dirIcon,
                isExcluded = isDirExcluded
            )
        )
        for (childNode in validChildNodes) {
            node.add(childNode)
        }
        return node
    }

    private fun updateWorkspaceModel(
        tree: Tree,
        updateStaged: Boolean,
        stagedNode: DefaultMutableTreeNode?,
        updateSuggested: Boolean,
        suggestedNode: DefaultMutableTreeNode?,
        updateMain: Boolean,
        projectNode: DefaultMutableTreeNode?,
        externalNode: DefaultMutableTreeNode?
    ) {
        val expandedKeys = getExpandedKeys(tree)
        val selectedKeys = getSelectedKeys(tree)
        val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, tree) as? JScrollPane
        val viewPosition = scrollPane?.viewport?.viewPosition

        if (updateStaged) currentStagedNode = stagedNode
        if (updateSuggested) currentSuggestedNode = suggestedNode
        if (updateMain) {
            currentProjectNode = projectNode
            currentExternalNode = externalNode
        }

        val newRoot = DefaultMutableTreeNode(WORKSPACE_ROOT_NAME)
        currentStagedNode?.let { newRoot.add(it) }
        currentSuggestedNode?.let { newRoot.add(it) }
        currentProjectNode?.let { newRoot.add(it) }
        currentExternalNode?.let { newRoot.add(it) }

        tree.model = DefaultTreeModel(newRoot)

        val pathsToSelect = mutableListOf<TreePath>()
        val currentSectionKeys = mutableSetOf<String>()

        fun traverse(node: DefaultMutableTreeNode, path: TreePath) {
            val nodeData = node.userObject as? NodeData
            if (nodeData != null) {
                val key = getPathKey(path)
                if (key != null) {
                    val isNewSection = path.pathCount == 2 && currentSectionKeys.add(key) && key !in activeSectionKeys
                    if (isNewSection || expandedKeys.contains(key)) {
                        tree.expandPath(path)
                    }
                    if (selectedKeys.contains(key)) {
                        pathsToSelect.add(path)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                traverse(child, path.pathByAddingChild(child))
            }
        }

        traverse(newRoot, TreePath(newRoot))
        activeSectionKeys = currentSectionKeys

        if (pathsToSelect.isNotEmpty()) {
            tree.selectionPaths = pathsToSelect.toTypedArray()
        }

        if (scrollPane != null && viewPosition != null) {
            scrollPane.viewport.viewPosition = viewPosition
        }
    }

    private fun getPathKey(path: TreePath): String? {
        val components = path.path
        if (components.size < 2) return null
        val topNode = components[1] as? DefaultMutableTreeNode ?: return null
        val topData = topNode.userObject as? NodeData
        val sectionPrefix = when (topData?.displayName) {
            STAGED_ROOT_NAME -> "STAGED"
            SUGGESTED_ROOT_NAME -> "SUG"
            EXTERNAL_ROOT_NAME -> "EXT"
            else -> "PROJ"
        }
        val lastNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val lastData = lastNode.userObject as? NodeData ?: return null
        val nodeId = lastData.file?.path ?: lastData.displayName
        return "$sectionPrefix:$nodeId"
    }

    private fun getExpandedKeys(tree: Tree): Set<String> {
        val expanded = mutableSetOf<String>()
        for (i in 0 until tree.rowCount) {
            if (tree.isExpanded(i)) {
                val path = tree.getPathForRow(i) ?: continue
                getPathKey(path)?.let { expanded.add(it) }
            }
        }
        return expanded
    }

    private fun getSelectedKeys(tree: Tree): Set<String> {
        val selected = mutableSetOf<String>()
        val paths = tree.selectionPaths ?: return selected
        for (path in paths) {
            getPathKey(path)?.let { selected.add(it) }
        }
        return selected
    }

    override fun dispose() {
        uiRefreshTimer.stop()
        treeUpdateJob?.cancel()
    }
}