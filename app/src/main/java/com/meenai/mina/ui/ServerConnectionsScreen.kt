package com.meenai.mina.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.server.ServerRuntime
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Connectors: apps connected through Mina Cloud. The Composio key lives on
 * the server; the app only carries the Google sign-in session. Connection
 * status is read live from the backend, so "connected or not" is always
 * visible here.
 */

private val CONNECTOR_PRESETS = listOf(
    "gmail" to "Gmail",
    "googlecalendar" to "Google Calendar",
    "whatsapp" to "WhatsApp",
    "slack" to "Slack",
    "github" to "GitHub",
    "notion" to "Notion",
    "linkedin" to "LinkedIn",
    "spotify" to "Spotify",
    "youtube" to "YouTube",
)

private data class Connector(val id: String, val toolkit: String, val status: String)

@Composable
fun ServerConnectionsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = remember { ServerRuntime.obtain(context) }
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var connections by remember { mutableStateOf<List<Connector>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var newApp by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            try {
                val arr = server.api.connections().jsonObject["items"]?.jsonArray
                connections = arr?.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    Connector(id, (o["toolkit"] as? JsonPrimitive)?.contentOrNull ?: "?", (o["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown")
                } ?: emptyList()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        signedIn = ServerRuntime.signedIn(context)
        if (signedIn == true) refresh() else loading = false
    }

    fun connect(appRaw: String) {
        val app = appRaw.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        if (app.isEmpty()) return
        scope.launch {
            busy = app
            try {
                val req = server.api.connectApp(app).jsonObject
                val url = (req["redirect_url"] as? JsonPrimitive)?.contentOrNull
                if (url.isNullOrBlank()) throw IllegalStateException("no redirect_url")
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val id = (req["id"] as? JsonPrimitive)?.contentOrNull
                // OAuth tab is open in the browser: poll until Composio finishes the connection
                if (id != null) {
                    for (attempt in 0 until 30) {
                        delay(4000)
                        val st = runCatching {
                            (server.api.connectionStatus(id).jsonObject["status"] as? JsonPrimitive)?.contentOrNull
                        }.getOrNull()
                        if (st != null && st != "INITIALIZING" && st != "PENDING") break
                    }
                }
                refresh()
            } catch (e: Exception) {
                error = e.message
            }
            busy = null
        }
    }

    fun disconnect(conn: Connector) {
        scope.launch {
            try {
                server.api.disconnectApp(conn.id)
            } catch (e: Exception) {
                error = e.message
            }
            refresh()
        }
    }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                GroupCard {
                    ListRow(
                        MinaIcons.Link,
                        tr(if (signedIn == true) R.string.connectors_signed_in else R.string.connectors_signed_out),
                        onClick = {},
                    ) {
                        RowValue(
                            tr(if (signedIn == true) R.string.connectors_pill_on else R.string.connectors_pill_off),
                            if (signedIn == true) ValueTone.Ok else ValueTone.Warn,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GroupCard {
                    if (loading) {
                        Text(tr(R.string.connectors_loading), Modifier.padding(16.dp), style = MinaType.Body, color = MinaTheme.colors.ink2)
                    } else if (connections.isEmpty()) {
                        Text(tr(R.string.connectors_empty), Modifier.padding(16.dp), style = MinaType.Body, color = MinaTheme.colors.ink2)
                    }
                    connections.forEach { c ->
                        ListRow(MinaIcons.Link, c.toolkit, c.status.lowercase(), onClick = { disconnect(c) }) {
                            RowValue(tr(R.string.connectors_remove), ValueTone.Warn)
                        }
                    }
                    error?.let { FooterNote(it) }
                }
                Spacer(Modifier.height(12.dp))
                GroupCard {
                    GroupLabel(tr(R.string.connectors_add_title))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newApp, onValueChange = { newApp = it }, singleLine = true,
                            label = { Text(tr(R.string.connectors_add_hint)) },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { connect(newApp) }) { Text(tr(R.string.connectors_connect)) }
                    }
                    GroupLabel(tr(R.string.connectors_presets_title))
                    CONNECTOR_PRESETS.chunked(3).forEach { rowApps ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowApps.forEach { (slug, label) ->
                                val active = busy == slug
                                Box(
                                    Modifier
                                        .background(if (active) MinaTheme.colors.ink else MinaTheme.colors.fill, RoundedCornerShape(50))
                                        .clickable(enabled = busy == null) { connect(slug) }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                ) {
                                    Text(label, style = MinaType.Caption, color = if (active) MinaTheme.colors.bg else MinaTheme.colors.ink)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GroupCard {
                    ListRow(MinaIcons.Link, tr(R.string.connectors_sign_out), onClick = {
                        scope.launch {
                            ServerRuntime.signOut(context)
                            signedIn = false
                            connections = emptyList()
                        }
                    }) {}
                }
            }
        }
}
