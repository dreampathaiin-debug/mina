package com.meenai.mina.core.composio

import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Connector tools: do the user's connected apps' work through their Composio account.
 * Provided only when the user has saved their Composio API key (ToolConfig.composio);
 * the accounts screen is where they link services.
 */
internal object ConnectorGroup {
    val tools: List<AgentTool> = listOf(ConnectorList, ConnectorActions, ConnectorRun)
}

/** Apps the user has connected, and how to get more. */
internal object ConnectorList : AgentTool {
    override val verb = Verb.ConnectorList
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val label = "List connected apps"
    override val def = ToolDef("connector_list", Prompts.tool("connector_list"))

    override fun available(ctx: ToolContext) = ctx.config.composio != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val accounts = ctx.config.composio!!.connectedAccounts()
        return ok {
            putJsonArray("connected") {
                accounts.forEach { acc ->
                    addJsonObject {
                        put("app", acc.toolkit)
                        put("status", acc.status)
                        put("account_id", acc.id)
                    }
                }
            }
            if (accounts.isEmpty()) put("note", "No apps connected yet. The user can link apps in Accounts > Connections (Connectors).")
        }
    }
}

/** Executable tools (actions) a connected toolkit offers, with their parameter schemas. */
internal object ConnectorActions : AgentTool {
    override val verb = Verb.ConnectorList
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val label = "List app actions"
    override val def = ToolDef("connector_actions", Prompts.tool("connector_actions"),
        mapOf("app" to str("Toolkit slug of the connected app, e.g. gmail, github, notion. Max 60 characters.")), listOf("app"))

    override fun available(ctx: ToolContext) = ctx.config.composio != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val app = a.str("app", max = 60)!!.lowercase().trim()
        val tools = ctx.config.composio!!.tools(app)
        if (tools.isEmpty()) return fail("not_found", "No tools found for app '$app'. Check the app slug with connector_list.")
        return ok {
            put("app", app)
            putJsonArray("actions") {
                tools.forEach { t ->
                    addJsonObject {
                        put("slug", t.slug)
                        put("description", t.description.take(300))
                        put("parameters", t.schema)
                    }
                }
            }
        }
    }
}

/** Run one action on a connected app: send mail, create docs, post messages, whatever the action does. */
internal object ConnectorRun : AgentTool {
    override val verb = Verb.ConnectorRun
    override val risk = Risk.Write
    override val label = "Run an app action"
    override val def = ToolDef("connector_run", Prompts.tool("connector_run"),
        mapOf(
            "tool" to str("Tool slug to run, exactly as connector_actions returned it, e.g. GMAIL_SEND_EMAIL. Max 120 characters."),
            "app" to str("Toolkit slug the tool belongs to, e.g. gmail. Used to pick the connected account. Max 60 characters."),
            "arguments" to buildJsonObject {
                put("type", "object")
                put("description", "Arguments object the tool's parameters schema requires.")
            },
        ), listOf("tool", "app"))

    override fun available(ctx: ToolContext) = ctx.config.composio != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val client = ctx.config.composio!!
        val tool = a.str("tool", max = 120)!!.trim()
        val app = a.str("app", max = 60)!!.lowercase().trim()
        val args = a.o["arguments"] as? JsonObject
            ?: throw ArgError("arguments", "must be a JSON object of the tool's arguments")
        val accountId = pickAccount(client, app)
            ?: return fail("not_connected", "No connected account for '$app'. The user can link it in Accounts > Connections.")
        return try {
            val r = client.execute(tool, args, accountId)
            val data = r["data"] ?: r["output"] ?: r
            ok {
                put("app", app)
                put("tool", tool)
                put("result", data)
            }
        } catch (e: ComposioClient.ComposioError) {
            fail("connector_error", "Composio rejected the call ($tool): ${e.message}", recoverable = true)
        }
    }

    /** Active account for the toolkit; one account or none is decided without asking. */
    private fun pickAccount(client: ComposioClient, app: String): String? {
        val accounts = client.connectedAccounts(limit = 100).filter { it.toolkit == app }
        return when {
            accounts.isEmpty() -> null
            accounts.size == 1 -> accounts[0].id
            else -> accounts.firstOrNull { it.status == "ACTIVE" }?.id
        }
    }
}
