package com.github.bumblebee202111.intellijcontextbridge.server

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
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.APP)
class ContextBridgeServer {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    
    // Thread-safe set to keep track of connected browsers
    private val connections = Collections.synchronizedSet(LinkedHashSet<DefaultWebSocketServerSession>())

    // Callbacks to update the IntelliJ UI
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun start() {
        if (server != null) return // Already running

        server = embeddedServer(CIO, port = 37373, host = "127.0.0.1") {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 15.seconds
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            routing {
                webSocket("/ai-bridge") {
                    thisLogger().info("Browser connected to ContextBridge!")
                    connections += this
                    onConnectionChanged?.invoke(true)

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                onMessageReceived?.invoke(text)
                            }
                        }
                    } catch (e: Exception) {
                        thisLogger().warn("WebSocket error: ${e.localizedMessage}")
                    } finally {
                        thisLogger().info("Browser disconnected.")
                        connections -= this
                        onConnectionChanged?.invoke(connections.isNotEmpty())
                    }
                }
            }
        }

        // Start the server in the background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                server?.start(wait = true)
            } catch (e: Exception) {
                thisLogger().error("Failed to start ContextBridgeServer: ${e.message}")
            }
        }
    }

    fun broadcast(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            connections.forEach {
                try {
                    it.send(message)
                } catch (e: Exception) {
                    thisLogger().warn("Failed to send message to a client")
                }
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        connections.clear()
        onConnectionChanged?.invoke(false)
    }
}