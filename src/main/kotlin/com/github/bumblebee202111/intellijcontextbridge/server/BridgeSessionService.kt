package com.github.bumblebee202111.intellijcontextbridge.server

import com.github.bumblebee202111.intellijcontextbridge.commands.CommandRegistryService
import com.github.bumblebee202111.intellijcontextbridge.commands.SlashCommand
import com.github.bumblebee202111.intellijcontextbridge.commands.SyncCommand
import com.github.bumblebee202111.intellijcontextbridge.context.core.AiPayload
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class BridgeSessionService(private val project: Project) : Disposable {

    private val server = ApplicationManager.getApplication().getService(ContextBridgeServer::class.java)
    private val contextState = project.service<ContextState>()
    private val commandRegistry = project.service<CommandRegistryService>()

    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val messageListeners = CopyOnWriteArrayList<(String) -> Unit>()

    var activeTabId: String? = null
        private set
    private var activeTabPathname: String? = null

    init {
        server.start()
        server.addTabsListener(this, ::handleTabsChanged)
        server.addHandshakeListener(this, ::handleTabHandshake)
        server.addMessageListener(this) { tabId, markdownText ->
            if (tabId == activeTabId) {
                messageListeners.forEach { it(markdownText) }
            }
        }
        commandRegistry.addChangeListener(this, ::handleCommandsChanged)
        handleTabsChanged(server.getActiveTabs())
    }

    fun addStateListener(parentDisposable: Disposable, listener: () -> Unit) {
        stateListeners.add(listener)
        Disposer.register(parentDisposable) { stateListeners.remove(listener) }
    }

    fun addMessageListener(parentDisposable: Disposable, listener: (String) -> Unit) {
        messageListeners.add(listener)
        Disposer.register(parentDisposable) { messageListeners.remove(listener) }
    }

    fun isBoundTabConnected(): Boolean = server.isTabConnected(activeTabId)

    fun getDisplayTabs(): List<BrowserTab> {
        val activeTabs = server.getActiveTabs()
        val boundId = activeTabId
        if (boundId != null && activeTabs.none { it.id == boundId }) {
            val disconnectedTab = BrowserTab(
                id = boundId,
                pathname = activeTabPathname ?: "",
                title = contextState.state.boundTabTitle,
                isConnected = false
            )
            return activeTabs + disconnectedTab
        }
        return activeTabs
    }

    fun changeBinding(newId: String?, newPathname: String, newTitle: String) {
        val oldId = activeTabId
        if (oldId != null && oldId != newId) {
            server.sendToTab(oldId, "[UNBOUND]")
        }
        if (newId.isNullOrEmpty()) {
            activeTabId = null
            activeTabPathname = null
            contextState.state.boundTabTitle = ""
        } else {
            activeTabId = newId
            activeTabPathname = newPathname
            contextState.state.boundTabTitle = newTitle
            if (oldId != newId) {
                server.sendToTab(newId, "[BOUND]|${project.name}")
                if (commandRegistry.isLoaded) {
                    sendCommandsToTab(newId, commandRegistry.getCommands())
                }
            }
        }
        notifyStateChanged()
    }

    fun sendPayload(payload: AiPayload): Boolean {
        val tabId = activeTabId ?: return false
        if (!server.isTabConnected(tabId)) return false
        val jsonString = Json.encodeToString(payload)
        server.sendToTab(tabId, jsonString)
        return true
    }

    private fun handleTabsChanged(tabs: List<BrowserTab>) {
        val state = contextState.state
        if (state.boundTabTitle.isNotEmpty() && !server.isTabConnected(activeTabId)) {
            val match = tabs.find { it.title == state.boundTabTitle }
            if (match != null) {
                changeBinding(match.id, match.pathname, match.title)
                return
            }
        }
        notifyStateChanged()
    }

    private fun handleTabHandshake(tab: BrowserTab) {
        val state = contextState.state

        if (state.boundTabTitle.isNotEmpty() && tab.title == state.boundTabTitle) {
            if (tab.id != activeTabId || tab.pathname != activeTabPathname) {
                changeBinding(tab.id, tab.pathname, tab.title)
            } else {
                server.sendToTab(tab.id, "[BOUND]|${project.name}")
                notifyStateChanged()
            }
        } else if (tab.id == activeTabId) {
            if (tab.pathname == activeTabPathname) {
                changeBinding(tab.id, tab.pathname, tab.title)
            } else {
                changeBinding(null, "", "")
            }
        }

        if (commandRegistry.isLoaded) {
            sendCommandsToTab(tab.id, commandRegistry.getCommands())
        }
    }

    private fun handleCommandsChanged(commands: List<SlashCommand>) {
        val boundId = activeTabId
        if (boundId != null && server.isTabConnected(boundId)) {
            sendCommandsToTab(boundId, commands)
        } else {
            server.getActiveTabs().forEach { tab ->
                sendCommandsToTab(tab.id, commands)
            }
        }
    }

    private fun sendCommandsToTab(tabId: String, commands: List<SlashCommand>) {
        val dtos = commands.map { SyncCommand(it.name, it.mode.name, it.promptBody) }
        val json = Json.encodeToString(dtos)
        server.sendToTab(tabId, "[COMMANDS]$json")
    }

    private fun notifyStateChanged() {
        stateListeners.forEach { it() }
    }

    override fun dispose() {
        stateListeners.clear()
        messageListeners.clear()
    }
}