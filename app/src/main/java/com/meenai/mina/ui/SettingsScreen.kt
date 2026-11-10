package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.meenai.mina.settings.ProviderConnection
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meenai.mina.BuildConfig
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.memory.ProfileStore
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.record.RunStore
import com.meenai.mina.replay.FileRouteStore
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.Problem
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.NetProxyStore
import com.meenai.mina.cap.web.WebProxy
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.settings.notificationOverview
import com.meenai.mina.task.ParallelCapability
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.PillTone
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.StateCard
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.core.usage.UsageQuery
import com.meenai.mina.core.usage.UsageRange
import com.meenai.mina.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置里能进入的子页面;[Home] 是设置首页。 */
private enum class SettingsPage { Home, Profile, Accounts, Privacy, Advanced, AppAccess, Usage, PhoneOps, DuringWork, Capsule, Language, Notifications, WebSites, Sandbox, Routes, Recovery, SelfCheck, ComputerAccess, Developer, RunStats, Devices }

/**
 * 设置页:总状态卡 + 新的分组首页(Profile / 常用 / 隐私与数据 / Advanced / 关于)。
 * 各子页面在这里切换(返回键先回首页),模型页由 [onOpenModels]、关于页由 [onOpenAbout] 交给 Activity,
 * 计划任务由 [onOpenPlans] 打开。修复动作由 [onFix] 交给 Activity 去做(请求权限、跳转系统设置)。
 */
