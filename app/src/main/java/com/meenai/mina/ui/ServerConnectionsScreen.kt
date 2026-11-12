package com.meenai.mina.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
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
 * visible here, and the full toolkit catalog comes from the server too.
 */

private data class Connector(val id: String, val toolkit: String, val status: String)

private data class CatalogApp(val slug: String, val name: String, val description: String, val logo: String)

/** Bundled fallback badges: vector glyph (white) on the app's brand colour. */
private val CONNECTOR_ICONS: Map<String, Pair<Int, Long>> = mapOf(
    "gmail" to (R.drawable.ic_conn_gmail to 0xFFEA4335),
    "googlecalendar" to (R.drawable.ic_conn_googlecalendar to 0xFF4285F4),
    "whatsapp" to (R.drawable.ic_conn_whatsapp to 0xFF25D366),
    "slack" to (R.drawable.ic_conn_slack to 0xFF4A154B),
    "github" to (R.drawable.ic_conn_github to 0xFF181717),
    "notion" to (R.drawable.ic_conn_notion to 0xFF000000),
    "linkedin" to (R.drawable.ic_conn_linkedin to 0xFF0A66C2),
    "spotify" to (R.drawable.ic_conn_spotify to 0xFF1DB954),
    "youtube" to (R.drawable.ic_conn_youtube to 0xFFFF0000),
)

/** Catalog icon tile: the app's real logo when the server has one, else the bundled badge, else an initial. */
@Composable
private fun CatalogIcon(slug: String, name: String, logo: String) {
    val badge = CONNECTOR_ICONS[slug]
    Box(
        Modifier
            .size(44.dp)
            .background(MinaTheme.colors.fill, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            logo.isNotBlank() -> AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(26.dp),
            )
            badge != null -> Box(
                Modifier
                    .size(26.dp)
                    .background(Color(badge.second), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(badge.first), null, Modifier.size(15.dp))
            }
            else -> Box(
                Modifier
                    .size(26.dp)
                    .background(MinaTheme.colors.ink2.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MinaType.Caption, color = MinaTheme.colors.ink)
            }
        }
    }
}

@Composable
fun ServerConnectionsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = remember { ServerRuntime.obtain(context) }
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(true) }
    var catalogLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connections by remember { mutableStateOf<List<Connector>>(emptyList()) }
    var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }

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

    fun loadCatalog() {
        scope.launch {
            catalogLoading = true
            try {
                val arr = server.api.catalogApps().jsonObject["items"]?.jsonArray
                apps = arr?.mapNotNull { el ->
                    val o = el.jsonObject
                    val slug = (o["slug"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    CatalogApp(
                        slug = slug,
                        name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: slug,
                        description = (o["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                        logo = (o["logo"] as? JsonPrimitive)?.contentOrNull ?: "",
                    )
                } ?: emptyList()
            } catch (e: Exception) {
                // catalog is best-effort; connecting an app still works without it
            }
            catalogLoading = false
        }
    }

    LaunchedEffect(Unit) {
        signedIn = ServerRuntime.signedIn(context)
        if (signedIn == true) {
            refresh()
            loadCatalog()
        } else {
            loading = false
        }
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

    Column(Modifier.padding(bottom = 24.dp)) {
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
                if (catalogLoading) {
                    Text(tr(R.string.connectors_loading), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MinaType.Body, color = MinaTheme.colors.ink2)
                }
                apps.forEach { a ->
                    val connected = connections.any {
                        it.toolkit.equals(a.slug, ignoreCase = true) && it.status.equals("ACTIVE", ignoreCase = true)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CatalogIcon(a.slug, a.name, a.logo)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(a.name, style = MinaType.Body, color = MinaTheme.colors.ink)
                            if (a.description.isNotBlank()) {
                                Text(
                                    a.description,
                                    style = MinaType.Caption,
                                    color = MinaTheme.colors.ink2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (connected) {
                            val conn = connections.first {
                                it.toolkit.equals(a.slug, ignoreCase = true) && it.status.equals("ACTIVE", ignoreCase = true)
                            }
                            TextButton(enabled = busy == null, onClick = { disconnect(conn) }) {
                                Text(tr(R.string.connectors_remove), color = MinaTheme.colors.ink2)
                            }
                        } else {
                            TextButton(enabled = busy == null, onClick = { connect(a.slug) }) {
                                Text(if (busy == a.slug) "…" else tr(R.string.connectors_connect))
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
                        apps = emptyList()
                    }
                }) {}
            }
        }
    }
}
