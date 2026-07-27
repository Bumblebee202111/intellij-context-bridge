package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.Tree
import java.util.concurrent.ConcurrentHashMap
import javax.swing.tree.DefaultMutableTreeNode
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
            if (allMaxed) return ContextLevel.FULL
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

    fun getNextToggleLevel(node: DefaultMutableTreeNode, file: VirtualFile): ContextLevel {
        val currentLevel = getComputedLevel(node)
        val maxLevel = if (file.isDirectory && file.children.isNotEmpty()) {
            ContextLevel.FULL
        } else {
            ContextCapabilityUtil.getMaxLevel(file)
        }
        return ContextCapabilityUtil.getNextLevel(currentLevel, maxLevel)
    }

    private fun getAggregatedState(node: DefaultMutableTreeNode): AggregatedState {
        val file = (node.userObject as? NodeData)?.file ?: return AggregatedState(
            allMaxed = true,
            allSkeleton = true,
            allNone = true,
            hasLeaves = false
        )
        
        if (!file.isDirectory || file.children.isEmpty()) {
            val level = contextState.getLevel(file)
            val maxLevel = if (file.isDirectory) ContextLevel.SKELETON else ContextCapabilityUtil.getMaxLevel(file)
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
        val file = (node.userObject as? NodeData)?.file ?: return
        if (!file.isDirectory) {
            contextState.applyStateRecursively(file, level, checkIgnore = false)
        } else {
            val enumeration = node.depthFirstEnumeration()
            while (enumeration.hasMoreElements()) {
                val descendant = enumeration.nextElement() as DefaultMutableTreeNode
                val descFile = (descendant.userObject as? NodeData)?.file ?: continue
                if (!descFile.isDirectory || descFile.children.isEmpty()) {
                    contextState.applyStateRecursively(descFile, level, checkIgnore = false)
                }
            }
        }
        nodeStateCache.clear()
    }

    @Suppress("UnsafeVfsRecursion")
    fun buildFileTree(
        dir: VirtualFile,
        showSelectedOnly: Boolean,
        searchQuery: String,
        allowedLeaves: Set<VirtualFile>? = null,
        isRoot: Boolean = false
    ): DefaultMutableTreeNode? {
        if (FileFilterUtil.isIgnored(project, dir)) return null

        val children = dir.children
            .filterNot { FileFilterUtil.isIgnored(project, it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        val validChildNodes = mutableListOf<DefaultMutableTreeNode>()
        val dirMatchesSearch = searchQuery.isBlank() || dir.name.contains(searchQuery, ignoreCase = true)

        for (child in children) {
            if (child.isDirectory) {
                val childNode = buildFileTree(child, showSelectedOnly, searchQuery, allowedLeaves, false)
                if (childNode != null) {
                    validChildNodes.add(childNode)
                }
            } else {
                if (allowedLeaves != null && !allowedLeaves.contains(child)) continue

                val level = contextState.getLevel(child)
                val isSelected = level != ContextLevel.NONE

                val passesSelection = !showSelectedOnly || isSelected || allowedLeaves != null
                val passesSearch = searchQuery.isBlank() || dirMatchesSearch || child.name.contains(searchQuery, ignoreCase = true)

                if (passesSelection && passesSearch) {
                    validChildNodes.add(DefaultMutableTreeNode(NodeData(child, child.name)))
                }
            }
        }

        if (validChildNodes.isEmpty()) {
            val dirLevel = contextState.getLevel(dir)
            val isSelected = dirLevel != ContextLevel.NONE
            val passesSelection = !showSelectedOnly || isSelected || allowedLeaves != null

            if (!passesSelection) return null
            if (searchQuery.isNotBlank() && !dirMatchesSearch) return null
            if (children.isNotEmpty()) return null
        }

        // Bottom-up compaction: Merge directories if they contain exactly 1 valid directory child
        if (!isRoot && validChildNodes.size == 1) {
            val singleChildNode = validChildNodes[0]
            val singleChildData = singleChildNode.userObject as? NodeData
            if (singleChildData != null && singleChildData.file.isDirectory) {
                val compactedDisplayName = "${dir.name}.${singleChildData.displayName}"
                singleChildNode.userObject = NodeData(singleChildData.file, compactedDisplayName)
                return singleChildNode
            }
        }

        val node = DefaultMutableTreeNode(NodeData(dir, dir.name))
        for (childNode in validChildNodes) {
            node.add(childNode)
        }
        return node
    }

    fun collapseDescendants(tree: Tree, node: DefaultMutableTreeNode, path: TreePath) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val childPath = path.pathByAddingChild(child)

            // Recursively collapse grandchildren first to reset deep state
            collapseDescendants(tree, child, childPath)

            // Collapse the direct child, leaving the actioned node's immediate children visible
            tree.collapsePath(childPath)
        }
    }

    /**
     * Captures the absolute paths of all currently expanded folders in the tree.
     */
    fun getExpandedFilePaths(tree: Tree): Set<String> {
        val expanded = mutableSetOf<String>()
        for (i in 0 until tree.rowCount) {
            if (tree.isExpanded(i)) {
                val path = tree.getPathForRow(i)
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val file = (node?.userObject as? NodeData)?.file
                if (file != null) {
                    expanded.add(file.path)
                }
            }
        }
        return expanded
    }

    /**
     * Re-expands folders in the new model based on the captured paths.
     */
    fun restoreExpandedFilePaths(tree: Tree, expandedFiles: Set<String>) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return

        fun traverseAndExpand(node: DefaultMutableTreeNode, path: TreePath) {
            val file = (node.userObject as? NodeData)?.file
            if (file != null && expandedFiles.contains(file.path)) {
                tree.expandPath(path)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                traverseAndExpand(child, path.pathByAddingChild(child))
            }
        }

        traverseAndExpand(root, TreePath(root))
    }
}