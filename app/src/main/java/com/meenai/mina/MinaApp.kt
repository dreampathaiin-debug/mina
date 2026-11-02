package com.meenai.mina

import com.meenai.mina.record.DetailedTraces
import com.meenai.mina.i18n.ResourceTexts
import com.meenai.mina.i18n.UiText
import com.meenai.mina.i18n.AppLanguage
import android.app.Application
import android.net.Uri
import android.util.Log
import com.meenai.mina.chat.ConversationDeleter
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.cap.sandbox.AndroidSandbox
import com.meenai.mina.cap.sandbox.Attachments
import com.meenai.mina.ui.AttachFiles
import com.meenai.mina.cap.sandbox.ShareExporter
import com.meenai.mina.cap.web.WebFiles
import com.meenai.mina.cap.sandbox.RuntimePreparer
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.sandbox.SkillsSync
import com.meenai.mina.capsule.AppForeground
import com.meenai.mina.capsule.CapsuleController
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.memory.AppLearningActions
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.settings.ApprovalControl
import com.meenai.mina.settings.ApprovalStore
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.replay.FileRouteStore
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.core.run.Outbox
import com.meenai.mina.core.run.RunStore
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.task.FileOutboxStore
import com.meenai.mina.task.FileRunStateStore
import com.meenai.mina.task.LocalChannel
import com.meenai.mina.task.RunRecovery
import com.meenai.mina.task.TaskRecovery
import com.meenai.mina.task.TriggerOutcomes
import com.meenai.mina.task.TaskService
import com.meenai.mina.task.TaskCenterState
import com.meenai.mina.ui.ChatViewModel
import com.meenai.mina.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meenai.mina.cap.web.cdp.WebDevtools
import com.meenai.mina.cap.web.WebOwner
import com.meenai.mina.cap.web.SiteRegistry
import com.meenai.mina.cap.sandbox.SandboxOwner
import com.meenai.mina.core.workspace.SandboxTree
import com.meenai.mina.task.TaskEnvironment
import com.meenai.mina.task.ResourceScheduler
import com.meenai.mina.task.RemoteTaskHub
import com.meenai.mina.task.FileChildRunStore
import com.meenai.mina.task.TriggerRuntime

/**
 * App 容器:进程里共用的服务都在这里建一份,经各自的 Owner 接口交给安卓入口(界面、服务、广播、provider),
 * 其余代码经构造参数拿到。助手进程(HelperMain、ShellService)没有这个对象,不用这里的任何服务。
 */
class MinaApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 手机能力的服务:内置助手、共用的 shell 连接、配对、「去设置」提示。 */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** 网页标签的 DevTools 会话入口:整个进程一份;连不上时经内置助手代连。 */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** 各任务的标签池共用的标签总数上限。 */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }

    /**
     * 电脑连接(MCP):开关、访问令牌(和服务商的 Key 一样加密存)、监听。局域网那种默认关着;
     * USB 那种由电脑经 adb 打开,只到这次关机为止(开机编号和 shell 里 `settings get global boot_count` 读到的一样)。
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        com.meenai.mina.mcp.McpRuntime(this,
            com.meenai.mina.settings.McpSettings(settingsDir, com.meenai.mina.settings.KeystoreKeyCipher("mina-mcp-token")),
            com.meenai.mina.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.meenai.mina.core.mcp.McpUsb.INFO_FILE) } }),
            remoteTasks, com.meenai.mina.device.AndroidDeviceAccess(this) { phone.hub.shell.value },
            com.meenai.mina.settings.RelaySettings(settingsDir, com.meenai.mina.settings.KeystoreKeyCipher("mina-remote-relay")))
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }
    override val tabBudget get() = resources.tabBudget

    /** 任务结束后留在后台接着运行的那一页;关掉时在来源对话那一轮记下原因(对话页那一轮下面显示「后台网页已关闭」)。 */
    override val pageKeeper: com.meenai.mina.cap.web.PageKeeper by lazy {
        com.meenai.mina.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("MinaApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** 记忆、个人技能、开关与结果卡片提示。 */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** 本机的 token 用量账本:经模型配置建出的每个模型的每次调用都记在这里。 */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** 模型配置与 Key;建出的模型都套上计量,记到 [usage]。 */
    override val providers: ProviderStore by lazy { ProviderStore.create(this, usage, "Mina/${BuildConfig.VERSION_NAME}") }

    /** 语音转文字的选择:整个进程一份,设置页改了输入框立刻看得到(两份实例互相看不到对方的 set)。 */
    val speech: com.meenai.mina.voice.SpeechSettings by lazy { com.meenai.mina.voice.SpeechSettings.get(this) }

    /** 语音输入的入口,输入框和悬浮胶囊共用。 */
    val voice: com.meenai.mina.voice.VoiceInput by lazy { com.meenai.mina.voice.VoiceInput(this, speech, providers) }

    /** 用 ChatGPT 登录:跑在 App 的作用域里,期间开前台服务撑住进程(见 [com.meenai.mina.settings.ChatGptSignIns])。 */
    val chatGptSignIn by lazy {
        com.meenai.mina.settings.ChatGptSignIns(scope,
            keepAlive = { com.meenai.mina.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("MinaChatGpt", msg, e) })
    }

    /** 经验路线。 */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** 异常退出后留下的系统改动的恢复;在跑的任务登记在这里。 */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** 运行账本与送达账本:整个进程一份,任务服务和启动时的恢复共用(同一个文件不能有两份实例)。 */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** 没结束的运行的善后(标中断、补送、告知):进程里只做一次,App 启动和任务服务启动都调它。 */
    val runRecovery: RunRecovery by lazy {
        val app = this
        RunRecovery(runStore, outbox, LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId())
        }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope)
    }

    /** 站点登记表(登录过的网站等):设置页和标签池共用。 */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/` 的树锁:安装、重置、命令、下载、附件都在它的许可里写。 */
    override val sandboxTree = SandboxTree()

    /** 任务从 App 拿的模型来源、评测钩子、屏幕画面的开关。 */
    override val tasks = TaskEnvironment()

    /** 沙箱状态:安装服务更新的,设置页与 Agent 看到的是同一份。 */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** 对话记录:整个进程一份,任务服务、聊天界面、结果卡片的按钮共用。 */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** 删对话(侧栏长按):连带它的任务、定时任务和工作区。 */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            removeSchedule = { TriggerRuntime.remove(app, it) },
            deleteWorkspace = { id ->
                // 工作区里有附件、用户发来的屏幕、任务写的文件;沙箱里的命令碰得到这里,按句柄删、不跟进链接,和重置互斥
                val dirs = SandboxDirs(app)
                dirs.tree.shared { withContext(Dispatchers.IO) { dirs.gate.deleteTree(dirs.work, dirs.workspace(id).name) } }
            },
        )
    }

    /** 用户发来的屏幕(跟着各自的对话存在工作区里)。 */
    val userScreens: com.meenai.mina.chat.UserScreenFiles by lazy { com.meenai.mina.chat.UserScreenFiles(this) }

    /** 全局的询问 / 自主:整个进程一份,设置页和各任务共用。 */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.meenai.mina.settings.AppAccessStore by lazy {
        com.meenai.mina.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** 全局的询问 / 自主和各任务自己的那一份;开发者选项里的「自主模式」经它改。 */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** 各 Run 的队列与展示状态跟 App 进程走，Service 重建时继续使用同一份。 */
    val taskState = TaskCenterState()

    /** Mina 自己的界面此刻看不看得见。 */
    val foreground = AppForeground()

    /** 检查更新:查到的新版本跟进程走,侧栏、设置首页、关于页的红点看同一份。 */
    val updates: com.meenai.mina.about.UpdateChecker by lazy {
        com.meenai.mina.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList())
    }

    /** 浮在别的 App 上的悬浮胶囊。 */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** 对话页的状态跟进程走:界面关了再打开,正在跑的任务的步骤还在。 */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // 按下那一刻就换成该对话任务的编号:之后切了对话、或那个任务换了,动作也不会落到别的任务上
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
            },
            conversations,
            taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // 虚拟屏上打开过 App,或在用内置浏览器:画中画才有画面可看(只建了空屏的不算)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // 按对话取它自己的任务,不用服务对外那一份(并行时那是显示中的别的任务)
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // 附件进对话的工作区,不需要运行环境已准备好:设备支持就给附件按钮
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(id), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(id) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = conversationDeleter::delete,
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.meenai.mina.core.cap.web.KeptPageClose.User) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 界面以外的文字(通知、服务、悬浮胶囊)按 App 语言取
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        registerActivityLifecycleCallbacks(foreground)
        // 设置 › 网络代理:Mina 自己的连接从第一个请求起就按它走;SOCKS 代理的账号密码经全进程的认证器给
        com.meenai.mina.settings.NetProxyStore.get(this)
        com.meenai.mina.core.net.NetProxy.shared.installSocksAuth()
        // 内置浏览器:设了代理才给 WebView 设上(读设置要解密,放到后台;没设代理不为此加载 WebView)
        scope.launch(Dispatchers.IO) { runCatching { com.meenai.mina.cap.web.WebProxy.apply(this@MinaApp) } }
        capsule.start()
        // 内置助手的真实现,并开始看着助手(Mina 进程起来后,还活着的助手会自己交接 Binder)
        phone.shellManager.start()
        // 连接一可用就补上默认打开的几项能力
        phone.recovery.start()
        // 告诉内置助手:Mina 被杀后要不要把它拉起来(胶囊一直显示或有任务在跑时才要)
        com.meenai.mina.cap.phone.setup.RestoreWishSync(
            phone.hub, FeatureFlags.get(this).capsuleModeChanges(), taskState.busy, phone.recoverySettings.consent, phone.recovery.accessibility,
        ).start()
        // 上次异常退出时留下的改动(App 被静音)在这里恢复
        scope.launch { taskRecovery.recover() }
        // 上次进程被杀时没结束的运行:现在就标为中断并告知,不等任务服务起来
        runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MinaTask", "${it.size} runs interrupted by process restart") }
        scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.rearm(this@MinaApp) } }
        // 给电脑跑的「经 USB 连接」脚本;顺带清掉上次开机留下的 USB 连接信息
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("MinaMcp", "cannot write USB script", it) } }
        // 上次导出写到一半被杀留下的空壳条目
        scope.launch { runCatching { ShareExporter(this@MinaApp).cleanPending() } }
        // 上次进程留下的网页上传副本
        scope.launch { runCatching { WebFiles.clearStale(this@MinaApp, SandboxDirs(this@MinaApp).gate) } }
        // 更新已经装上后,关于页下好的安装包
        scope.launch(Dispatchers.IO) { runCatching { com.meenai.mina.about.ApkUpdater(this@MinaApp).dropInstalled() } }
        // 运行环境没准备好(首次启动、Mina 升级后)就在后台准备,不联网、对用户不可见
        RuntimePreparer.ensure(this)
        // 沙箱就绪(启动时已就绪,或刚装好)就把技能目录核对一次:App 更新带来的脚本马上能用
        scope.launch {
            SandboxState.get(this@MinaApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@MinaApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@MinaApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }
}
