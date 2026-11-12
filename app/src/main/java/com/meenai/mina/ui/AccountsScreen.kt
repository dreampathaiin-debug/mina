package com.meenai.mina.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meenai.mina.R
import androidx.core.net.toUri
import com.meenai.mina.i18n.tr
import com.meenai.mina.server.ServerRuntime
import com.meenai.mina.ui.accounts.AccountItem
import com.meenai.mina.ui.accounts.AccountStatus
import com.meenai.mina.ui.accounts.AccountTabs
import com.meenai.mina.ui.accounts.AccountType
import com.meenai.mina.ui.accounts.AccountsUiState
import com.meenai.mina.ui.accounts.AccountsViewModel
import com.meenai.mina.ui.accounts.ConnectedAccountCard
import com.meenai.mina.ui.accounts.ConnectSheet
import com.meenai.mina.ui.accounts.GlassCardLite
import com.meenai.mina.ui.accounts.GroupedAccountRow
import com.meenai.mina.ui.components.GlassBaseDark
import com.meenai.mina.ui.components.GlassBaseLight
import com.meenai.mina.ui.components.GlassBlurSupported
import com.meenai.mina.ui.components.GlassPage
import com.meenai.mina.ui.components.LocalHazeState
import com.meenai.mina.ui.components.pressScale
import com.meenai.mina.ui.components.staggerIn
import com.meenai.mina.ui.theme.InterTight
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** 每页一条:可选的小节标签 + 行。 */
private data class AccountSection(val label: String?, val rows: List<AccountItem>)

