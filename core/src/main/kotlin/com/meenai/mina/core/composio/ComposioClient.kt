package com.meenai.mina.core.composio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Composio REST v3.1 client (user's own API key, BYOK). Accounts/Connectors in the app use it to
 * link services; the connector_* tools use it to run actions on the user's behalf.
 * Key stays on the device; every call carries it in the x-api-key header.
 */
class ComposioClient(private val apiKey: String, private val baseUrl: String = BASE) {

    class ComposioError(val code: Int, message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    data class Toolkit(val slug: String, val name: String, val logo: String)
    data class Account(val id: String, val toolkit: String, val status: String)
    data class Tool(val slug: String, val description: String, val schema: JsonObject)

    private fun request(method: String, path: String, body: JsonObject? = null, query: Map<String, String> = emptyMap()): JsonObject {
        val url = buildString {
            append(baseUrl); append(path)
            if (query.isNotEmpty()) append('?').append(query.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            })
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ComposioError(code, "HTTP $code: ${text.take(400)}")
            return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun items(o: JsonObject): List<JsonObject> = (o["items"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    /** Search toolkits (apps) by keyword; [q] empty lists the first page. */
    fun toolkits(q: String = "", limit: Int = 40): List<Toolkit> =
        items(request("GET", "/toolkits", query = buildMap {
            if (q.isNotBlank()) put("q", q)
            put("limit", limit.toString())
        })).mapNotNull { t ->
            val slug = t.s("slug") ?: return@mapNotNull null
            Toolkit(slug, t.s("name") ?: slug, t.s("logo") ?: "")
        }

    fun connectedAccounts(limit: Int = 50): List<Account> =
        items(request("GET", "/connected_accounts", query = mapOf("limit" to limit.toString()))).mapNotNull { a ->
            val id = a.s("id") ?: return@mapNotNull null
            val slug = ((a["toolkit"] as? JsonObject)?.get("slug") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@mapNotNull null
            Account(id, slug, a.s("status") ?: "UNKNOWN")
        }

    /** The toolkit's auth config: find an existing one, else create a Composio-managed one. */
    fun authConfigId(slug: String): String {
        request("GET", "/auth_configs", query = mapOf("toolkit_slugs" to slug, "limit" to "5"))
            .let { r -> items(r).firstOrNull()?.s("id")?.let { return it } }
        val created = request("POST", "/auth_configs", buildJsonObject {
            put("toolkit", slug)
            put("composio_managed_auth_config", true)
        })
        return created.s("id") ?: ((created["auth_config"] as? JsonObject)?.s("id")
            ?: throw ComposioError(0, "Could not set up auth for $slug"))
    }

    /** Create an auth link session and return the URL the user opens in their browser. */
    fun createLink(slug: String, userId: String): String {
        val r = request("POST", "/connected_accounts/link", buildJsonObject {
            put("auth_config_id", authConfigId(slug))
            put("user_id", userId)
        })
        return firstString(r) ?: throw ComposioError(0, "No auth link returned for $slug")
    }

    /** First string value under any of the known redirect-url keys, anywhere in the response. */
    private fun firstString(o: JsonObject): String? {
        for (k in listOf("redirect_url", "redirectUrl", "composio_link_redirect_url", "url", "auth_url", "link")) {
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
        }
        o.values.forEach { v -> (v as? JsonObject)?.let { firstString(it)?.let { s -> return s } } }
        return null
    }

    fun deleteAccount(id: String) {
        request("DELETE", "/connected_accounts/$id")
    }

    /** Executable tools of one toolkit (their input schemas ride along for connector_run). */
    fun tools(toolkit: String, limit: Int = 40): List<Tool> =
        items(request("GET", "/tools", query = mapOf("toolkits" to toolkit, "limit" to limit.toString())))
            .mapNotNull { t ->
                val slug = t.s("slug") ?: t.s("name") ?: return@mapNotNull null
                Tool(slug, t.s("description") ?: "", t["input_parameters"] as? JsonObject ?: JsonObject(emptyMap()))
            }

    /** Run one tool with JSON arguments; pass the account id when several accounts exist. */
    fun execute(slug: String, args: JsonObject, accountId: String?): JsonObject {
        val body = buildJsonObject {
            put("version", "latest")
            put("arguments", args)
            if (!accountId.isNullOrBlank()) put("connected_account_id", accountId)
        }
        return request("POST", "/tools/execute/$slug", body)
    }

    companion object {
        const val BASE = "https://backend.composio.dev/api/v3.1"
    }
}