@Composable
fun SettingsScreen(
    store: ProviderStore,
    status: SystemStatus,
    androidRelease: String,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
    webSites: WebSitesState? = null,
    /** 开发者选项里的「自主模式」:当前值和换它的办法。 */
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    /** 查到的新版本号;有它时「关于 Mina」一行写「x 可更新」带红点。 */
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
    /** 计划任务(Plans)页。 */
    onOpenPlans: () -> Unit = {},
    /** agent 的 profile 记忆:Profile 页写进去。 */
    profile: ProfileStore? = null,
) {
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    // 开发者选项里的「看图定位已验证」跟着看图模型(看图用主模型时是主模型那条配置)
    val visionConfig = remember(configs, roles) { store.configFor(ModelRole.Vision) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var explaining by remember { mutableStateOf<Problem?>(null) }

    fun fix(f: Fix) = if (f == Fix.AddModel) onOpenModels() else onFix(f)
    val onProblem: (Problem) -> Unit = { p -> StatusCopy.directFix(p)?.let(::fix) ?: run { explaining = p } }

    // 并行任务的上限只在实验开关 parallelTasks 打开时出现;设备能力按此刻算,第二块屏建失败过就按失败算
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    var parallelMax by remember { mutableIntStateOf(flags.parallelMax()) }
    var keepScreenOn by remember { mutableStateOf(flags.keepScreenOn()) }
    var muteTarget by remember { mutableStateOf(flags.muteTarget()) }
    var operatingPace by remember { mutableStateOf(flags.operatingPace()) }
    var selfConfig by remember { mutableStateOf(flags.selfConfig()) }
    var capsuleMode by remember { mutableStateOf(flags.capsuleMode()) }
    // 换语言时界面会重建,这里每次进来读一次即可
    val language = remember { AppLanguage.current(context) }
    val runtimeLimit by (context.applicationContext as com.meenai.mina.MinaApp).taskState.parallelLimit.collectAsState()
    var speed by remember { mutableStateOf(flags.speedFlags()) }
    val routeStore = remember { FileRouteStore.shared(context) }
    var routeCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
    val sandboxStatus by remember { SandboxState.get(context) }.status.collectAsState()
    // 网络代理:只在 Advanced 里改;读设置要解密、看浏览器支不支持要加载 WebView,都放到后台
    val proxies = remember { NetProxyStore.get(context) }
    var proxy by remember { mutableStateOf<String?>(null) }
    var browserProxy by remember { mutableStateOf<WebProxy.Mode?>(null) }
    var editingProxy by remember { mutableStateOf(false) }
    LaunchedEffect(proxy) {
        if (proxy == null) proxy = withContext(Dispatchers.IO) { proxies.get() }
        browserProxy = withContext(Dispatchers.IO) { WebProxy.mode() }
    }
    val mcp = remember { (context.applicationContext as com.meenai.mina.MinaApp).mcp }
    val mcpNetworkOn by mcp.settings.enabled.collectAsState()
    val mcpUsbOn by mcp.usb.enabled.collectAsState()
    val mcpOn = mcpNetworkOn || mcpUsbOn
    // 「Token 用量」一行的值:近 7 天的合计,账本每记一笔就重算
    val usage = remember { (context.applicationContext as com.meenai.mina.MinaApp).usage }
    val usageVersion by usage.version.collectAsState()
    var usageWeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(usageVersion) {
        usageWeek = withContext(Dispatchers.Default) {
            UsageQuery.report(usage.snapshot(), UsageRange.LastDays(7), Instant.now(), ZoneId.systemDefault(), emptyMap()).totals.tokens
        }
    }
    val parallel = if (!speed.parallelTasks) null else ParallelSetting(
        parallelMax,
        ParallelCapability.detect(
            status.sdkInt, context.getSystemService(ActivityManager::class.java).isLowRamDevice, parallelMax,
            secondDisplayFailed = runtimeLimit.reason == ParallelCapability.SECOND_DISPLAY_FAILED,
        ),
    )
    // 开发者选项只在调试版和性能测试版里列出:发布版的各开关按评测结果统一定默认值
    val showDeveloper = com.meenai.mina.record.DetailedTraces.supportedBuild
    val toHome = { page = SettingsPage.Home }
    // 通知页可以从首页或手机操作页进,返回回到进来的那一页
    var notificationsFrom by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    val back = {
        page = when (page) {
            SettingsPage.RunStats -> SettingsPage.Developer
            SettingsPage.SelfCheck -> SettingsPage.Recovery
            SettingsPage.Notifications -> notificationsFrom
            else -> SettingsPage.Home
        }
    }

    if (page != SettingsPage.Home) BackHandler { back() }

    val visiblePage = if (!showDeveloper && (page == SettingsPage.Developer || page == SettingsPage.RunStats)) SettingsPage.Home else page
    when (visiblePage) {
        SettingsPage.Home -> SettingsContent(
            status = status, androidRelease = androidRelease,
            mainModel = roles.main?.let { id -> configs.firstOrNull { it.id == id } }?.let(ProviderConnection::displayName),
            onBack = onBack, onProblem = onProblem, onOpenModels = onOpenModels,
            proxy = proxy, proxyNote = proxy?.let { NetProxyCopy.subtitle(it, browserProxy) }, onOpenProxy = { editingProxy = true },
            usageTokens = usageWeek, onOpenUsage = { page = SettingsPage.Usage },
            onOpenPhoneOps = { page = SettingsPage.PhoneOps },
            onOpenAppAccess = { page = SettingsPage.AppAccess },
            onOpenDevices = { page = SettingsPage.Devices },
            onOpenDuringWork = { page = SettingsPage.DuringWork },
            capsuleMode = capsuleMode, onOpenCapsule = { page = SettingsPage.Capsule },
            onOpenNotifications = { notificationsFrom = SettingsPage.Home; page = SettingsPage.Notifications },
            language = language, onOpenLanguage = { page = SettingsPage.Language },
            onOpenProfile = { page = SettingsPage.Profile },
            onOpenAccounts = { page = SettingsPage.Accounts },
            onOpenPrivacy = { page = SettingsPage.Privacy },
            onOpenAdvanced = { page = SettingsPage.Advanced },
            onOpenPlans = onOpenPlans,
            onOpenAbout = onOpenAbout,
            versionName = BuildConfig.VERSION_NAME, updateVersion = updateVersion,
        )
        SettingsPage.Profile -> ProfileScreen(profile, onBack = toHome)
        SettingsPage.Accounts -> AccountsScreen(webSites, onBack = toHome)
        SettingsPage.Privacy -> PrivacyScreen(
            onOpenMemory = onOpenMemory,
            webSitesCount = webSites?.sites?.size,
            onOpenWebSites = { page = SettingsPage.WebSites },
            onBack = toHome,
        )
        SettingsPage.Advanced -> AdvancedScreen(
            proxy = proxy, proxyNote = proxy?.let { NetProxyCopy.subtitle(it, browserProxy) }, onOpenProxy = { editingProxy = true },
            usageTokens = usageWeek, onOpenUsage = { page = SettingsPage.Usage },
            sandbox = sandboxStatus, onOpenSandbox = { page = SettingsPage.Sandbox },
            routes = routeCount, onOpenRoutes = { page = SettingsPage.Routes },
            onOpenRecovery = { page = SettingsPage.Recovery },
            computerAccess = mcpOn, onOpenComputerAccess = { page = SettingsPage.ComputerAccess },
            showDeveloper = showDeveloper, onOpenDeveloper = { page = SettingsPage.Developer },
            onBack = toHome,
        )
        SettingsPage.AppAccess -> AppAccessScreen(onBack = toHome)
        SettingsPage.Devices -> DevicesScreen(onBack = toHome)
        SettingsPage.Usage -> UsageScreen(usage, store.providers, onBack = toHome)
        SettingsPage.PhoneOps -> PhoneOpsScreen(
            status, androidRelease, onProblem,
            onOpenNotifications = { notificationsFrom = SettingsPage.PhoneOps; page = SettingsPage.Notifications },
            onRepair = { onFix(Fix.RepairPhoneHelper) }, onUnpair = { onFix(Fix.UnpairPhoneHelper) }, onBack = toHome,
        )
        SettingsPage.Notifications -> NotificationsScreen(status, onFix = ::fix, onBack = back)
        SettingsPage.DuringWork -> DuringWorkScreen(
            keepScreenOn, onKeepScreenOn = { flags.setKeepScreenOn(it); keepScreenOn = it },
            muteTarget, onMuteTarget = { flags.setMuteTarget(it); muteTarget = it },
            parallel, onParallelMax = { flags.setParallelMax(it); parallelMax = it }, onBack = toHome,
            operatingPace = operatingPace, onOperatingPace = { flags.setOperatingPace(it); operatingPace = it },
            selfConfig = selfConfig, onSelfConfig = { flags.setSelfConfig(it); selfConfig = it },
        )
        SettingsPage.Language -> LanguageScreen(language, onLanguage = { AppLanguage.set(context, it) }, onBack = toHome)
        SettingsPage.Capsule -> CapsuleScreen(capsuleMode, onMode = { flags.setCapsuleMode(it); capsuleMode = it }, onBack = toHome)
        // 没有数据时首页不显示入口,走不到这一页
        SettingsPage.WebSites -> webSites?.let { WebSitesScreen(it, onBack = toHome) }
        SettingsPage.Sandbox -> SandboxScreen(onBack = toHome)
        SettingsPage.Routes -> RoutesScreen(
            routeCount,
            onClear = { scope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } } },
            onBack = toHome,
        )
        SettingsPage.Recovery -> RecoveryScreen(onBack = toHome, onOpenSelfCheck = { page = SettingsPage.SelfCheck })
        SettingsPage.SelfCheck -> SelfCheckScreen(status, onBack = back)
        SettingsPage.ComputerAccess -> McpScreen(mcp, onBack = toHome)
        SettingsPage.Developer -> DeveloperOptionsScreen(
            speed, onSpeed = { flags.setSpeed(it); speed = it }, onBack = toHome,
            autonomous = autonomous, onAutonomous = onAutonomous,
            grounding = visionConfig?.let { GroundingSetting(it.model, it.capabilities.groundingVerified) },
            onGrounding = { verified -> scope.launch { withContext(Dispatchers.IO) { store.setVisionGroundingVerified(verified) } } },
            onOpenRunStats = { page = SettingsPage.RunStats },
        )
        SettingsPage.RunStats -> RunStatsScreen(remember { RunStore(context).metrics }, onBack = back)
    }

    if (editingProxy) NetProxyDialog(
        initial = proxy.orEmpty(),
        onDismiss = { editingProxy = false },
        onSave = { v ->
            scope.launch {
                withContext(Dispatchers.IO) { proxies.set(v) }
                proxy = v.trim()
                editingProxy = false
            }
        },
    )

    explaining?.let { p ->
        val e = StatusCopy.explain(p, androidRelease)
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(e.title) },
            text = { Text(e.body) },
            confirmButton = {
                if (e.action != null && e.fix != null) {
                    TextButton({ explaining = null; fix(e.fix) }) { Text(e.action) }
                } else {
                    TextButton({ explaining = null }) { Text(stringResource(R.string.ok_got_it)) }
                }
            },
            dismissButton = if (e.fix != null) ({ TextButton({ explaining = null }) { Text(stringResource(R.string.cancel)) } }) else null,
        )
    }
}