/**
 * Connected Accounts(Settings > Accounts 进来)。UI only 的 mock 版:
 * 数据在 [AccountsViewModel],OAuth / 加密存储都留了 TODO 钩子。
 * 布局:钉住的 haze 顶栏(内容从下面滚过去被模糊)→ 已连卡片 → 玻璃分段标签
 * (HorizontalPager 同步,左右滑也切页)→ 分组玻璃卡 → 底部悬浮玻璃搜索。
 */
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    val vm: AccountsViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val server = remember { ServerRuntime.obtain(context) }
    LaunchedEffect(Unit) {
        val signed = runCatching { ServerRuntime.signedIn(context) }.getOrNull() == true
        vm.attach(if (signed) server else null, signed)
    }
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    AccountsContent(
        state = state,
        onBack = onBack,
        onTab = vm::onTab,
        onQuery = vm::onQuery,
        onOpen = vm::openSheet,
        onRemove = vm::remove,
        onDismiss = vm::dismissSheet,
        onConnect = { item -> vm.connect(item, openUrl) },
        onSaveLogin = vm::saveLogin,
        onSaveKey = vm::saveKey,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountsContent(
    state: AccountsUiState,
    onBack: () -> Unit,
    onTab: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (AccountItem) -> Unit,
    onRemove: (AccountItem) -> Unit,
    onDismiss: () -> Unit = {},
    onConnect: (AccountItem) -> Unit = {},
    onSaveLogin: (String) -> Unit = {},
    onSaveKey: (String) -> Unit = {},
) {
    val c = MinaTheme.colors
    val haze = LocalHazeState.current
    val density = LocalDensity.current
    val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val topPad = statusBar + 64.dp + 8.dp + 44.dp + 12.dp
    val bottomPad = navBar + 56.dp + 12.dp + 20.dp

    var deleting by remember { mutableStateOf<AccountItem?>(null) }

    GlassPage {
        Box(Modifier.fillMaxSize()) {
            val pagerState = rememberPagerState(initialPage = state.selectedTab, pageCount = { 4 })
            LaunchedEffect(state.selectedTab) {
                if (pagerState.settledPage != state.selectedTab && pagerState.currentPage != state.selectedTab) {
                    pagerState.animateScrollToPage(state.selectedTab)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    if (page != state.selectedTab) onTab(page)
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val sections = sectionsFor(page, state)
                LazyColumn(
                    Modifier.fillMaxSize()
                        .then(if (haze != null && GlassBlurSupported) Modifier.haze(haze) else Modifier),
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.signedIn == false && (page == 0 || page == 1)) {
                        item(key = "signedout") {
                            Text(
                                tr(R.string.connectors_signed_out),
                                Modifier.padding(horizontal = 32.dp),
                                style = MinaType.Hint,
                                color = c.ink3,
                            )
                        }
                    }
                    state.error?.let { err ->
                        item(key = "error") {
                            Text(
                                err,
                                Modifier.padding(horizontal = 32.dp),
                                style = MinaType.Hint,
                                color = c.bad,
                                maxLines = 2,
                            )
                        }
                    }
                    connectedSection(state.connected, onOpen, onRemove)
                    if (sections.isEmpty()) {
                        item(key = "empty") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                Text(tr(R.string.accounts_no_match), style = MinaType.Body, color = c.ink3, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        sections.forEachIndexed { si, section ->
                            item(key = "group-$si") {
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    section.label?.let {
                                        Text(it, Modifier.padding(start = 4.dp, bottom = 8.dp), style = MinaType.Label, color = c.ink3)
                                    }
                                    GlassCardLite {
                                        section.rows.forEachIndexed { i, item ->
                                            if (i > 0) com.meenai.mina.ui.accounts.RowDivider()
                                            GroupedAccountRow(item, Modifier.staggerIn(si * 4 + i, 16.dp), onOpen = onOpen)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 钉住的顶栏:haze 模糊 + 底边渐隐;返回键 48dp 玻璃圆钮,标题居中 24sp。
            val glassBase = if (c.isDark) GlassBaseDark else GlassBaseLight
            val barStyle = remember(c) {
                HazeStyle(backgroundColor = glassBase, tint = HazeTint(c.glass.copy(alpha = 0.85f)), blurRadius = 28.dp)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .then(if (haze != null && GlassBlurSupported) Modifier.hazeChild(haze, style = barStyle) else Modifier),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(top = statusBar, start = 8.dp, end = 8.dp)
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .background(c.glass.copy(alpha = if (c.isDark) 0.55f else 0.6f), CircleShape)
                            .border(1.dp, c.cardEdge, CircleShape)
                            .clip(CircleShape)
                            .pressScale(onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MinaIcons.Back, tr(R.string.accounts_back), Modifier.size(20.dp), tint = c.ink)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            tr(R.string.accounts_title),
                            style = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = InterTight),
                            color = c.ink,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.size(56.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Brush.verticalGradient(listOf(c.glass.copy(alpha = 0.9f), Color.Transparent))),
                )
            }

            // 分段标签:钉在顶栏下面,列表从它底下滚过去。
            AccountTabs(
                selected = state.selectedTab,
                onSelect = onTab,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBar + 64.dp + 8.dp)
                    .padding(horizontal = 16.dp),
            )

            // 底部悬浮搜索:玻璃胶囊 + haze,按名字和副标题实时过滤。
            val searchStyle = remember(c) {
                HazeStyle(backgroundColor = glassBase, tint = HazeTint(c.glass.copy(alpha = 0.85f)), blurRadius = 28.dp)
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .then(if (haze != null && GlassBlurSupported) Modifier.hazeChild(haze, shape = MinaShapes.Pill, style = searchStyle) else Modifier)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(MinaShapes.Pill)
                    .background(c.glass.copy(alpha = if (c.isDark) 0.6f else 0.8f))
                    .border(1.dp, c.glassEdge, MinaShapes.Pill)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(MinaIcons.Search, tr(R.string.accounts_search_hint), Modifier.size(20.dp), tint = c.ink2)
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f),
                    textStyle = MinaType.Body.copy(color = c.ink),
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    decorationBox = { inner ->
                        Box {
                            if (state.query.isEmpty()) Text(tr(R.string.accounts_search_hint), style = MinaType.Body, color = c.ink3)
                            inner()
                        }
                    },
                )
            }

            ConnectSheet(
                target = state.sheet,
                loading = state.sheetLoading,
                onDismiss = onDismiss,
                onConnect = { state.sheet?.item?.let(onConnect) },
                onSaveLogin = onSaveLogin,
                onSaveKey = onSaveKey,
            )

            deleting?.let { d ->
                AlertDialog(
                    onDismissRequest = { deleting = null },
                    containerColor = c.surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    title = { Text(tr(R.string.accounts_remove_title, d.name), style = MinaType.BodyStrong, color = c.ink) },
                    text = { Text(tr(R.string.accounts_remove_message), style = MinaType.Body, color = c.ink2) },
                    dismissButton = {
                        TextButton(onClick = { deleting = null }) { Text(tr(R.string.accounts_cancel), color = c.ink2) }
                    },
                    confirmButton = {
                        TextButton(onClick = { onRemove(d); deleting = null }) { Text(tr(R.string.accounts_remove), color = c.bad) }
                    },
                )
            }
        }
    }
}

/** 已连区块:每张卡一个账号,连上时展开淡入,移除时收起淡出。 */
private fun LazyListScope.connectedSection(
    connected: List<AccountItem>,
    onOpen: (AccountItem) -> Unit,
    onRemove: (AccountItem) -> Unit,
) {
    item(key = "connected") {
        AnimatedVisibility(
            visible = connected.isNotEmpty(),
            enter = if (MinaTheme.reduceMotion) fadeIn(tween(1)) else expandVertically(tween(260)) + fadeIn(tween(260)),
            exit = if (MinaTheme.reduceMotion) fadeOut(tween(1)) else shrinkVertically(tween(260)) + fadeOut(tween(260)),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                connected.forEachIndexed { i, item ->
                    ConnectedAccountCard(item, Modifier.staggerIn(i, 16.dp), onOpen = onOpen, onRemove = onRemove)
                }
            }
        }
    }
}

/** 按页取小节:All = 三组带标签;其他页一组;搜索按名字 / 副标题 / 身份过滤。 */
private fun sectionsFor(page: Int, state: AccountsUiState): List<AccountSection> {
    val q = state.query.trim()
    fun match(i: AccountItem): Boolean =
        q.isEmpty() || i.name.contains(q, true) ||
            (i.subtitle?.contains(q, true) ?: false) ||
            (i.identity?.contains(q, true) ?: false)
    val byType = { t: AccountType -> state.items.filter { it.type == t && match(it) } }
    val all = listOf(
        AccountSection(tr(R.string.accounts_section_connections), byType(AccountType.CONNECTION)),
        AccountSection(tr(R.string.accounts_section_logins), byType(AccountType.LOGIN)),
        AccountSection(tr(R.string.accounts_section_api_keys), byType(AccountType.API_KEY)),
    )
    return when (page) {
        1 -> listOf(AccountSection(null, byType(AccountType.CONNECTION)))
        2 -> listOf(AccountSection(null, byType(AccountType.LOGIN)))
        3 -> listOf(AccountSection(null, byType(AccountType.API_KEY)))
        else -> all
    }.filter { it.rows.isNotEmpty() }
}

@Preview(name = "All", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun AccountsPreviewAll() = PreviewState(0)

@Preview(name = "Connections", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun AccountsPreviewConnections() = PreviewState(1)

@Preview(name = "Logins", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun AccountsPreviewLogins() = PreviewState(2)

@Preview(name = "API Keys", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun AccountsPreviewApiKeys() = PreviewState(3)

@Composable
private fun PreviewState(tab: Int) {
    MinaTheme {
        AccountsContent(
            state = AccountsUiState(selectedTab = tab),
            onBack = {}, onTab = {}, onQuery = {}, onOpen = {}, onRemove = {},
        )
    }
}
