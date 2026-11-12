package com.meenai.mina.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.meenai.mina.R
import com.meenai.mina.core.composio.ComposioClient
import com.meenai.mina.settings.ComposioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Accounts:连上的云服务(Composio 连接器,用户存了自己的 API key 就能直连服务)和网页登录。
 * 点一个网站直接用内置浏览器打开登录页,登录完 Mina 以后就能自己进去。
 */

/** 常用的主站:即使 Mina 还没访问过也列出来,点了就开登录页。 */
private val MAIN_SITES: List<Pair<String, String>> = listOf(
    "mail.google.com" to "Gmail",
    "calendar.google.com" to "Google Calendar",
    "drive.google.com" to "Google Drive",
    "linkedin.com" to "LinkedIn",
    "instagram.com" to "Instagram",
    "x.com" to "X",
    "facebook.com" to "Facebook",
    "youtube.com" to "YouTube",
    "github.com" to "GitHub",
    "notion.so" to "Notion",
    "amazon.in" to "Amazon",
    "flipkart.com" to "Flipkart",
    "chatgpt.com" to "ChatGPT",
    "reddit.com" to "Reddit",
    "swiggy.com" to "Swiggy",
    "zomato.com" to "Zomato",
    "paytm.com" to "Paytm",
    "phonepe.com" to "PhonePe",
    "stackoverflow.com" to "Stack Overflow",
)

@Composable
fun AccountsScreen(webSites: WebSitesState?, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PageScaffold(stringResource(R.string.accounts_title), onBack) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                AccountsTabs(tab, onPick = { tab = it })
            }
            if (tab == 0) {
                // Composio connectors always go through the Mina Cloud backend; the key lives server-side
                if (com.meenai.mina.server.ServerRuntime.enabled) ServerConnectionsContent() else ConnectionsSection()
            } else LoginsSection(webSites)
        }
    }
}

/** Connections / Logins 两个小胶囊,样式和 Home/Chat 分段一致。 */
@Composable
private fun AccountsTabs(tab: Int, onPick: (Int) -> Unit) {
    val options = listOf(tr(R.string.accounts_tab_connections), tr(R.string.accounts_tab_logins))
    Row(
        Modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = tab == i
            Box(
                Modifier
                    .background(if (selected) MinaTheme.colors.ink else MinaTheme.colors.fill, RoundedCornerShape(50))
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(label, style = MinaType.Caption, color = if (selected) MinaTheme.colors.bg else MinaTheme.colors.ink)
            }
        }
    }
}

@Composable
private fun ConnectionsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasKey by rememberSaveable { mutableStateOf(ComposioStore.key(context) != null) }
    var accounts by remember { mutableStateOf<List<ComposioClient.Account>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ComposioClient.Account?>(null) }

    fun refresh() {
        if (!hasKey) return
        busy = true
        scope.launch {
            accounts = try {
                ComposioStore.client(context)?.connectedAccounts()
            } catch (e: Exception) {
                error = e.message
                null
            }
            busy = false
        }
    }

    LaunchedEffect(hasKey) { if (hasKey) refresh() }

    fun connect(slug: String) {
        busy = true
        scope.launch {
            try {
                val client = ComposioStore.client(context)!!
                val url = client.createLink(slug, ComposioStore.userId(context))
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                for (attempt in 0 until 24) {
                    delay(4000)
                    val acc = try { client.connectedAccounts().firstOrNull { it.toolkit == slug } } catch (e: Exception) { null }
                    if (acc != null && acc.status != "INITIALIZING") break
                }
                refresh()
            } catch (e: Exception) {
                error = e.message
                busy = false
            }
        }
    }

    GroupLabel(tr(R.string.accounts_connectors))
    GroupCard {
        ListRow(MinaIcons.Link, tr(R.string.composio_row),
            subtitle = if (hasKey) tr(R.string.composio_row_on) else tr(R.string.composio_row_off),
            first = true,
            onClick = { showKeyDialog = true },
        ) {
            RowValue(if (hasKey) tr(R.string.composio_pill_on) else tr(R.string.composio_pill_off), if (hasKey) ValueTone.Ok else ValueTone.Warn)
        }
        if (hasKey) {
            val list = accounts
            if (list != null && list.isNotEmpty()) {
                list.forEachIndexed { i, acc ->
                    ListRow(
                        MinaIcons.Check, acc.toolkit.replaceFirstChar { it.uppercase() },
                        subtitle = acc.status.lowercase(),
                        first = false,
                        onClick = { removing = acc },
                    ) { RowValue(tr(R.string.composio_disconnect), ValueTone.Bad) }
                }
            }
            ListRow(
                MinaIcons.Plus, tr(R.string.composio_connect_app),
                subtitle = tr(R.string.composio_connect_sub), first = false,
                onClick = { if (!busy) showPicker = true },
            ) { RowValue(tr(R.string.web_open_login), ValueTone.Link) }
        }
    }
    FooterNote(tr(R.string.composio_note))
    error?.let {
        FooterNote(it, color = MinaTheme.colors.warn)
        LaunchedEffect(it) { kotlinx.coroutines.delay(6000); error = null }
    }

    if (showKeyDialog) KeyDialog(onDismiss = { showKeyDialog = false }) { key ->
        ComposioStore.saveKey(context, key)
        hasKey = true
        showKeyDialog = false
        refresh()
    }
    if (showPicker) AppPickerDialog(
        onDismiss = { showPicker = false },
        onPick = { slug -> showPicker = false; connect(slug) },
    )
    removing?.let { acc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr(R.string.composio_disconnect)) },
            text = { Text(tr(R.string.composio_disconnect_ask, acc.toolkit)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    busy = true
                    scope.launch {
                        try { ComposioStore.client(context)?.deleteAccount(acc.id) } catch (e: Exception) { error = e.message }
                        refresh()
                    }
                }) { Text(tr(R.string.composio_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr(R.string.composio_cancel)) } },
        )
    }
}

