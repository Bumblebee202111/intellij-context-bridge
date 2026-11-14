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
        const val PENDING_REQUESTS_ROOT_NAME = "Pending AI Requests"
        const val SUGGESTED_ROOT_NAME = "Suggested Context"
        const val EXTERNAL_ROOT_NAME = "External Context"

        private val LINE_COL_SUFFIX_REGEX = Regex(":\\d+(?::\\d+)?$")
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
    private var activeSectionKeys = emptySet<String>()

    private var currentRequestedNode: DefaultMutableTreeNode? = null
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
    private var lastRequestedFiles: Set<VirtualFile>? = null
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
        requestRefresh(vfsStructureChanged = true, forceMainRebuild = true)
    }

    fun attachExternalFiles(filesProvider: () -> Collection<VirtualFile>) {
        contextState.scope.launch {
            val added = readAction {
                val files = filesProvider()
                if (files.isNotEmpty()) {
                    contextState.addExternalFiles(files)
                    true
                } else {
                    false
                }
            }
            if (added) {
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
                attachExternalFiles {
                    droppedFiles.mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
                }
                return true
            }
        }

        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val rawText = runCatching {
                transferable.getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()
            if (!rawText.isNullOrBlank()) {
                attachExternalFiles {
                    resolveFilesFromText(rawText)
                }
                return true
            }
        }

        return false
    }

    private fun resolveFilesFromText(rawText: String): List<VirtualFile> {
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

        fun resolveCandidate(rawCandidate: String): VirtualFile? {
            val cleaned = cleanToken(rawCandidate)
            if (cleaned.isEmpty()) return null

            contextState.resolveFile(cleaned)?.takeIf { it.isValid && it.exists() }?.let { return it }

            if (LINE_COL_SUFFIX_REGEX.containsMatchIn(cleaned)) {
                val withoutLineCol = cleaned.replace(LINE_COL_SUFFIX_REGEX, "")
                if (withoutLineCol.isNotEmpty()) {
                    contextState.resolveFile(withoutLineCol)?.takeIf { it.isValid && it.exists() }?.let { return it }
                }
            }
            return null
        }

        for (rawLine in rawText.lineSequence()) {
            ProgressManager.checkCanceled()
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
                    buildProjectNode(projectDir, showSelectedOnly) to
                            buildExternalContextNode(showSelectedOnly)
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

            val currentRequests = contextState.pendingAiRequests.toSet()
            val shouldRebuildRequestedTree = shouldRefreshVfs || currentRequests != lastRequestedFiles
            val requestedRootNode = if (shouldRebuildRequestedTree) {
                readAction {
                    buildRequestedFilesNode(projectDir, currentRequests)
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
                if (shouldRebuildRequestedTree) {
                    lastRequestedFiles = currentRequests
                }

                if (shouldRebuildMainTree || shouldRebuildSuggestionTree || shouldRebuildRequestedTree) {
                    updateWorkspaceModel(
                        tree = tree,
                        updateRequested = shouldRebuildRequestedTree,
                        requestedNode = requestedRootNode,
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

        var allMaxed = true
        var allSkeleton = true
        var allNone = true
        var hasLeaves = false

        val enumeration = node.children()
        while (enumeration.hasMoreElements()) {
            val child = enumeration.nextElement() as DefaultMutableTreeNode
            val childState = getAggregatedState(child)

            if (childState.hasLeaves) {
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

    private fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel): Boolean {
        val nodeData = node.userObject as? NodeData ?: return false
        val file = nodeData.file
        val currentLevel = getComputedLevel(node)
        var structureChanged = false

        if (level == ContextLevel.NONE && currentLevel == ContextLevel.NONE) {
            if (file != null && contextState.removeExternalRoot(file)) {
                structureChanged = true
            } else if (file == null && nodeData.displayName == EXTERNAL_ROOT_NAME && contextState.clearExternalRoots()) {
                structureChanged = true
            }
        }

        if (file == null || nodeData.isDirectory) {
            val enumeration = node.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val descendant = enumeration.nextElement() as DefaultMutableTreeNode
                val descData = descendant.userObject as? NodeData ?: continue
                val descFile = descData.file ?: continue
                if (!descData.isDirectory || descData.isEmptyDirectory) {
                    contextState.applyStateRecursively(descFile, level, checkIgnore = false)
                }
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
            groupNode.add(
                DefaultMutableTreeNode(
                    NodeData(
                        file = file,
                        displayName = file.name,
                        isDirectory = false,
                        isEmptyDirectory = false,
                        maxLevel = maxLevel,
                        icon = fileIcon,
                        locationHint = getFileLocationHint(projectDir, file)
                    )
                )
            )
        }

        return groupNode
    }

    private fun buildRequestedFilesNode(
        projectDir: VirtualFile?,
        requestedFiles: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        return buildFlatFileGroupNode(
            title = PENDING_REQUESTS_ROOT_NAME,
            icon = AllIcons.Nodes.ConfigFolder,
            projectDir = projectDir,
            files = requestedFiles
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

    private fun buildProjectNode(
        projectDir: VirtualFile?,
        showSelectedOnly: Boolean
    ): DefaultMutableTreeNode? {
        if (projectDir == null) return null
        val projectNode = buildFileTree(projectDir, showSelectedOnly, isRoot = true)
            ?: DefaultMutableTreeNode(
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

    private fun buildExternalContextNode(showSelectedOnly: Boolean): DefaultMutableTreeNode? {
        val validExternalRoots = contextState.externalRoots
            .filter { it.isValid }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }, { it.path }))

        if (validExternalRoots.isEmpty()) return null

        val externalChildNodes = mutableListOf<DefaultMutableTreeNode>()
        for (extRoot in validExternalRoots) {
            ProgressManager.checkCanceled()
            val locationHint = extRoot.parent?.path
            if (extRoot.isDirectory) {
                val dirNode = buildFileTree(extRoot, showSelectedOnly, isRoot = true)
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

    @Suppress("UnsafeVfsRecursion")
    private fun buildFileTree(
        dir: VirtualFile,
        showSelectedOnly: Boolean,
        isRoot: Boolean = false
    ): DefaultMutableTreeNode? {
        ProgressManager.checkCanceled()
        if (FileFilterUtil.isIgnored(project, dir)) return null

        fun passesSelection(file: VirtualFile): Boolean {
            return !showSelectedOnly || contextState.getLevel(file) != ContextLevel.NONE
        }

        val children = dir.children
            .filterNot { FileFilterUtil.isIgnored(project, it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val validChildNodes = mutableListOf<DefaultMutableTreeNode>()

        for (child in children) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) {
                val childNode = buildFileTree(child, showSelectedOnly, isRoot = false)
                if (childNode != null) {
                    validChildNodes.add(childNode)
                }
            } else if (passesSelection(child)) {
                val maxLevel = ContextCapabilityUtil.getMaxLevel(child)
                val icon = child.fileType.icon ?: AllIcons.FileTypes.Any_type
                validChildNodes.add(
                    DefaultMutableTreeNode(
                        NodeData(
                            file = child,
                            displayName = child.name,
                            isDirectory = false,
                            isEmptyDirectory = false,
                            maxLevel = maxLevel,
                            icon = icon
                        )
                    )
                )
            }
        }

        if (validChildNodes.isEmpty()) {
            if (!passesSelection(dir) || children.isNotEmpty()) return null
        }

        // Bottom-up compaction: Merge directories if they contain exactly 1 valid directory child
        if (!isRoot && validChildNodes.size == 1) {
            val singleChildNode = validChildNodes[0]
            val singleChildData = singleChildNode.userObject as? NodeData
            if (singleChildData != null && singleChildData.file != null && singleChildData.isDirectory) {
                val compactedDisplayName = "${dir.name}.${singleChildData.displayName}"
                singleChildNode.userObject = singleChildData.copy(displayName = compactedDisplayName)
                return singleChildNode
            }
        }

        val isEmptyDir = children.isEmpty()
        val node = DefaultMutableTreeNode(
            NodeData(
                file = dir,
                displayName = dir.name,
                isDirectory = true,
                isEmptyDirectory = isEmptyDir,
                maxLevel = if (isEmptyDir) ContextLevel.SKELETON else ContextLevel.COMPLETE,
                icon = AllIcons.Nodes.Folder
            )
        )
        for (childNode in validChildNodes) {
            node.add(childNode)
        }
        return node
    }

    private fun updateWorkspaceModel(
        tree: Tree,
        updateRequested: Boolean,
        requestedNode: DefaultMutableTreeNode?,
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

        if (updateRequested) currentRequestedNode = requestedNode
        if (updateSuggested) currentSuggestedNode = suggestedNode
        if (updateMain) {
            currentProjectNode = projectNode
            currentExternalNode = externalNode
        }

        val newRoot = DefaultMutableTreeNode(WORKSPACE_ROOT_NAME)
        currentRequestedNode?.let { newRoot.add(it) }
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
            PENDING_REQUESTS_ROOT_NAME -> "REQ"
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