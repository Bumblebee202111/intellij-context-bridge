package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ContextTreeManager(private val project: Project, private val contextState: ContextState) {

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

    fun applyStateToNode(node: DefaultMutableTreeNode, level: ContextLevel) {
        val nodeData = node.userObject as? NodeData
        val file = nodeData?.file
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
            return !showSelectedOnly || contextState.getLevel(file) != ContextLevel.NONE || allowedLeaves != null
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

    fun updateTreeModel(
        tree: Tree,
        newRoot: DefaultMutableTreeNode,
        expandAllByDefault: Boolean = false
    ) {
        val expandedPaths = getExpandedFilePaths(tree)
        val selectedPaths = getSelectedFilePaths(tree)

        tree.model = DefaultTreeModel(newRoot)

        val pathsToSelect = mutableListOf<TreePath>()

        fun traverse(node: DefaultMutableTreeNode, path: TreePath) {
            val nodeData = node.userObject as? NodeData
            if (nodeData != null) {
                val pathStr = nodeData.file?.path ?: nodeData.displayName
                if (expandedPaths.contains(pathStr)) {
                    tree.expandPath(path)
                }
                if (selectedPaths.contains(pathStr)) {
                    pathsToSelect.add(path)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                traverse(child, path.pathByAddingChild(child))
            }
        }

        if (expandedPaths.isNotEmpty() || selectedPaths.isNotEmpty()) {
            traverse(newRoot, TreePath(newRoot))
        }

        if (expandedPaths.isEmpty()) {
            if (expandAllByDefault) {
                TreeUtil.expandAll(tree)
            } else if (tree.rowCount > 0) {
                tree.expandRow(0)
            }
        }

        if (pathsToSelect.isNotEmpty()) {
            tree.selectionPaths = pathsToSelect.toTypedArray()
        }
    }

    private fun getExpandedFilePaths(tree: Tree): Set<String> {
        val expanded = mutableSetOf<String>()
        for (i in 0 until tree.rowCount) {
            if (tree.isExpanded(i)) {
                val path = tree.getPathForRow(i)
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val nodeData = node?.userObject as? NodeData
                if (nodeData != null) {
                    expanded.add(nodeData.file?.path ?: nodeData.displayName)
                }
            }
        }
        return expanded
    }

    private fun getSelectedFilePaths(tree: Tree): Set<String> {
        val selected = mutableSetOf<String>()
        val paths = tree.selectionPaths ?: return selected
        for (path in paths) {
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val nodeData = node.userObject as? NodeData ?: continue
            selected.add(nodeData.file?.path ?: nodeData.displayName)
        }
        return selected
    }
}