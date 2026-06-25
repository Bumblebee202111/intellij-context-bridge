package com.github.bumblebee202111.intellijcontextbridge.toolWindow

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ContextBridgeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contextBridgeWindow = ContextBridgeToolWindow(project)
        val content = ContentFactory.getInstance().createContent(contextBridgeWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class ContextBridgeToolWindow(private val project: Project) {
        private val contextState = project.service<ContextState>()

        fun getContent(): JPanel {
            val panel = JPanel(BorderLayout())

            val rootDir = project.guessProjectDir()
            val rootNode = if (rootDir != null) buildFileTree(rootDir) else DefaultMutableTreeNode("No Project Root")

            val treeModel = DefaultTreeModel(rootNode)
            val tree = Tree(treeModel)

            // 1. Custom Renderer for [S] and [F] tags
            tree.cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(
                    tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
                ) {
                    val node = value as? DefaultMutableTreeNode ?: return
                    val file = node.userObject as? VirtualFile

                    if (file != null) {
                        icon = file.fileType.icon
                        append(file.name)

                        val level = contextState.getLevel(file)
                        when (level) {
                            ContextLevel.SKELETON -> append("  [S]", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                            ContextLevel.FULL -> append("  [F]", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            ContextLevel.NONE -> {}
                        }
                    } else {
                        append(value.toString())
                    }
                }
            }

            // 2. Mouse Listener for Toggling States
            tree.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Only react to double clicks OR single clicks depending on preference. Let's do single click for fast toggling.
                    val row = tree.getRowForLocation(e.x, e.y)
                    if (row == -1) return

                    val path = tree.getPathForRow(row)
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val file = node.userObject as? VirtualFile ?: return

                    val currentLevel = contextState.getLevel(file)
                    val nextLevel = when (currentLevel) {
                        ContextLevel.NONE -> ContextLevel.SKELETON
                        ContextLevel.SKELETON -> ContextLevel.FULL
                        ContextLevel.FULL -> ContextLevel.NONE
                    }

                    applyStateRecursively(node, nextLevel)
                    tree.repaint() // Refresh the UI to show new tags
                }
            })

            panel.add(JBScrollPane(tree), BorderLayout.CENTER)
            return panel
        }

        // Helper: Recursively build the tree nodes
        private fun buildFileTree(dir: VirtualFile): DefaultMutableTreeNode {
            val node = DefaultMutableTreeNode(dir)

            // Sort: Directories first, then files alphabetically
            val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))

            for (child in children) {
                // Ignore hidden files and build directories to keep the tree clean
                if (child.name.startsWith(".") || child.name == "build") continue

                if (child.isDirectory) {
                    node.add(buildFileTree(child))
                } else {
                    node.add(DefaultMutableTreeNode(child))
                }
            }
            return node
        }

        // Helper: Apply state to a folder and all its children recursively
        private fun applyStateRecursively(node: DefaultMutableTreeNode, level: ContextLevel) {
            val file = node.userObject as? VirtualFile ?: return
            contextState.setLevel(file, level)

            for (i in 0 until node.childCount) {
                val childNode = node.getChildAt(i) as? DefaultMutableTreeNode
                if (childNode != null) {
                    applyStateRecursively(childNode, level)
                }
            }
        }
    }
}