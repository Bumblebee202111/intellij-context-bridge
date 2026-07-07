package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.FileStateRecord
import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

class SessionHistoryPanel(private val project: Project, private val onHistoryChanged: () -> Unit) {
    private val contextState = project.service<ContextState>()
    
    val content = JPanel(BorderLayout())
    
    private val listModel = DefaultListModel<UserTurn>()
    private val turnList = JBList(listModel)
    
    private val promptArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Select a turn to view its details."
        margin = JBUI.insets(5)
    }
    
    private val filesListModel = DefaultListModel<Map.Entry<String, FileStateRecord>>()
    private val filesList = JBList(filesListModel).apply {
        emptyText.text = "No files in this turn."
    }
    
    private val deleteButton = JButton("Delete Selected Turn").apply {
        isEnabled = false
    }
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss")

    init {
        turnList.emptyText.text = "No session history."
        turnList.cellRenderer = object : ColoredListCellRenderer<UserTurn>() {
            override fun customizeCellRenderer(list: JList<out UserTurn>, value: UserTurn, index: Int, selected: Boolean, hasFocus: Boolean) {
                val time = dateFormat.format(Date(value.timestamp))
                append("[$time] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                
                if (value.prompt.isBlank()) {
                    append("<Empty Prompt>", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                } else {
                    val snippet = value.prompt.replace("\n", " ").take(40)
                    val displaySnippet = if (value.prompt.length > 40) "$snippet..." else snippet
                    append(displaySnippet, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        filesList.cellRenderer = object : ColoredListCellRenderer<Map.Entry<String, FileStateRecord>>() {
            override fun customizeCellRenderer(list: JList<out Map.Entry<String, FileStateRecord>>, value: Map.Entry<String, FileStateRecord>, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.key, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val levelText = if (value.value.level == ContextLevel.FULL) "Full" else "Skeleton"
                append(" ($levelText)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        turnList.addListSelectionListener {
            val turn = turnList.selectedValue
            deleteButton.isEnabled = turn != null
            
            if (turn != null) {
                if (turn.prompt.isBlank()) {
                    promptArea.text = ""
                    promptArea.emptyText.text = "*(No prompt text provided in this turn)*"
                } else {
                    promptArea.text = turn.prompt
                }

                filesListModel.clear()
                turn.sentFiles.forEach { entry ->
                    filesListModel.addElement(entry)
                }
            } else {
                promptArea.text = ""
                promptArea.emptyText.text = "Select a turn to view its details."
                filesListModel.clear()
            }
        }
        
        deleteButton.addActionListener {
            val turn = turnList.selectedValue ?: return@addActionListener
            contextState.removeTurn(turn.id)
            refresh()
            onHistoryChanged() // Notify Tab 1 to update its tree and undo button
        }
        
        val leftPane = JBScrollPane(turnList).apply {
            border = IdeBorderFactory.createTitledBorder("Timeline")
        }
        
        val rightPane = JPanel(BorderLayout())
        val rightSplitter = JBSplitter(true, 0.4f).apply {
            firstComponent = JBScrollPane(promptArea).apply {
                border = IdeBorderFactory.createTitledBorder("Prompt")
            }
            secondComponent = JBScrollPane(filesList).apply {
                border = IdeBorderFactory.createTitledBorder("Context Files")
            }
        }
        rightPane.add(rightSplitter, BorderLayout.CENTER)
        
        val buttonPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(deleteButton, BorderLayout.EAST)
        }
        rightPane.add(buttonPanel, BorderLayout.SOUTH)
        
        val mainSplitter = JBSplitter(false, 0.35f).apply {
            firstComponent = leftPane
            secondComponent = rightPane
        }
        
        content.add(mainSplitter, BorderLayout.CENTER)
    }
    
    fun refresh() {
        listModel.clear()
        // Show newest at the top
        contextState.getState().turns.reversed().forEach { listModel.addElement(it) }
    }
}