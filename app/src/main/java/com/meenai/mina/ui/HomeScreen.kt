package com.meenai.mina.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.meenai.mina.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.home.HomePanel
import com.meenai.mina.cap.phone.shell.NotificationStore
import com.meenai.mina.home.PanelsStore
import com.meenai.mina.home.Weather
import com.meenai.mina.home.weatherKind
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.staggerIn
import com.meenai.mina.ui.components.SwipeToDeleteRow
import com.meenai.mina.ui.components.GlassPillTabs
import com.meenai.mina.ui.components.GlassPage
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home 页:Hark 风格的重要通知、agent 留意的面板,和手机上已装的 App(点了直接打开)。
 * Chat 的那段应用横条保持原样,这里是一份可以点击打开的完整网格。
 */
@Composable
fun HomeScreen(
    openDrawer: () -> Unit,
    onSettings: () -> Unit,
    pill: @Composable () -> Unit,
    updateAvailable: Boolean,
    onOpenAbout: () -> Unit,
    phoneSetupNeeded: Boolean,
    onPhoneSetup: () -> Unit,
    /** 把一句话交给 agent 去办(发进当前对话,并回到 Chat 页看回复)。 */
    onAskAgent: (String) -> Unit,
) {
    val c = MinaTheme.colors
    GlassPage {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                "", left = MinaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = openDrawer,
                center = pill,
                right = { BarIconButton(MinaIcons.Gear, stringResource(R.string.settings_title), onSettings) },
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                Column(Modifier.staggerIn(0)) { ImportantSection(updateAvailable, onOpenAbout, phoneSetupNeeded, onPhoneSetup) }
                Column(Modifier.staggerIn(1)) { PanelsSection(onAskAgent) }
                Column(Modifier.staggerIn(2)) { AppsSection() }
            }
        }
    }
}

/** 顶栏中间的 Home/Chat 分段胶囊:玻璃底,黑色滑块随弹簧滑动。 */
@Composable
fun RootTabPill(home: Boolean, onPick: (Boolean) -> Unit) {
    GlassPillTabs(
        options = listOf(tr(R.string.root_home), tr(R.string.root_chat)),
        selected = if (home) 0 else 1,
        onSelect = { i -> onPick(i == 0) },
        height = 34.dp,
    )
}

// ---------- 重要通知 ----------

@Composable
private fun ImportantSection(updateAvailable: Boolean, onOpenAbout: () -> Unit, phoneSetupNeeded: Boolean, onPhoneSetup: () -> Unit) {
    val notes = remember { NotificationStore.snapshot(null, 3) }
    val anyCard = updateAvailable || phoneSetupNeeded || notes.isNotEmpty()
    GroupLabel(tr(R.string.home_important))
    if (!anyCard) {
        GroupCard {
            ListRow(MinaIcons.Check, tr(R.string.home_caught_up), tr(R.string.home_caught_up_sub), first = true)
        }
        return
    }
    if (updateAvailable) {
        GroupCard {
            ActionCard(tr(R.string.home_update_verb), tr(R.string.home_update_title), tr(R.string.home_update_sub), first = true, onClick = onOpenAbout)
        }
    }
    if (phoneSetupNeeded) {
        GroupCard {
            ActionCard(tr(R.string.home_fix_verb), tr(R.string.home_phone_title), tr(R.string.home_phone_sub), first = true, onClick = onPhoneSetup)
        }
    }
    if (notes.isNotEmpty()) {
        GroupCard {
            notes.forEachIndexed { i, n ->
                ActionCard(
                    tr(R.string.home_note_verb),
                    jsonText(n, "title").ifBlank { jsonText(n, "text") },
                    listOf(jsonText(n, "text"), jsonText(n, "app")).filter { it.isNotBlank() }.joinToString(" · "),
                    first = i == 0,
                )
            }
        }
    }
}

