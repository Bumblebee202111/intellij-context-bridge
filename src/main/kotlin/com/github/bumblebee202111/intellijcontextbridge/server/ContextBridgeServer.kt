package com.github.bumblebee202111.intellijcontextbridge.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

data class BrowserTab(val id: String, val pathname: String, val title: String, val session: DefaultWebSocketServerSession)

@Serializable
data class SyncCommand(val name: String, val mode: String, val promptBody: String)

@Service(Service.Level.APP)
class ContextBridgeServer(private val scope: CoroutineScope) : Disposable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val activeTabs = ConcurrentHashMap<String, BrowserTab>()

    private val tabsListeners = CopyOnWriteArrayList<(List<BrowserTab>) -> Unit>()
    private val messageListeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    private val handshakeListeners = CopyOnWriteArrayList<(BrowserTab) -> Unit>()

    fun addTabsListener(parentDisposable: Disposable, listener: (List<BrowserTab>) -> Unit) {
        tabsListeners.add(listener)
        Disposer.register(parentDisposable) { tabsListeners.remove(listener) }
    }

    fun addMessageListener(parentDisposable: Disposable, listener: (String, String) -> Unit) {
        messageListeners.add(listener)
        Disposer.register(parentDisposable) { messageListeners.remove(listener) }
    }

    fun addHandshakeListener(parentDisposable: Disposable, listener: (BrowserTab) -> Unit) {
        handshakeListeners.add(listener)
        Disposer.register(parentDisposable) { handshakeListeners.remove(listener) }
    }

    fun getActiveTabs(): List<BrowserTab> = activeTabs.values.toList()

    fun start() {
        if (server != null) return

        scope.launch {
            var assignedPort: Int? = null

            for (port in 37373..37382) {
                try {
                    ServerSocket(port).use { }
                    assignedPort = port
                    break
                } catch (e: Exception) {
                    // Port is in use, try the next one
                }
            }

            if (assignedPort == null) {
                thisLogger().error("Failed to start ContextBridgeServer: All ports 37373-37382 are in use.")
                return@launch
            }

            try {
                server = embeddedServer(CIO, port = assignedPort, host = "127.0.0.1") {
                    install(WebSockets) {
                        pingPeriod = 15.seconds
                        timeout = 15.seconds
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }

                    routing {
                        webSocket("/ai-bridge") {
                            var currentTabId: String? = null
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()

                                        if (text.startsWith("[HANDSHAKE]")) {
                                            val parts = text.removePrefix("[HANDSHAKE]").split("|", limit = 3)
                                            if (parts.size == 3) {
                                                currentTabId = parts[0]
                                                val tab = BrowserTab(parts[0], parts[1], parts[2], this@webSocket)
                                                activeTabs[parts[0]] = tab
                                                notifyTabsChanged()

                                                handshakeListeners.forEach { it.invoke(tab) }
                                            }
                                        } else {
                                            currentTabId?.let { tabId ->
                                                messageListeners.forEach { it.invoke(tabId, text) }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                currentTabId?.let { activeTabs.remove(it) }
                                notifyTabsChanged()
                            }
                        }
                    }
                }

                server?.start(wait = false)
                thisLogger().info("ContextBridgeServer successfully bound to port $assignedPort")

            } catch (e: Exception) {
                thisLogger().error("ContextBridgeServer crashed during startup on port $assignedPort: ${e.message}")
                server = null
            }
        }
    }

    private fun notifyTabsChanged() {
        val tabs = getActiveTabs()
        tabsListeners.forEach { it.invoke(tabs) }
    }

    fun sendToTab(tabId: String, message: String) {
        val tab = activeTabs[tabId]
        if (tab != null) {
            scope.launch {
                try {
                    tab.session.send(message)
                } catch (e: Exception) {
                    thisLogger().warn("Failed to send message to tab $tabId")
                }
            }
        }
    }

    override fun dispose() {
        server?.stop(1000, 2000)
        server = null
        activeTabs.clear()
        notifyTabsChanged()
    }
}