// ---------- 设置首页:新 UI ----------

/**
 * 设置首页(Hark 风格):Profile 头像卡、Mina 版本卡,然后是常用/高级的分组。
 * 原来的全部入口都在:常用的留在外面,代理/用量/沙盒/路线/恢复/MCP/开发者收进 Advanced,记忆和网页登录收进 Privacy & data。
 */
@Composable
fun SettingsContent(
    status: SystemStatus,
    androidRelease: String,
    /** 当前主模型的名字;没有可用的主模型时为空。 */
    mainModel: String?,
    onBack: () -> Unit,
    onProblem: (Problem) -> Unit,
    onOpenModels: () -> Unit,
    /** 网络代理(空串表示不用);为空(还没读出来)时不显示这一行。 */
    proxy: String? = null,
    /** 网络代理那一行下面的说明。 */
    proxyNote: String? = null,
    onOpenProxy: () -> Unit = {},
    /** 近 7 天用了多少 token;为空(还没统计出来)时不显示这一行。 */
    usageTokens: Long? = null,
    onOpenUsage: () -> Unit = {},
    onOpenPhoneOps: () -> Unit = {},
    onOpenAppAccess: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenDuringWork: () -> Unit = {},
    /** 悬浮胶囊此刻的选择,写在「悬浮胶囊」一行的右边。 */
    capsuleMode: CapsuleMode = CapsuleMode.DEFAULT,
    onOpenCapsule: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    /** 此刻选的界面语言,写在「语言」一行的右边。 */
    language: AppLanguage = AppLanguage.System,
    onOpenLanguage: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    /** 计划任务(Plans)。 */
    onOpenPlans: () -> Unit = {},
    versionName: String = "",
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val check = Prerequisites.check(status, hasMainModel = mainModel != null)
    val context = LocalContext.current
    var profileValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { profileValues = ProfileUiStore.read(context) }
    PageScaffold(stringResource(R.string.settings_title), onBack) {
        StatusSummary(check, onProblem)

        // Profile 头像卡:有名字才显示
        val name = profileValues["name"]
        val email = profileValues["email"]
        if (!name.isNullOrBlank() || !email.isNullOrBlank()) {
            MinaCard(Modifier.padding(bottom = 12.dp).clickable(onClick = onOpenProfile)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(name.orEmpty().ifBlank { tr(R.string.profile_title) }, style = MinaType.BodyStrong, color = MinaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!email.isNullOrBlank()) Text(email, style = MinaType.Caption, color = MinaTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RowChevron()
                }
            }
        }

        // Mina 版本卡:右边「更新」或「关于」
        MinaCard(Modifier.padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mina", style = MinaType.Title, color = MinaTheme.colors.ink)
                    Text(AboutCopy.settingsValue(updateVersion, versionName).text ?: "v" + versionName, style = MinaType.Caption, color = MinaTheme.colors.ink3)
                }
                MinaButton(
                    if (updateVersion != null) tr(R.string.settings_update_btn) else tr(R.string.settings_about_btn),
                    onOpenAbout, kind = ButtonKind.Tonal, size = ButtonSize.Small,
                )
            }
        }

        GroupLabel(stringResource(R.string.settings_main))
        GroupCard {
            ListRow(MinaIcons.Gear, stringResource(R.string.settings_profile), stringResource(R.string.settings_profile_sub), first = true, onClick = onOpenProfile) { RowChevron() }
            ListRow(MinaIcons.Clock, stringResource(R.string.settings_scheduled), onClick = onOpenPlans) { RowChevron() }
            ListRow(MinaIcons.Link, stringResource(R.string.settings_accounts), stringResource(R.string.settings_accounts_sub), onClick = onOpenAccounts) { RowChevron() }
        }

        GroupCard {
            ListRow(MinaIcons.Spark, stringResource(R.string.settings_model), first = true, onClick = onOpenModels) {
                RowValue(mainModel ?: stringResource(R.string.settings_not_set))
                RowChevron()
            }
            ListRow(MinaIcons.Message, CapsuleSettingsCopy.TITLE, CapsuleSettingsCopy.SUBTITLE, onClick = onOpenCapsule) {
                RowValue(CapsuleSettingsCopy.label(capsuleMode))
                RowChevron()
            }
            ListRow(MinaIcons.Globe, stringResource(R.string.language_title), onClick = onOpenLanguage) {
                RowValue(languageLabel(language))
                RowChevron()
            }
        }

        GroupLabel(stringResource(R.string.settings_general))
        GroupCard {
            ListRow(MinaIcons.Lock, stringResource(R.string.settings_privacy_data), stringResource(R.string.settings_privacy_data_sub), first = true, onClick = onOpenPrivacy) { RowChevron() }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MinaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
            ListRow(MinaIcons.Phone, stringResource(R.string.settings_phone_ops), onClick = onOpenPhoneOps) {
                PhoneOpsPill(status)
                RowChevron()
            }
            ListRow(MinaIcons.Apps, stringResource(R.string.appaccess_title), stringResource(R.string.appaccess_row_sub), onClick = onOpenAppAccess) { RowChevron() }
            ListRow(MinaIcons.Shield, stringResource(R.string.devices_title), stringResource(R.string.devices_row_sub), onClick = onOpenDevices) { RowChevron() }
            ListRow(MinaIcons.Moon, stringResource(R.string.settings_during_work), stringResource(R.string.settings_during_work_sub), onClick = onOpenDuringWork) { RowChevron() }
        }

        GroupCard {
            ListRow(MinaIcons.Code, stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_sub), first = true, onClick = onOpenAdvanced) { RowChevron() }
        }

        GroupCard {
            ListRow(MinaIcons.Info, stringResource(R.string.settings_about), first = true, onClick = onOpenAbout) {
                TrailingValue(AboutCopy.settingsValue(updateVersion, versionName))
                RowChevron()
            }
        }
    }
}

