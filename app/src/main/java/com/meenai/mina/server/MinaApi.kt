package com.meenai.mina.server

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every backend endpoint the app can consume, grouped by feature. All calls flow
 * through MinaServerClient (auth header, single retry after refresh, 429
 * Retry-After handling, offline/unreachable mapping) so call sites stay one-liners.
 * Responses stay as JsonElement: typed models can be mapped per screen later
 * without re-touching this file.
 */
class MinaApi(private val client: MinaServerClient, private val sse: MinaSseClient) {

    // ---- Provider key management (server-held, encrypted) ----
    suspend fun listKeys(): JsonElement = client.get("/v1/providers/keys")
    suspend fun addKey(provider: String, apiKey: String, label: String? = null): JsonElement =
        client.post("/v1/providers/keys", buildJsonObject {
            put("provider", provider); put("api_key", apiKey); if (label != null) put("label", label)
        })
    suspend fun rotateKey(keyId: String, apiKey: String): JsonElement =
        client.post("/v1/providers/keys/$keyId/rotate", buildJsonObject { put("api_key", apiKey) })
    suspend fun testKey(keyId: String): JsonElement =
        client.post("/v1/providers/keys/$keyId/test", buildJsonObject { })
    suspend fun deleteKey(keyId: String): JsonElement = client.delete("/v1/providers/keys/$keyId")

    // ---- Model preferences + routing ----
    suspend fun aiPolicy(): JsonElement = client.get("/v1/settings/ai-policy")
    suspend fun setModelPreference(preferredModel: String?, group: String?): JsonElement =
        client.patch("/v1/settings/ai-policy", buildJsonObject {
            if (preferredModel != null) put("preferred_model", preferredModel)
            if (group != null) put("preferred_group", group)
        })
    suspend fun modelCatalog(): JsonElement = client.get("/v1/catalog")
    suspend fun routingPreview(prompt: String): JsonElement =
        client.post("/v1/routing/preview", buildJsonObject { put("prompt", prompt) })

    // ---- Composio connections (server-managed OAuth) ----
    suspend fun connections(): JsonElement = client.get("/v1/connections")
    suspend fun connectApp(app: String): JsonElement =
        client.post("/v1/connections/$app/connect", buildJsonObject { })
    suspend fun connectionStatus(connectionId: String): JsonElement = client.get("/v1/connections/$connectionId")
    suspend fun disconnectApp(connectionId: String): JsonElement = client.delete("/v1/connections/$connectionId")

    // ---- Tools ----
    suspend fun searchTools(query: String, schemas: Boolean = false): JsonElement =
        client.get("/v1/tools/search?query=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&include_schemas=$schemas")
    suspend fun executeTool(toolSlug: String, args: JsonElement, connectedAccountId: String? = null): JsonElement =
        client.post("/v1/tools/execute", buildJsonObject {
            put("tool_slug", toolSlug); put("args", args)
            if (connectedAccountId != null) put("connected_account_id", connectedAccountId)
        })

    // ---- Memory ----
    suspend fun memories(kind: String? = null, limit: Int = 50): JsonElement =
        client.get("/v1/memory?" + listOfNotNull(
            kind?.let { "kind=$it" }, "limit=$limit").joinToString("&"))
    suspend fun addMemory(content: String, kind: String = "semantic"): JsonElement =
        client.post("/v1/memory", buildJsonObject { put("content", content); put("kind", kind) })
    suspend fun updateMemory(id: String, content: String): JsonElement =
        client.patch("/v1/memory/$id", buildJsonObject { put("content", content) })
    suspend fun deleteMemory(id: String, purge: Boolean = false): JsonElement =
        client.delete("/v1/memory/$id" + if (purge) "?purge=true" else "")
    suspend fun searchMemory(query: String, k: Int = 8): JsonElement =
        client.post("/v1/memory/search", buildJsonObject { put("query", query); put("k", k) })

    // ---- Usage analytics + budgets ----
    suspend fun usageSummary(): JsonElement = client.get("/v1/usage")
    suspend fun usageDashboard(): JsonElement = client.get("/v1/dashboard")
    suspend fun budgets(): JsonElement = client.get("/v1/budgets")
    suspend fun alerts(): JsonElement = client.get("/v1/alerts")

    // ---- Durable tasks + progress ----
    suspend fun tasks(status: String? = null): JsonElement =
        client.get("/v1/tasks" + (status?.let { "?status=$it" } ?: ""))
    suspend fun task(taskId: String): JsonElement = client.get("/v1/tasks/$taskId")
    suspend fun taskEvents(taskId: String): JsonElement = client.get("/v1/tasks/$taskId/events")
    suspend fun approveTask(taskId: String, approve: Boolean): JsonElement =
        client.post("/v1/tasks/$taskId/approve", buildJsonObject { put("approve", approve) })
    suspend fun cancelTask(taskId: String): JsonElement =
        client.post("/v1/tasks/$taskId/cancel", buildJsonObject { })
    suspend fun pauseTask(taskId: String): JsonElement =
        client.post("/v1/tasks/$taskId/pause", buildJsonObject { })
    suspend fun resumeTask(taskId: String): JsonElement =
        client.post("/v1/tasks/$taskId/resume", buildJsonObject { })

    /** Live task progress over SSE with automatic reconnect + Last-Event-ID resume. */
    fun taskProgress(taskId: String): Flow<MinaSseEvent> =
        sse.stream("/v1/tasks/$taskId/events/stream")

    // ---- Conversations ----
    suspend fun conversations(): JsonElement = client.get("/v1/conversations")
    suspend fun conversation(id: String): JsonElement = client.get("/v1/conversations/$id")
    suspend fun messages(conversationId: String): JsonElement =
        client.get("/v1/conversations/$conversationId/messages")
    suspend fun appendMessage(conversationId: String, role: String, content: String): JsonElement =
        client.post("/v1/conversations/$conversationId/messages", buildJsonObject {
            put("role", role); put("content", content)
        })

    // ---- Cloud agent runs (orchestrator + approvals) ----
    suspend fun startRun(input: JsonElement): JsonElement =
        client.post("/v1/agent/runs", input)
    suspend fun run(runId: String): JsonElement = client.get("/v1/agent/runs/$runId")
    suspend fun runEvents(runId: String): JsonElement = client.get("/v1/agent/runs/$runId/events")
    suspend fun decideApproval(runId: String, approvalId: String, approve: Boolean): JsonElement =
        client.post("/v1/agent/runs/$runId/approvals/$approvalId", buildJsonObject { put("approve", approve) })
    suspend fun cancelRun(runId: String): JsonElement =
        client.post("/v1/agent/runs/$runId/cancel", buildJsonObject { })
    suspend fun deviceResults(runId: String, result: JsonElement): JsonElement =
        client.post("/v1/agent/runs/$runId/device-results", result)

    /** Live agent run stream. */
    fun runStream(runId: String): Flow<MinaSseEvent> = sse.stream("/v1/agent/runs/$runId/events")
}
