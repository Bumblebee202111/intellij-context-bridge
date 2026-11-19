package com.github.bumblebee202111.intellijcontextbridge.server

import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

object McpHandler {

    private const val PROTOCOL_VERSION = "2026-07-28"
    private const val SERVER_NAME = "intellij-context-bridge"
    private const val SERVER_VERSION = "0.0.1"
    private const val TOOL_STAGE_FILES = "stage_context_files"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handlePost(call: ApplicationCall) {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin != null && !isLocalhostOrigin(origin)) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }

        val body = call.receiveText()
        val request = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }

        if (request == null) {
            respondJson(call, jsonRpcError(JsonNull, -32700, "Parse error"), HttpStatusCode.BadRequest)
            return
        }

        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        if (method.isNullOrBlank()) {
            respondJson(call, jsonRpcError(id ?: JsonNull, -32600, "Invalid Request"), HttpStatusCode.BadRequest)
            return
        }

        if (id == null) {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        val params = request["params"] as? JsonObject
        val response = try {
            when (method) {
                "server/discover" -> jsonRpcResult(id, buildDiscoverResult())
                "tools/list" -> jsonRpcResult(id, buildToolsListResult())
                "tools/call" -> handleToolCall(id, params)
                "ping" -> jsonRpcResult(id, buildJsonObject {})
                else -> jsonRpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            thisLogger().warn("MCP request failed for method $method", e)
            jsonRpcError(id, -32603, e.message ?: "Internal error")
        }

        respondJson(call, response, HttpStatusCode.OK)
    }

    private fun buildDiscoverResult(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        putJsonArray("supportedVersions") {
            add(kotlinx.serialization.json.JsonPrimitive(PROTOCOL_VERSION))
        }
        putJsonObject("capabilities") {
            putJsonObject("tools") {}
        }
        put("ttlMs", 0)
        put("cacheScope", "private")
        putJsonObject("_meta") {
            putJsonObject("io.modelcontextprotocol/serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
        }
    }

    private fun buildToolsListResult(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        put("ttlMs", 0)
        put("cacheScope", "private")
        putJsonArray("tools") {
            add(buildJsonObject {
                put("name", TOOL_STAGE_FILES)
                put("description", "Stage file paths into the user's active IDE context.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("paths") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                            put("description", "Absolute (or project-relative) file paths to stage.")
                        }
                        putJsonObject("project") {
                            put("type", "string")
                            put("description", "Optional open project name or base path override when multiple projects are open.")
                        }
                        putJsonObject("clearStaged") {
                            put("type", "boolean")
                            put("description", "If true, replace previously staged files instead of appending.")
                        }
                    }
                    putJsonArray("required") {
                        add(kotlinx.serialization.json.JsonPrimitive("paths"))
                    }
                    put("additionalProperties", false)
                }
            })
        }
    }

    private suspend fun handleToolCall(id: JsonElement, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return jsonRpcError(id, -32602, "Missing tool name")

        if (toolName != TOOL_STAGE_FILES) {
            return jsonRpcError(id, -32602, "Unknown tool: $toolName")
        }

        val arguments = params["arguments"] as? JsonObject ?: buildJsonObject {}
        val pathsArray = arguments["paths"] as? JsonArray
            ?: return jsonRpcResult(id, toolCallResult("Missing required 'paths' array.", isError = true))

        val paths = pathsArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (paths.isEmpty()) {
            return jsonRpcResult(id, toolCallResult("'paths' must contain at least one path.", isError = true))
        }

        val projectOverride = arguments["project"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val clearStaged = arguments["clearStaged"]?.jsonPrimitive?.booleanOrNull ?: false

        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (openProjects.isEmpty()) {
            return jsonRpcResult(id, toolCallResult("No open projects found in the IDE.", isError = true))
        }

        val targetProject = resolveTargetProject(openProjects, projectOverride)
            ?: return jsonRpcResult(
                id,
                toolCallResult(
                    "Project '$projectOverride' not found. Open projects: ${openProjects.joinToString { it.name }}",
                    isError = true
                )
            )

        val contextState = targetProject.service<ContextState>()
        val resolved = LinkedHashSet<VirtualFile>()
        val unresolved = mutableListOf<String>()

        for (rawPath in paths) {
            val normalized = normalizePath(rawPath)
            val vf = contextState.resolveFileRefreshing(normalized)
            if (vf != null && vf.isValid && vf.exists()) {
                resolved.add(vf)
            } else {
                unresolved.add(rawPath)
            }
        }

        if (resolved.isEmpty()) {
            val msg = buildString {
                appendLine("Target project: ${targetProject.name}")
                appendLine("None of the provided paths were found:")
                unresolved.forEach { appendLine("- $it") }
            }.trim()
            return jsonRpcResult(id, toolCallResult(msg, isError = true))
        }

        val stageResult = readAction {
            contextState.ensureInitialized()
            contextState.addOrStageFiles(
                files = resolved,
                stage = true,
                clearStaged = clearStaged,
                promoteDirsToProjectRoot = true,
                selectExternalDirs = false
            )
        }

        if (stageResult.affectedFiles.isNotEmpty() || stageResult.externalRootsChanged || clearStaged) {
            contextState.notifyContextChanged()
        }

        val attachedDirs = resolved.filter { it.isDirectory }
        val summary = buildString {
            appendLine("Target project: ${targetProject.name}")
            if (openProjects.size > 1) {
                val others = openProjects.filter { it != targetProject }.joinToString { it.name }
                appendLine("Other open projects: $others")
            }
            if (stageResult.affectedFiles.isNotEmpty()) {
                appendLine("Staged ${stageResult.affectedFiles.size} file(s):")
                stageResult.affectedFiles.forEach { appendLine("- ${it.path}") }
            }
            if (attachedDirs.isNotEmpty()) {
                appendLine("Attached ${attachedDirs.size} directory root(s):")
                attachedDirs.forEach { appendLine("- ${it.path}") }
            }
            if (unresolved.isNotEmpty()) {
                appendLine("Unresolved (${unresolved.size}):")
                unresolved.forEach { appendLine("- $it") }
            }
        }.trim()

        return jsonRpcResult(id, toolCallResult(summary, isError = false))
    }

    private fun resolveTargetProject(openProjects: List<Project>, projectOverride: String?): Project? {
        if (projectOverride != null) {
            return openProjects.firstOrNull {
                it.name.equals(projectOverride, ignoreCase = true) ||
                    it.basePath?.equals(projectOverride, ignoreCase = true) == true
            }
        }
        if (openProjects.size == 1) return openProjects[0]

        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        if (focused != null && !focused.isDisposed && focused in openProjects) {
            return focused
        }
        return openProjects.last()
    }

    private fun normalizePath(raw: String): String {
        var path = raw.trim()
        if (path.startsWith("file://")) {
            path = runCatching { URI(path).path }.getOrNull() ?: path.removePrefix("file://")
        } else if (path.startsWith("~/")) {
            val userHome = System.getProperty("user.home")
            if (!userHome.isNullOrBlank()) {
                path = userHome.trimEnd('/', '\\') + path.removePrefix("~")
            }
        }
        return path
    }

    private fun isLocalhostOrigin(origin: String): Boolean {
        return try {
            val host = URI(origin).host?.lowercase() ?: return false
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        } catch (_: Exception) {
            false
        }
    }

    private fun toolCallResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("resultType", "complete")
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        put("isError", isError)
    }

    private fun jsonRpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun jsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private suspend fun respondJson(call: ApplicationCall, payload: JsonObject, status: HttpStatusCode) {
        call.respondText(
            text = payload.toString(),
            contentType = ContentType.Application.Json,
            status = status
        )
    }
}