// ---------- Advanced ----------

/** Advanced:代理、用量、沙盒、路线、恢复、MCP、开发者。 */
@Composable
private fun AdvancedScreen(
    proxy: String?,
    proxyNote: String?,
    onOpenProxy: () -> Unit,
    usageTokens: Long?,
    onOpenUsage: () -> Unit,
    sandbox: SandboxStatus?,
    onOpenSandbox: () -> Unit,
    routes: Int?,
    onOpenRoutes: () -> Unit,
    onOpenRecovery: () -> Unit,
    computerAccess: Boolean?,
    onOpenComputerAccess: () -> Unit,
    showDeveloper: Boolean,
    onOpenDeveloper: () -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(stringResource(R.string.advanced_title), onBack) {
        GroupLabel(stringResource(R.string.settings_advanced))
        GroupCard {
            proxy?.let {
                ListRow(MinaIcons.Link, NetProxyCopy.TITLE, proxyNote, first = true, onClick = onOpenProxy) {
                    RowValue(NetProxyCopy.value(it))
                    RowChevron()
                }
            }
            usageTokens?.let {
                ListRow(MinaIcons.Chart, UsageCopy.TITLE, first = proxy == null, onClick = onOpenUsage) {
                    RowValue(UsageCopy.settingsValue(it))
                    RowChevron()
                }
            }
            val sandboxRow = sandbox?.takeIf { it !is SandboxStatus.Unsupported }
            val recoveryRow = true
            var shown = proxy == null && usageTokens == null
            fun take(): Boolean = shown.also { shown = false }
            if (sandboxRow != null) {
                ListRow(MinaIcons.Terminal, stringResource(R.string.settings_sandbox), first = take(), onClick = onOpenSandbox) {
                    RowValue(sandboxValue(sandboxRow)); RowChevron()
                }
            }
            routes?.let {
                ListRow(MinaIcons.Route, stringResource(R.string.settings_routes), first = take(), onClick = onOpenRoutes) { RowValue(trPlural(R.plurals.settings_count_routes, it)); RowChevron() }
            }
            if (recoveryRow) {
                ListRow(MinaIcons.Wrench, stringResource(R.string.settings_recovery), first = take(), onClick = onOpenRecovery) { RowChevron() }
            }
            computerAccess?.let { on ->
                ListRow(MinaIcons.Desktop, stringResource(R.string.mcp_title), first = take(), onClick = onOpenComputerAccess) {
                    RowValue(stringResource(if (on) R.string.state_on else R.string.state_off)); RowChevron()
                }
            }
            if (showDeveloper) {
                ListRow(MinaIcons.Code, stringResource(R.string.settings_developer), first = take(), onClick = onOpenDeveloper) { RowChevron() }
            }
        }
        FooterNoteCopy()
    }
}

