package com.meenai.mina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.browser.BrowserTab
import com.meenai.mina.browser.FindState
import com.meenai.mina.browser.RecentItem
import com.meenai.mina.browser.SiteFace
import com.meenai.mina.browser.StartPageModel
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.Hairline
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaDialog
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.delay

/** 起点页右上角的菜单:标签页、历史记录、清除浏览数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuSheet(tabsCount: Int, onDismiss: () -> Unit, onTabs: () -> Unit, onHistory: () -> Unit, onClearData: () -> Unit) {
    val c = MinaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-menu")) {
            fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
            ListRow(
                MinaIcons.Apps, tr(R.string.browser_tabs), detail = tabsCount.takeIf { it > 0 }?.toString(),
                first = true, onClick = act(onTabs),
            )
            ListRow(MinaIcons.Clock, tr(R.string.browser_history), onClick = act(onHistory))
            ListRow(MinaIcons.Trash, tr(R.string.browser_clear_data), onClick = act(onClearData))
        }
    }
}

/** 标签页面板:打开着的标签,点一下换过去,× 关掉;右上角新建。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(
    tabs: List<BrowserTab>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    val c = MinaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-tabs")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.browser_tabs), Modifier.weight(1f), style = MinaType.Title, color = c.ink)
                Text(
                    tr(R.string.browser_new_tab),
                    Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = onNew).padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MinaType.BodyStrong, color = c.accent,
                )
            }
            if (tabs.isEmpty()) {
                Text(
                    tr(R.string.browser_no_tabs), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MinaType.Body, color = c.ink3,
                )
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                tabs.forEachIndexed { i, t ->
                    if (i > 0) Hairline(start = 56.dp)
                    ListRow(
                        MinaIcons.Globe,
                        t.title.ifBlank { tr(R.string.browser_new_tab) },
                        subtitle = t.host ?: t.url,
                        first = true,
                        titleColor = if (t.active) c.accent else Color.Unspecified,
                        onClick = { onSelect(t.id) },
                        trailing = {
                            val close = tr(R.string.browser_close_tab)
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = close) { onClose(t.id) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(MinaIcons.Close, close, Modifier.size(16.dp), tint = c.ink3) }
                        },
                    )
                }
            }
        }
    }
}

/** 完整历史:全部记录,整理时点 × 删一条;底部清除全部。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    pages: List<RecentItem>,
    now: Long,
    onDismiss: () -> Unit,
    onOpen: (RecentItem) -> Unit,
    onRemove: (RecentItem) -> Unit,
    onClear: () -> Unit,
) {
    val c = MinaTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-history")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.browser_history), Modifier.weight(1f), style = MinaType.Title, color = c.ink)
                if (pages.isNotEmpty()) {
                    Text(
                        if (editing) tr(R.string.done) else tr(R.string.browser_edit),
                        Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button) { editing = !editing }.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MinaType.BodyStrong, color = c.accent,
                    )
                }
            }
            if (pages.isEmpty()) {
                Text(
                    tr(R.string.browser_no_recent), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MinaType.Body, color = c.ink3,
                )
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                pages.forEachIndexed { i, r ->
                    if (i > 0) Hairline(start = 56.dp)
                    ListRow(
                        if (r.search) MinaIcons.Search else MinaIcons.Globe,
                        r.title, subtitle = r.subtitle, detail = StartPageModel.relativeTime(r.openedAtMs, now),
                        first = true,
                        onClick = { if (!editing) onOpen(r) },
                        trailing = {
                            if (editing) {
                                val remove = tr(R.string.browser_remove_site, r.title)
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = remove) { onRemove(r) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(MinaIcons.Close, remove, Modifier.size(16.dp), tint = c.ink3) }
                            }
                        },
                    )
                }
            }
            if (pages.isNotEmpty()) {
                TextButton(
                    onClick = { clearing = true }, modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                ) { Text(tr(R.string.browser_clear_history), color = c.bad) }
            }
        }
    }
    if (clearing) {
        MinaDialog(
            onDismissRequest = { clearing = false },
            title = { Text(tr(R.string.browser_clear_history_q)) },
            confirmButton = {
                TextButton(onClick = { clearing = false; editing = false; onClear() }) { Text(tr(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** 清除浏览数据:勾选历史、网站数据、缓存,一起清。网站数据会退出所有网站。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearDataSheet(onDismiss: () -> Unit, onClear: (history: Boolean, siteData: Boolean, cache: Boolean) -> Unit) {
    val c = MinaTheme.colors
    var history by rememberSaveable { mutableStateOf(true) }
    var siteData by rememberSaveable { mutableStateOf(true) }
    var cache by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-clear-data")) {
            Text(
                tr(R.string.browser_clear_data), Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                style = MinaType.Title, color = c.ink,
            )
            ListRow(MinaIcons.Clock, tr(R.string.browser_history), first = true, trailing = { MinaSwitch(history, { history = it }) })
            ListRow(
                MinaIcons.Globe, tr(R.string.browser_clear_site_data), subtitle = tr(R.string.browser_clear_site_data_sub),
                trailing = { MinaSwitch(siteData, { siteData = it }) },
            )
            ListRow(MinaIcons.Refresh, tr(R.string.browser_clear_cache), trailing = { MinaSwitch(cache, { cache = it }) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tr(R.string.cancel)) }
                TextButton(
                    onClick = { onClear(history, siteData, cache) },
                    enabled = history || siteData || cache,
                    modifier = Modifier.weight(1f),
                ) { Text(tr(R.string.clear)) }
            }
        }
    }
}

/** 页内查找条:输入后自动搜,显示第几处/共几处,上一个、下一个、关闭。 */
@Composable
fun BrowserFindBar(find: FindState, onQuery: (String) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val c = MinaTheme.colors
    var text by remember(find.active) { mutableStateOf(find.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(text) {
        delay(200)
        onQuery(text)
    }
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).background(c.fill, MinaShapes.Pill).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MinaIcons.Search, null, Modifier.size(16.dp), tint = c.ink3)
            BasicTextField(
                text, { text = it },
                Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(focus).testTag("browser-find-input"),
                singleLine = true,
                textStyle = MinaType.Body.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        val count = when {
            find.query.isBlank() -> ""
            find.matches == 0 -> tr(R.string.browser_find_no_matches)
            else -> "${find.index}/${find.matches}"
        }
        if (count.isNotEmpty()) {
            Text(count, style = MinaType.Hint, color = c.ink3, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
        FindAction(MinaIcons.Back, tr(R.string.browser_find_prev), onPrev)
        FindAction(MinaIcons.Forward, tr(R.string.browser_find_next), onNext)
        FindAction(MinaIcons.Close, tr(R.string.browser_find_close), onClose)
    }
}

@Composable
private fun FindAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(17.dp), tint = MinaTheme.colors.ink3) }
}
