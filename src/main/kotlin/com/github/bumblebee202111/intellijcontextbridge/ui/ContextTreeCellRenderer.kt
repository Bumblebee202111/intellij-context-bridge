package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

data class NodeData(val file: VirtualFile?, val displayName: String)

class StateIcon(private val level: ContextLevel) : Icon {
    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        val g2d = g as Graphics2D
        val oldAntialias = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldStroke = g2d.stroke

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val accentColor = JBUI.CurrentTheme.Focus.focusColor()
        val disabledColor = JBUI.CurrentTheme.Label.disabledForeground()

        when (level) {
            ContextLevel.COMPLETE -> {
                g2d.color = accentColor
                g2d.fillOval(x + 2, y + 2, 12, 12)
            }
            ContextLevel.SKELETON -> {
                g2d.color = accentColor
                g2d.stroke = BasicStroke(2f)
                g2d.drawOval(x + 3, y + 3, 10, 10)
            }
            ContextLevel.MIXED -> {
                g2d.color = accentColor
                g2d.fillRoundRect(x + 4, y + 4, 8, 8, 2, 2)
            }
            ContextLevel.NONE -> {
                g2d.color = disabledColor
                g2d.stroke = BasicStroke(1f)
                g2d.drawOval(x + 3, y + 3, 10, 10)
            }
        }

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias)
        g2d.stroke = oldStroke
    }

    override fun getIconWidth() = 16
    override fun getIconHeight() = 16
}

class CachedOverlayIcon(private val baseIcon: Icon) : Icon {
    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        baseIcon.paintIcon(c, g, x, y)
        val g2d = g as Graphics2D
        val oldAntialias = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val dotSize = 6
        val padding = 1
        val dotX = x + getIconWidth() - dotSize - padding
        val dotY = y + getIconHeight() - dotSize - padding

        // Draw background cutout for native look
        g2d.color = JBUI.CurrentTheme.Tree.background(true, true)
        g2d.fillOval(dotX - 1, dotY - 1, dotSize + 2, dotSize + 2)

        // Draw the green sync/cached dot
        g2d.color = JBColor(Color(0x59A869), Color(0x499C54))
        g2d.fillOval(dotX, dotY, dotSize, dotSize)

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias)
    }

    override fun getIconWidth() = baseIcon.iconWidth
    override fun getIconHeight() = baseIcon.iconHeight
}

class ContextTreeCellRenderer(
    private val getComputedLevel: (DefaultMutableTreeNode) -> ContextLevel,
    private val isCached: (VirtualFile) -> Boolean
) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val nodeData = node.userObject as? NodeData
        val file = nodeData?.file
        val level = getComputedLevel(node)

        if (file != null) {
            val cached = !file.isDirectory && isCached(file)

            val rowIcon = RowIcon(2)
            rowIcon.setIcon(StateIcon(level), 0)

            val baseIcon = if (file.isDirectory) AllIcons.Nodes.Folder else file.fileType.icon
            val fileIcon = if (cached && baseIcon != null) CachedOverlayIcon(baseIcon) else baseIcon
            rowIcon.setIcon(fileIcon, 1)

            icon = rowIcon

            if (level == ContextLevel.COMPLETE) {
                append(nodeData.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else {
                append(nodeData.displayName)
            }
        } else if (nodeData != null) {
            // Synthetic Root Node (e.g., [AI Requested Files])
            val rowIcon = RowIcon(2)
            rowIcon.setIcon(StateIcon(level), 0)
            rowIcon.setIcon(AllIcons.Nodes.ConfigFolder, 1)
            icon = rowIcon

            append(nodeData.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        } else {
            append(value.toString())
        }
    }
}