@Composable
private fun FooterNoteCopy() {
    com.meenai.mina.ui.components.FooterNote(tr(R.string.advanced_note))
}

// ---------- Privacy & data ----------

/** Privacy & data:记忆与个人技能、网页登录。 */
@Composable
private fun PrivacyScreen(
    onOpenMemory: (() -> Unit)?,
    webSitesCount: Int?,
    onOpenWebSites: () -> Unit,
    onBack: () -> Unit,
) {
    PageScaffold(stringResource(R.string.privacy_title), onBack) {
        GroupLabel(stringResource(R.string.settings_privacy))
        GroupCard {
            var first = true
            fun take(): Boolean = first.also { first = false }
            onOpenMemory?.let {
                ListRow(MinaIcons.Heart, stringResource(R.string.memory_and_skills), stringResource(R.string.settings_memory_sub), first = take(), onClick = it) { RowChevron() }
            }
            webSitesCount?.let {
                ListRow(MinaIcons.Globe, stringResource(R.string.settings_web_logins), first = take(), onClick = onOpenWebSites) { RowValue(trPlural(R.plurals.settings_count_sites, it)); RowChevron() }
            }
        }
        com.meenai.mina.ui.components.FooterNote(stringResource(R.string.privacy_note))
    }
}

/** 「手机操作」一行尾部的状态胶囊:无障碍和内置助手都就绪才是「可用」。 */
@Composable
private fun PhoneOpsPill(status: SystemStatus) {
    when {
        status.sdkInt < Prerequisites.MIN_SDK -> StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
        status.builtinShell == BuiltinShellStatus.Running && status.accessibility == AccessibilityState.On -> StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
        else -> StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
    }
}

