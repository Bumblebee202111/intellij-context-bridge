package com.github.bumblebee202111.intellijcontextbridge.ui

import com.github.bumblebee202111.intellijcontextbridge.diagnostics.DiagnosticReport
import com.github.bumblebee202111.intellijcontextbridge.diagnostics.DiagnosticStatus
import com.github.bumblebee202111.intellijcontextbridge.diagnostics.DiagnosticStep
import com.github.bumblebee202111.intellijcontextbridge.server.ContextBridgeServer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

class DiagnosticDialog(private val project: Project, private val tabId: String) : DialogWrapper(project, true) {
    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
    private val listModel = DefaultListModel<DiagnosticStep>()
    private val stepList = JBList(listModel)
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Select a step to view details..."
        margin = JBUI.insets(5)
    }
    private val jsonParser = Json { ignoreUnknownKeys = true }

    init {
        title = "Web UI Diagnostics (Waiting for results...)"
        init()

        stepList.cellRenderer = object : ColoredListCellRenderer<DiagnosticStep>() {
            override fun customizeCellRenderer(
                list: JList<out DiagnosticStep>,
                value: DiagnosticStep,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = when (value.status) {
                    DiagnosticStatus.PASS -> AllIcons.RunConfigurations.TestPassed
                    DiagnosticStatus.FAIL -> AllIcons.RunConfigurations.TestFailed
                    DiagnosticStatus.TIMEOUT -> AllIcons.RunConfigurations.TestError
                }
                append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(" (${value.durationMs}ms)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        stepList.addListSelectionListener {
            val step = stepList.selectedValue
            if (step != null) {
                val sb = StringBuilder()
                sb.appendLine("Step: ${step.name}")
                sb.appendLine("Status: ${step.status}")
                sb.appendLine("Duration: ${step.durationMs}ms")
                if (step.error != null) {
                    sb.appendLine("\nError:")
                    sb.appendLine(step.error)
                }
                detailsArea.text = sb.toString()
            } else {
                detailsArea.text = ""
            }
        }

        server.addDiagnosticListener(disposable) { incomingTabId, json ->
            if (incomingTabId == tabId) {
                SwingUtilities.invokeLater {
                    try {
                        val report = jsonParser.decodeFromString<DiagnosticReport>(json)
                        listModel.clear()
                        report.steps.forEach { listModel.addElement(it) }
                        title = "Web UI Diagnostics - Total Duration: ${report.totalDurationMs}ms"
                    } catch (e: Exception) {
                        detailsArea.text = "Failed to parse diagnostic report:\n${e.message}\n\nRaw JSON:\n$json"
                    }
                }
            }
        }

        server.sendDiagnosticRun(tabId)
    }

    override fun createCenterPanel(): JComponent {
        val splitter = JBSplitter(false, 0.4f).apply {
            firstComponent = JBScrollPane(stepList)
            secondComponent = JBScrollPane(detailsArea)
            preferredSize = Dimension(700, 400)
        }
        val panel = JPanel(BorderLayout())
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)
}