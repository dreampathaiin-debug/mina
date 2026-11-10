package com.meenai.mina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
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
 * Accounts:连上的云服务(MCP 连接器还没接,一律「Coming soon」)和网页登录。
 * 点一个网站直接用内置浏览器打开登录页,登录完 Mina 以后就能自己进去。
 */

/** 云服务连接:MCP 连接器还没上线,先占位。 */
private val CONNECTIONS = listOf(
    "Google" to "Gmail · Calendar · Drive",
    "Microsoft 365" to "Outlook · Calendar · OneDrive",
    "Slack" to "Workspaces · Channels",
    "GitHub" to "Issues · Pull requests",
    "Atlassian" to "Jira · Confluence",
    "Linear" to "Issues · Projects",
)

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
            if (tab == 0) ConnectionsSection() else LoginsSection(webSites)
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
    GroupLabel(tr(R.string.accounts_connections))
    GroupCard {
        CONNECTIONS.forEachIndexed { i, (name, detail) ->
            ListRow(MinaIcons.Link, name, subtitle = detail, first = i == 0) {
                StatusPill(tr(R.string.accounts_coming_soon))
            }
        }
    }
    FooterNote(tr(R.string.accounts_conns_note))
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