private fun sandboxValue(s: SandboxStatus): String = when (s) {
    is SandboxStatus.Ready -> tr(R.string.sandbox_ready)
    is SandboxStatus.Installing -> tr(R.string.sandbox_preparing)
    is SandboxStatus.Broken -> tr(R.string.pill_needs_attention)
    else -> tr(R.string.sandbox_not_prepared)
}

/**
 * 页面顶上的总状态:一切正常是「一切就绪」;否则变成第一个问题的提示,带修复按钮。
 * 焦点提醒改不了,只在手机操作页的「系统版本」一行里提示,不占这里。
 */
@Composable
private fun StatusSummary(check: Prerequisites.Check, onProblem: (Problem) -> Unit) {
    val problem = check.problems.firstOrNull { it != Problem.FocusMayLeak }
    if (problem == null) {
        StateCard(MinaIcons.Check, stringResource(R.string.all_ready), stringResource(R.string.settings_all_ready_detail), TileTone.Ok, Modifier.testTag("settings-status"))
    } else {
        val copy = StatusCopy.banner(problem)
        StateCard(
            MinaIcons.Exclaim, copy.title, copy.detail,
            if (problem in check.blockers) TileTone.Bad else TileTone.Warn,
            Modifier.testTag("settings-status"),
        ) {
            MinaButton(copy.link, { onProblem(problem) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}