/** 填 Composio API key 的小对话框。 */
@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.composio_set_key)) },
        text = {
            Column {
                Text(tr(R.string.composio_key_hint_long))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    label = { Text(tr(R.string.composio_key_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (key.isNotBlank()) onSave(key) }) { Text(tr(R.string.composio_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.composio_cancel)) } },
    )
}

/** 挑一个服务去连:本地热门清单 + Composio 目录里按词搜。 */
@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ComposioClient.Toolkit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList(); searching = false; return@LaunchedEffect
        }
        searching = true
        withContext(Dispatchers.IO) {
            try { results = ComposioStore.client(context)?.toolkits(query.trim()) ?: emptyList() } catch (e: Exception) { results = emptyList() }
        }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.composio_connect_app)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(tr(R.string.composio_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.trim().length < 2) {
                    val popular = listOf("gmail", "googlecalendar", "googledrive", "slack", "notion", "github",
                        "youtube", "x", "instagram", "linkedin", "twitter", "airtable", "googlesheets",
                        "outlook", "discord", "telegram", "whatsapp", "spotify", "trello", "linear")
                    Text(tr(R.string.composio_popular), style = MinaType.Caption)
                    Column {
                        popular.forEach { slug ->
                            Text(
                                slug.replaceFirstChar { it.uppercase() },
                                style = MinaType.Body,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(slug) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                } else if (searching) {
                    Text(tr(R.string.composio_searching), style = MinaType.Caption)
                } else if (results.isEmpty()) {
                    Text(tr(R.string.composio_no_results), style = MinaType.Caption)
                } else {
                    Column {
                        results.forEach { t ->
                            Text(
                                if (t.name.equals(t.slug, true)) t.name else "${t.name} (${t.slug})",
                                style = MinaType.Body,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(t.slug) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.composio_cancel)) } },
    )
}

@Composable
private fun LoginsSection(webSites: WebSitesState?) {
    val seen = webSites?.sites?.map { it.host }?.toSet().orEmpty()
    GroupLabel(tr(R.string.accounts_logins))
    GroupCard {
        val visited = webSites?.sites.orEmpty()
        if (visited.isNotEmpty()) {
            visited.forEachIndexed { i, s ->
                ListRow(
                    MinaIcons.Globe, s.host,
                    subtitle = listOfNotNull(
                        tr(R.string.web_signed_in_by_you).takeIf { s.loggedInByUser },
                        tr(R.string.web_last_visit, day(s.lastVisitMs)),
                    ).joinToString(" · "),
                    first = i == 0,
                    onClick = { webSites?.onOpenLogin?.invoke(s.host) },
                ) { RowValue(tr(R.string.web_open_login), ValueTone.Link) }
            }
        }
        val extra = MAIN_SITES.filter { it.first !in seen }
        extra.forEachIndexed { i, (host, name) ->
            ListRow(
                MinaIcons.Globe, name, subtitle = host,
                first = visited.isEmpty() && i == 0,
                onClick = { webSites?.onOpenLogin?.invoke(host) },
            ) { RowValue(tr(R.string.web_open_login), ValueTone.Link) }
        }
        if (visited.isEmpty() && extra.isEmpty()) {
            ListRow(MinaIcons.Globe, tr(R.string.web_none), tr(R.string.web_none_sub), first = true)
        }
    }
    FooterNote(tr(R.string.accounts_logins_note))
}

private fun day(ms: Long): String = java.text.SimpleDateFormat(tr(R.string.date_pattern_md_compact), com.meenai.mina.i18n.uiLocale()).format(java.util.Date(ms))
