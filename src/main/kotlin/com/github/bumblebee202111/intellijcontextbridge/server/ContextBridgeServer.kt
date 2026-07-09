package com.github.bumblebee202111.intellijcontextbridge.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.*
import kotlin.time.Duration.Companion.seconds

data class BrowserTab(val id: String, val title: String, val session: DefaultWebSocketServerSession)

@Service(Service.Level.APP)
class ContextBridgeServer : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val activeTabs = Collections.synchronizedMap(LinkedHashMap<String, BrowserTab>())

    var onTabsChanged: ((List<BrowserTab>) -> Unit)? = null
    var onMessageReceived: ((String) -> Unit)? = null

    fun getActiveTabs(): List<BrowserTab> = activeTabs.values.toList()

    fun start() {
        if (server != null) return

        scope.launch {
            var assignedPort: Int? = null

            // Safely probe for a free port using raw ServerSocket before initializing Ktor
            for (port in 37373..37382) {
                try {
                    ServerSocket(port).use { } // Throws BindException if taken
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
                                            val parts = text.removePrefix("[HANDSHAKE]").split("|", limit = 2)
                                            if (parts.size == 2) {
                                                currentTabId = parts[0]
                                                activeTabs[parts[0]] = BrowserTab(parts[0], parts[1], this@webSocket)
                                                notifyTabsChanged()
                                            }
                                        } else {
                                            onMessageReceived?.invoke(text)
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

                server?.start(wait = false) // Non-blocking start
                thisLogger().info("ContextBridgeServer successfully bound to port $assignedPort")

            } catch (e: Exception) {
                thisLogger().error("ContextBridgeServer crashed during startup on port $assignedPort: ${e.message}")
                server = null
            }
        }
    }

    private fun notifyTabsChanged() {
        onTabsChanged?.invoke(getActiveTabs())
    }

    fun sendToTab(tabId: String, message: String) {
        val tab = activeTabs[tabId]
        if (tab != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    tab.session.send(message)
                } catch (e: Exception) {
                    thisLogger().warn("Failed to send message to tab $tabId")
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        server?.stop(1000, 2000)
        server = null
        activeTabs.clear()
        notifyTabsChanged()
    }
}