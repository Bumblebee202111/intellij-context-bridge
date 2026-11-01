package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.Tree
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.compareTo

class ContextTreeManager(private val project: Project, private val contextState: ContextState) {

    companion object {
        const val WORKSPACE_ROOT_NAME = "[Workspace]"
        const val PENDING_REQUESTS_ROOT_NAME = "Pending AI Requests"
        const val SUGGESTED_ROOT_NAME = "Suggested Context"
        const val EXTERNAL_ROOT_NAME = "External Context"
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
    private var isInitialExpandDone = false
    private var hadPendingNode = false
    private var hadSuggestedNode = false
    private var hadExternalNode = false

    private var currentRequestedNode: DefaultMutableTreeNode? = null
    private var currentSuggestedNode: DefaultMutableTreeNode? = null
    private var currentProjectNode: DefaultMutableTreeNode? = null
    private var currentExternalNode: DefaultMutableTreeNode? = null

    fun clearCache() {
        nodeStateCache.clear()
    }

    fun getComputedLevel(node: DefaultMutableTreeNode): ContextLevel {
        return getAggregatedState(node).level
    }

    fun getNextToggleLevel(node: DefaultMutableTreeNode, file: VirtualFile?): ContextLevel {
        val currentLevel = getComputedLevel(node)
        val nodeData = node.userObject as? NodeData
        val maxLevel = if (nodeData == null || file == null || (nodeData.isDirectory && !nodeData.isEmptyDirectory)) {
            ContextLevel.COMPLETE
        } else {
            nodeData.maxLevel
        }
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

    fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel): Boolean {
        val nodeData = node.userObject as? NodeData
        val file = nodeData?.file
        val currentLevel = getComputedLevel(node)
        var structureChanged = false

        if (level == ContextLevel.NONE && currentLevel == ContextLevel.NONE) {
            if (file != null && contextState.externalRoots.remove(file)) {
                structureChanged = true
            } else if (file == null && nodeData?.displayName == EXTERNAL_ROOT_NAME && contextState.externalRoots.isNotEmpty()) {
                contextState.externalRoots.clear()
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

    fun buildRequestedFilesNode(
        projectDir: VirtualFile?,
        requestedFiles: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        return buildFlatFileGroupNode(
            title = ContextTreeManager.Companion.PENDING_REQUESTS_ROOT_NAME,
            icon = AllIcons.Nodes.ConfigFolder,
            projectDir = projectDir,
            files = requestedFiles
        )
    }

    fun buildSuggestedFilesNode(
        projectDir: VirtualFile?,
        suggestions: Set<VirtualFile>
    ): DefaultMutableTreeNode? {
        return buildFlatFileGroupNode(
            title = ContextTreeManager.Companion.SUGGESTED_ROOT_NAME,
            icon = AllIcons.Actions.IntentionBulb,
            projectDir = projectDir,
            files = suggestions
        )
    }

    fun buildProjectNode(
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

    fun buildExternalContextNode(showSelectedOnly: Boolean): DefaultMutableTreeNode? {
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
    fun buildFileTree(
        dir: VirtualFile,
        showSelectedOnly: Boolean,
        allowedLeaves: Set<VirtualFile>? = null,
        isRoot: Boolean = false
    ): DefaultMutableTreeNode? {
        ProgressManager.checkCanceled()
        if (FileFilterUtil.isIgnored(project, dir)) return null

        fun passesSelection(file: VirtualFile): Boolean {
            if (allowedLeaves != null) return allowedLeaves.contains(file)
            return !showSelectedOnly || contextState.getLevel(file) != ContextLevel.NONE
        }

        val children = dir.children
            .filterNot { FileFilterUtil.isIgnored(project, it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val validChildNodes = mutableListOf<DefaultMutableTreeNode>()

        for (child in children) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) {
                val childNode = buildFileTree(child, showSelectedOnly, allowedLeaves, false)
                if (childNode != null) {
                    validChildNodes.add(childNode)
                }
            } else {
                if (allowedLeaves != null && !allowedLeaves.contains(child)) continue

                if (passesSelection(child)) {
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

    fun updateWorkspaceModel(
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
        var pendingNodePath: TreePath? = null
        var suggestedNodePath: TreePath? = null
        var projectNodePath: TreePath? = null
        var externalNodePath: TreePath? = null

        fun traverse(node: DefaultMutableTreeNode, path: TreePath) {
            val nodeData = node.userObject as? NodeData
            if (nodeData != null) {
                if (path.pathCount == 2) {
                    when (nodeData.displayName) {
                        PENDING_REQUESTS_ROOT_NAME -> pendingNodePath = path
                        SUGGESTED_ROOT_NAME -> suggestedNodePath = path
                        EXTERNAL_ROOT_NAME -> externalNodePath = path
                        else -> if (nodeData.file != null) projectNodePath = path
                    }
                }
                val key = getPathKey(path)
                if (key != null) {
                    if (expandedKeys.contains(key)) {
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

        if (pendingNodePath != null) {
            if (!hadPendingNode) {
                tree.expandPath(pendingNodePath)
                hadPendingNode = true
            }
        } else {
            hadPendingNode = false
        }

        if (suggestedNodePath != null) {
            if (!hadSuggestedNode) {
                tree.expandPath(suggestedNodePath)
                hadSuggestedNode = true
            }
        } else {
            hadSuggestedNode = false
        }

        if (externalNodePath != null) {
            if (!hadExternalNode) {
                tree.expandPath(externalNodePath)
                hadExternalNode = true
            }
        } else {
            hadExternalNode = false
        }

        if (!isInitialExpandDone && projectNodePath != null) {
            tree.expandPath(projectNodePath)
            isInitialExpandDone = true
        }

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
}