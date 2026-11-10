package com.meenai.mina.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meenai.mina.R
import com.meenai.mina.home.HomePanel
import com.meenai.mina.home.HomeViewModel
import com.meenai.mina.home.PanelsStore
import com.meenai.mina.home.SunPhase
import com.meenai.mina.home.Weather
import com.meenai.mina.home.WeatherData
import com.meenai.mina.home.sunPhase
import com.meenai.mina.home.weatherKind
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.GlassCompactTabs
import com.meenai.mina.ui.components.GlassPage
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.NotificationCard
import com.meenai.mina.ui.components.SwipeToDeleteCard
import com.meenai.mina.ui.components.SwipeToDeleteRow
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.WeatherCardModel
import com.meenai.mina.ui.components.WeatherCardView
import com.meenai.mina.ui.components.appearDown
import com.meenai.mina.ui.components.staggerIn
import com.meenai.mina.ui.components.weatherIconFor
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Home 页:顶部是可左滑删除的通知卡,然后常驻天气卡(自动定位、open-meteo),
 * 再往下的面板(agent 在盯的事)原样保留。原来的「Your apps」一段已撤。
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
    val vm: HomeViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 回到前台时看一眼天气(半小时内的缓存直接用,位置挪远了才重取)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val staggerBase = (if (updateAvailable) 1 else 0) + (if (phoneSetupNeeded) 1 else 0)

    GlassPage {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    "", left = MinaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = openDrawer,
                    center = pill,
                    right = { BarIconButton(MinaIcons.Gear, stringResource(R.string.settings_title), onSettings) },
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (updateAvailable) item(key = "sys-update") {
                        MinaCard(Modifier.staggerIn(0, 16.dp)) {
                            ActionCard(tr(R.string.home_update_verb), tr(R.string.home_update_title), tr(R.string.home_update_sub), first = true, onClick = onOpenAbout)
                        }
                    }
                    if (phoneSetupNeeded) item(key = "sys-phone") {
                        MinaCard(Modifier.staggerIn(if (updateAvailable) 1 else 0, 16.dp)) {
                            ActionCard(tr(R.string.home_fix_verb), tr(R.string.home_phone_title), tr(R.string.home_phone_sub), first = true, onClick = onPhoneSetup)
                        }
                    }
                    items(state.notifications, key = { "notif-" + it.key }) { n ->
                        Box(Modifier.animateItem().appearDown()) {
                            SwipeToDeleteCard(onDelete = {
                                vm.dismiss(n.key)
                                scope.launch {
                                    val r = snackbar.showSnackbar(
                                        tr(R.string.home_deleted),
                                        actionLabel = tr(R.string.home_undo),
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (r == SnackbarResult.ActionPerformed) vm.undoDismiss(n.key)
                                }
                            }) {
                                NotificationCard(
                                    verb = tr(n.verb),
                                    title = n.title,
                                    app = n.app,
                                    onClick = { openRelatedApp(context, n.app) },
                                )
                            }
                        }
                    }
                    item(key = "weather") { WeatherSection(vm, state.weather, state.isWeatherLoading, state.locationDenied, staggerBase) }
                    item(key = "panels") { PanelsSection(onAskAgent, staggerBase + 1) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

/** 顶栏中间的 Home/Chat 分段胶囊:56dp 高、内容定宽,黑色滑块随弹簧滑动。 */
@Composable
fun RootTabPill(home: Boolean, onPick: (Boolean) -> Unit) {
    GlassCompactTabs(
        options = listOf(tr(R.string.root_home), tr(R.string.root_chat)),
        selected = if (home) 0 else 1,
        onSelect = { i -> onPick(i == 0) },
    )
}

// ---------- 常驻天气卡 ----------

@Composable
private fun WeatherSection(
    vm: HomeViewModel,
    weather: WeatherData?,
    loading: Boolean,
    locationDenied: Boolean,
    staggerIndex: Int,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.onLocationGranted()
    }
    val model = weather?.let { weatherCardModel(it, context) }
    MinaCard(Modifier.staggerIn(staggerIndex, 16.dp)) {
        WeatherCardView(
            w = model,
            loading = loading,
            showEnableLocation = locationDenied,
            onEnableLocation = {
                launcher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            },
            rationale = if (locationDenied) tr(R.string.home_location_rationale) else null,
        )
    }
}

/** 天气数据 → 界面要的文字:天气名、温度、日落/下一次日出(按设备 12/24 小时制)。 */
private fun weatherCardModel(w: WeatherData, context: Context): WeatherCardModel {
    val now = LocalDateTime.now()
    val phase = sunPhase(w, now)
    val riseToday = w.todaySunrise?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val iso = when (phase) {
        SunPhase.Sunset -> w.sunset
        SunPhase.Sunrise -> if (riseToday != null && now < riseToday) w.todaySunrise else w.nextSunrise
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val fmt = DateTimeFormatter.ofPattern(pattern)
    return WeatherCardModel(
        place = w.place.ifBlank { null },
        condition = tr(weatherKind(w.code)),
        temp = "${w.temp.roundToInt()}°C",
        sunLabel = tr(if (phase == SunPhase.Sunset) R.string.panel_sunset else R.string.home_sunrise),
        sunTime = iso?.let { i -> runCatching { fmt.format(LocalDateTime.parse(i)) }.getOrNull() } ?: "—",
        icon = weatherIconFor(w.code, w.isDay),
    )
}

/** 点卡片打开通知来源的 App;没有可打开的就什么都不做。 */
private fun openRelatedApp(context: Context, pkg: String?) {
    if (pkg.isNullOrBlank()) return
    runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }
}

/** 顶栏下面的一张状态卡:动词胶囊 + 标题 + 说明,整卡可点。 */
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
private fun PanelsSection(onAskAgent: (String) -> Unit, staggerIndex: Int = 0) {
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

    panels.forEach { p ->
        val del: () -> Unit = { scope.launch { withContext(Dispatchers.IO) { store.remove(p.id) }; panels = store.all() } }
        SwipeToDeleteRow(onDelete = del) {
            if (p.kind == "weather") WeatherCard(p, onDelete = del)
            else TrackCard(p, onAsk = { onAskAgent(tr(R.string.panel_ask_msg, p.name)) }, onDelete = del)
        }
    }
    GroupCard(Modifier.staggerIn(staggerIndex, 16.dp)) {
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