private fun jsonText(o: JsonObject, key: String): String =
    (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()


/** Hark 风格的通知卡:左边动词小胶囊,右边一句话。 */
@Composable
private fun ActionCard(verb: String, title: String, subtitle: String, first: Boolean, onClick: (() -> Unit)? = null) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(verb, style = MinaType.Caption, color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MinaType.Body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 面板 ----------

@Composable
private fun PanelsSection(onAskAgent: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { PanelsStore.shared(context) }
    val scope = rememberCoroutineScope()
    var panels by remember { mutableStateOf<List<HomePanel>>(emptyList()) }
    var create by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        panels = withContext(Dispatchers.IO) { store.all() }
        // 天气卡在打开时刷新:半小时内的旧数据直接用
        val stale = panels.filter { it.kind == "weather" && it.lat != null && it.lon != null && (it.temp == null || it.updatedAt < System.currentTimeMillis() - 30 * 60_000L) }
        if (stale.isNotEmpty()) {
            val fresh = stale.mapNotNull { p ->
                val now = withContext(Dispatchers.IO) { Weather.now(p.lat!!, p.lon!!) } ?: return@mapNotNull null
                p.copy(temp = now.temp, code = now.code, sunset = now.sunset, updatedAt = System.currentTimeMillis())
            }
            fresh.forEach { withContext(Dispatchers.IO) { store.put(it) } }
            panels = withContext(Dispatchers.IO) { store.all() }
        }
    }

    GroupLabel(tr(R.string.home_panels))
    panels.forEach { p ->
        val del: () -> Unit = { scope.launch { withContext(Dispatchers.IO) { store.remove(p.id) }; panels = store.all() } }
        SwipeToDeleteRow(onDelete = del) {
            if (p.kind == "weather") WeatherCard(p, onDelete = del)
            else TrackCard(p, onAsk = { onAskAgent(tr(R.string.panel_ask_msg, p.name)) }, onDelete = del)
        }
    }
    GroupCard {
        Row(
            Modifier.fillMaxWidth().clickable { create = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MinaIcons.Plus, null, Modifier.size(20.dp), tint = MinaTheme.colors.ink)
            Text(tr(R.string.panel_create), style = MinaType.BodyStrong, color = MinaTheme.colors.ink)
        }
    }
    if (create) PanelCreateDialog(
        onDismiss = { create = false; failed = false },
        onCreate = { kind, text ->
            scope.launch {
                if (kind == "weather") {
                    val place = withContext(Dispatchers.IO) { Weather.locate(text) }
                    if (place == null) { failed = true; return@launch }
                    val now = withContext(Dispatchers.IO) { Weather.now(place.first, place.second) }
                    val panel = HomePanel(
                        id = "w${System.currentTimeMillis()}", kind = "weather", name = text.trim(),
                        lat = place.first, lon = place.second,
                        temp = now?.temp, code = now?.code, sunset = now?.sunset, updatedAt = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { store.put(panel) }
                } else {
                    withContext(Dispatchers.IO) { store.put(HomePanel("t${System.currentTimeMillis()}", "track", text.trim(), updatedAt = System.currentTimeMillis())) }
                }
                panels = withContext(Dispatchers.IO) { store.all() }
                create = false
            }
        },
        failed = failed,
    )
}

@Composable
private fun WeatherCard(p: HomePanel, onDelete: () -> Unit) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MinaIcons.Sun, null, Modifier.size(18.dp), tint = c.ink2)
            Text(p.name + (p.country?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""), Modifier.weight(1f).padding(start = 8.dp), style = MinaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BarIconButton(MinaIcons.Trash, "", onDelete)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelTile(Modifier.weight(1f), tr(weatherKind(p.code)), if (p.temp != null) "${p.temp.toInt()}°C" else "—")
            PanelTile(Modifier.weight(1f), tr(R.string.panel_sunset), p.sunset?.takeIf { it.isNotBlank() }?.split("T")?.lastOrNull()?.take(5) ?: "—")
        }
    }
}

@Composable
private fun PanelTile(modifier: Modifier = Modifier, label: String, value: String) {
    val c = MinaTheme.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MinaType.Caption, color = c.ink3)
        Text(value, style = MinaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackCard(p: HomePanel, onAsk: () -> Unit, onDelete: () -> Unit) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(bottom = 12.dp).clickable(onClick = onAsk)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(tr(R.string.panel_tracking), style = MinaType.Caption, color = c.ink)
            }
            Text(p.name, Modifier.weight(1f).padding(start = 10.dp), style = MinaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BarIconButton(MinaIcons.Trash, "", onDelete)
        }
        Text(
            tr(R.string.panel_updated, panelTime(p.updatedAt)),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MinaType.Caption, color = c.ink3,
        )
    }
}

private fun panelTime(atMs: Long): String =
    if (atMs <= 0) "" else DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(atMs))

@Composable
private fun PanelCreateDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit, failed: Boolean) {
    val c = MinaTheme.colors
    var kind by remember { mutableStateOf("weather") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.panel_new_title), style = MinaType.Title, color = c.ink) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(tr(R.string.panel_kind_weather), kind == "weather") { kind = "weather" }
                    KindChip(tr(R.string.panel_kind_track), kind == "track") { kind = "track" }
                }
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.ink),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        .clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box { if (text.isEmpty()) Text(if (kind == "weather") tr(R.string.panel_city_hint) else tr(R.string.panel_topic_hint), style = MinaType.Body, color = c.ink3); inner }
                    },
                )
                if (failed) Text(tr(R.string.panel_weather_fail), Modifier.padding(top = 8.dp), style = MinaType.Caption, color = c.bad)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onCreate(kind, text) }) { Text(tr(R.string.panel_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun KindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MinaTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.ink else c.fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MinaType.Caption, color = if (selected) c.bg else c.ink)
    }
}

// ---------- 已装的 App ----------

private data class AppEntry(val label: String, val pkg: String, val icon: Bitmap?)

@Composable
private fun AppsSection() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(context) } }
    GroupLabel(tr(R.string.home_apps))
    GroupCard {
        val rows = (apps ?: emptyList()).chunked(4)
        if (apps == null) {
            ListRow(MinaIcons.Apps, tr(R.string.home_apps_loading), first = true)
            return@GroupCard
        }
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                row.forEach { a -> AppTile(a, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (i != rows.lastIndex) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(MinaTheme.colors.hair))
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != self }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                runCatching {
                    val label = info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: info.activityInfo.packageName
                    val icon = runCatching { pm.getApplicationIcon(info.activityInfo.packageName).toBitmap(96, 96) }.getOrNull()
                    AppEntry(label, info.activityInfo.packageName, icon)
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

@Composable
private fun AppTile(a: AppEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = MinaTheme.colors
    Column(
        modifier.clickable {
            context.packageManager.getLaunchIntentForPackage(a.pkg)?.let { context.startActivity(it) }
        }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(c.fill), contentAlignment = Alignment.Center) {
            a.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(30.dp)) }
                ?: Icon(MinaIcons.Apps, null, Modifier.size(22.dp), tint = c.ink2)
        }
        Text(a.label, Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp), style = MinaType.Hint, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
