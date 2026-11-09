package com.meenai.mina.acceptance

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meenai.mina.DeviceTaskState
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.MinaApp
import com.meenai.mina.memory.MemoryRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.RoleChoice
import com.meenai.mina.task.TaskService
import com.meenai.mina.ui.MainActivity
import com.meenai.mina.ui.typeAndSend
import com.meenai.mina.ui.awaitOne
import com.meenai.mina.ui.appString
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.meenai.mina.R
import com.meenai.mina.browser.PageAskModel
import com.meenai.mina.browser.PageConversations
import com.meenai.mina.browser.UserBrowser
import com.meenai.mina.core.cap.web.AttachedPage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment
import com.meenai.mina.core.cap.phone.OperatingPace

/**
 *Real machine acceptance:Run a task using the real model configured in the settings,Only runs when parameters are passed explicitly,Usually the entire set of tests will be skipped.
 *
 * -Match model(Key is only passed in via parameters,Write to the app’s own encrypted storage,Don't fall anywhere else):
 *   `-e liveKey <key> -e liveBaseUrl <url> -e liveModel <model> [-e liveId <id>]`,run [configureProvider].
 * -Cut the main model:`-e liveMain <id>`,Run the same [configureProvider].
 * -run tasks:`-e liveGoal <Target> -e liveRun <Record directory name> [-e liveTimeoutSec 600] [-e liveOnTakeover cancel|handback]
 *   [-e livePace fast|standard|human] [-e liveMoveAwayAtStep N] [-e liveCancelAtStep N]`,run [runGoal]. via real interface:Write the target in the input box and click Send.
 *   liveCancelAtStep:When step N is reached, stop the task like clicking the stop button.,Wait for the explanation after the stop to be recorded in the conversation.(write in result.txt summary).
 *   `-e liveKeepCheckSec N`:After the task is completed, check whether there is a page left running in the background.(web_keep_open),If so, read the media playback progress on the page twice every N seconds.
 *   (video/audio currentTime,Only those with the same origin can be read)and a timer that only counts,write in result.txt kept*(keptPage,keptMediaAdvanced,
 *   keptTimerAdvanced,keptNotification),Save another picture of it kept-frame.png;That page is not closed,Save it for people to see.
 *   livePace:The rhythm of operating in the app fast / standard / human(default fast,run fast;Don’t change the settings),When comparing speed, pass standard.
 *   liveMoveAwayAtStep:At step N, move the app on the virtual screen to the home screen(The simulated user opens the same app on the home screen App),
 *Note pause status and notifications,Resume after a few seconds just like clicking "Continue" on the notification.
 *
 * -Ask on the browser page:`-e liveUrl <URL> -e liveGoal <question> -e liveRun <Record directory name> [-e liveTimeoutSec 600] [-e liveDesktop true]
 *   [-e liveNew true] [-e liveTouchAtStep N -e liveResumeAfterSec S] [-e liveOnTakeover cancel|handback]`,run [runPageAsk].
 *via real interface:Sidebar into browser,Open URL(liveDesktopSwitch to PC version),Wait for the page to load,Write a question in "Ask Mina" at the bottom of the page and click Send
 *   (When liveNew, click "New Conversation"),This page lends itself to that mission.liveTouchAtStep:When you reach step N, swipe up on the webpage through the window with your finger.
 *   (Just like real people PageFrame,Don’t click on the link),Take note of any pauses, banners, or cards that pop up,Wait S seconds(default 5)Then click "Continue" on the banner,Take note of recovery.
 *Don't give liveTouchAtStephour,If the task is paused due to "you are operating", it will be considered a failure.(Mina's own clicks and scrolls should not pause the task),The record is written as written.
 *   result.txt In addition to those of runGoal, there are also:borrowed(Page lent to task),firstToolCalls,urlsOpened(web_navigateOpened URL),
 *   reopenedSameUrl(Opened this page again,Shouldn't have),userTabUrlAtEnd,pausedByTouch,resumed,pauses(Various number of pauses),userPauses,
 *   conversationId,pageOrigin,listedAsWeb(This conversation in the sidebar is labeled "Web Page"). Take a screenshot of the home screen for each step, pause, resume, end, and sidebar.(step-NN.png etc.).
 *
 *Records are written to the App external file directory acceptance/<liveRun>/:log.txt(What the model says, tool calls and results summary at each step),
 * result.txt(Model, goals, results, number of steps, time taken, final answer), each step of the virtual screen and the main screen screenshots;
 *Passed `-e liveSettleSec N`Wait at most N seconds for the outcome determination and learning to be completed,Write down what you learned learned.txt.
 */
@RunWith(AndroidJUnit4::class)
class LiveRunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @After
    fun tearDown() {
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.evalHooks.operatingPace = null
    }

    @Test
    fun configureProvider() {
        val key = args.getString("liveKey")
        val main = args.getString("liveMain")
        assumeTrue(key != null || main != null)
        val store = ProviderStore.get(context)
        var saved: String? = null
        if (key != null) {
            val model = args.getString("liveModel") ?: error("liveModel missing")
            val id = args.getString("liveId") ?: model
            val baseUrl = args.getString("liveBaseUrl") ?: ProviderPresets.all.first().baseUrl
            //Ability is selected based on the template that matches the address.(It’s the same as selecting a template in settings,Method with prompt word caching)
            val preset = ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.all.first()
            store.save(
                ProviderConfig(
                    id, ProviderConfig.Kind.OpenAiCompat, baseUrl,
                    //View main model and view positioning verified:Open by parameter(Positioning verification is only given when there is actual measurement evidence)
                    model, preset.capabilities.copy(vision = args.getString("liveVision") == "true",
                        groundingVerified = args.getString("liveGroundingVerified") == "true"), name = id,
                ),
                key,
            )
            check(store.apiKey(id) == key) { "key not stored" }
            saved = id
        }
        //The configuration must have a purpose and be used,Otherwise, it will be treated as if no one is using it and it will be cleared.:If the main model is not specified, the newly saved one will be used.
        (main ?: saved)?.let { check(store.choose(ModelRole.Main, RoleChoice.Model(it))) { "cannot assign $it" } }
        //See the main model in the picture:Use the main model to view pictures
        if (args.getString("liveVision") == "true") store.choose(ModelRole.Vision, RoleChoice.SameAsMain)
    }

    @Test
    fun runGoal() {
        val goal = args.getString("liveGoal")
        assumeTrue(goal != null)
        //The process launched by the test is not set to the App language,Start as normal and change the default language to it:The built-in browser asks the website for the page language
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.let { android.os.LocaleList.setDefault(it) }
        }
        val run = args.getString("liveRun") ?: "run"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val moveAwayAt = args.getString("liveMoveAwayAtStep")?.toInt() ?: -1
        val cancelAt = args.getString("liveCancelAtStep")?.toInt() ?: -1
        val pace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        testTasks.evalHooks.operatingPace = pace
        var cancelled = false
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")

        DeviceSupport.shell()
        DeviceSupport.service()
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} pace=${pace.name.lowercase()} goal=$goal")
        //Learn to run in the process-level scope after the task ends;At the beginning, note down that there are already several permanent things there.,At the end, wait until it returns to this number
        val memory = MemoryRuntime.of(context)
        val learningJob = memory.scope.coroutineContext[Job]!!
        val resident = learningJob.children.count { it.isActive }

        compose.typeAndSend(goal!!)
        //Put away the keyboard after sending it out:The small background screen below the conversation page is revealed.,It’s like a real person collecting the keyboard after sending it.
        compose.runOnUiThread {
            val w = compose.activity.window
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val chat = (context.applicationContext as MinaApp).chat
        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
        //Sent out,This question comes up in the conversation;If not, report it immediately,Don't wait until timeout is in vain
        check(chat.ui.value.exchanges.any { it.goal == goal.trim() }) {
            "send did not go out: composer=${chat.ui.value.composer} exchanges=${chat.ui.value.exchanges.size} task=${DeviceTaskState.state.value}"
        }
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var takeovers = 0
        var movedAway = false
        var pausesHandled = 0
        var end: TaskState? = null
        var last: TaskState? = null
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = DeviceTaskState.state.value
            if (s != last) {
                note("state $s")
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                frame(dir, "step-%02d".format(s.step))
            }
            if (moveAwayAt > 0 && !movedAway && s is TaskState.Running && s.step >= moveAwayAt) {
                val shell = DeviceSupport.shell()
                val task = shell.topTaskId(testTasks.agentDisplayId.value)
                movedAway = true
                note("moving task $task (${shell.packageOfTask(task)}) to the main screen: ${task >= 0 && shell.moveTask(task, 0)}")
            }
            if (cancelAt > 0 && !cancelled && s is TaskState.Running && s.step >= cancelAt) {
                cancelled = true
                note("stopping at step ${s.step}")
                compose.runOnUiThread { (context.applicationContext as MinaApp).chat.cancel() }
            }
            if (s is TaskState.Held && s.reason.resumable && pausesHandled == 0) {
                pausesHandled++
                Thread.sleep(3000)
                val shell = DeviceSupport.shell()
                note("paused; still paused after 3s: ${DeviceTaskState.state.value}; main top task=${shell.topTaskId(0)} pkg=${shell.packageOfTask(shell.topTaskId(0))}")
                note("notifications: " + minaNotifications())
                mainShot(dir, "paused-main")
                TaskService.resume(context)
                note("resume requested")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                frame(dir, "takeover-agent")
                mainShot(dir, "takeover-main")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context) else TaskService.cancel(context)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                note("chat status at end: ${chat.ui.value.exchanges.lastOrNull()?.status}")
                break
            }
            //The interface test's clock only runs when the test pushes it:While waiting, push forward together,The steps, timings, and small screens of the conversation page will be refreshed as usual.(Used for recording demonstrations)
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context)
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        mainShot(dir, "end-main")
        //explanation after stopping:Wait for it to appear on the conversation page as the answer for this round
        val summary = if (end == TaskState.Cancelled) DeviceSupport.pollUntil(30_000) {
            chat.ui.value.exchanges.lastOrNull()?.status?.let { it as? com.meenai.mina.ui.ExchangeStatus.Cancelled }?.summary
        }.also {
            note("stop summary: $it")
            compose.waitForIdle()
            mainShot(dir, "stopped-summary")
        } else null
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        // liveSettleSec:Wait until the ending judgment and learning after handing over are completed.,Write down what you learned learned.txt
        val settleMs = (args.getString("liveSettleSec")?.toLong() ?: 0) * 1000
        if (settleMs > 0) {
            val settled = DeviceSupport.pollUntil(settleMs) { true.takeIf { learningJob.children.count { it.isActive } <= resident } } != null
            note("learning settled=$settled")
            File(dir, "learned.txt").writeText(learned(memory))
        }
        val keepSec = args.getString("liveKeepCheckSec")?.toLongOrNull() ?: 0
        val kept = if (keepSec > 0) keptPageCheck(keepSec, dir, ::note) else ""
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\npauses=$pausesHandled\nwallSec=$wall\ninputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "answer=$answer\n" + (summary?.let { "summary=$it\n" } ?: "") + kept,
        )
    }

    /**
     *Is the page left in the background after the task still running?:separated [seconds]Read the playback progress of the media in the page twice a second and a timer that only counts(Put it into the page only for this acceptance,
     *Don’t touch the page’s own stuff),Save a picture of its display. Return to write result.txt.
     */
    private fun keptPageCheck(seconds: Long, dir: File, note: (String) -> Unit): String {
        val keeper = (context.applicationContext as MinaApp).pageKeeper
        val page = keeper.page.value
        val tab = keeper.keptTab()
        if (page == null || tab == null) {
            note("no kept page after the run")
            return "keptPage=none\n"
        }
        val probe = "(function(){if(!window.__keepCheck){window.__keepCheck=0;setInterval(function(){window.__keepCheck++},200);}" +
            "return JSON.stringify({timer:window.__keepCheck,media:Array.from(document.querySelectorAll('video,audio')).map(function(m){return {t:m.currentTime,paused:m.paused}})});})()"
        fun read(): org.json.JSONObject? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var raw: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (tab.destroyed) latch.countDown() else tab.view.evaluateJavascript(probe) { raw = it; latch.countDown() }
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return raw?.let { r -> runCatching { org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String) }.getOrNull() }
        }
        val first = read()
        Thread.sleep(seconds * 1000)
        val second = read()
        note("kept page ${page.host}: before=$first after=$second")
        fun times(o: org.json.JSONObject?) = o?.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optDouble("t") } }.orEmpty()
        val before = times(first)
        val after = times(second)
        val mediaAdvanced = after.indices.any { i -> before.getOrNull(i)?.let { after[i] > it } == true }
        val timerAdvanced = (second?.optInt("timer") ?: 0) > (first?.optInt("timer") ?: 0)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { keeper.frames().filterNotNull().first() } }?.let { bmp ->
            File(dir, "kept-frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val notified = minaNotifications().contains(appString(R.string.kept_notif_title))
        //WebView can only ask on the main thread
        var keptUrl: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { keptUrl = if (tab.destroyed) null else tab.view.url }
        return "keptPage=${page.host}\nkeptUrl=$keptUrl\nkeptMedia=${before.size}\nkeptMediaAdvanced=$mediaAdvanced\n" +
            "keptTimerAdvanced=$timerAdvanced\nkeptPageAlive=${!tab.destroyed && keeper.page.value?.id == page.id}\nkeptNotification=$notified\n"
    }

    @Test
    fun runPageAsk() {
        val url = args.getString("liveUrl")
        val goal = args.getString("liveGoal")
        assumeTrue(url != null && goal != null)
        val run = args.getString("liveRun") ?: "page"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val touchAt = args.getString("liveTouchAtStep")?.toInt() ?: -1
        val resumeAfterMs = (args.getString("liveResumeAfterSec")?.toLong() ?: 5) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as MinaApp
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} url=$url goal=$goal")

        //Sidebar into browser,Open URL,Wait for the page to load
        compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
        compose.awaitOne(appString(R.string.drawer_browser)).performClick()
        compose.waitForIdle()
        val browser = UserBrowser.of(context)
        compose.runOnUiThread { browser.open(url!!)?.let { error("cannot open $url: $it") } }
        fun loaded() = DeviceSupport.pollUntil(30_000) { browser.page.value?.takeIf { !it.loading && it.progress >= 100 } } != null
        note("page loaded=${loaded()}")
        if (args.getString("liveDesktop") == "true") {
            compose.runOnUiThread { browser.setDesktop(true) }
            Thread.sleep(1000)
            note("desktop page loaded=${loaded()}")
        }
        Thread.sleep(1500)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-ask-bar").fetchSemanticsNodes().isNotEmpty() }
        if (args.getString("liveNew") == "true" && compose.onAllNodesWithTag("page-ask-fresh").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("page-ask-fresh").performClick()
        }
        mainShot(dir, "step-00")
        val before = DeviceTaskState.runs.value.toSet()
        compose.onNodeWithTag("page-ask-field").performTextInput(goal!!)
        compose.onNodeWithTag("page-ask-send").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val task = DeviceSupport.pollUntil(15_000) {
            DeviceTaskState.runs.value.firstOrNull { it !in before && AttachedPage.parse(it.goal).words == goal.trim() }
        } ?: error("the page question did not start a task: runs=${DeviceTaskState.runs.value.map { it.goal.take(60) }}")
        val conversationId = task.conversationId
        note("task ${task.key} conversation=$conversationId")
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var last: TaskState? = null
        var borrowed = false
        var pauses = 0
        var userPauses = 0
        var takeovers = 0
        var asked = false
        var touched = false
        var pausedByTouch = false
        var resumed = false
        var end: TaskState? = null
        //Pause every calculation that appears in the status stream(Polling misses brief pauses,It will also miss the period after touching and before restoring to test yourself and wait.)
        val seen = java.util.Collections.synchronizedList(mutableListOf<TaskState>())
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var prev: TaskState? = null
            task.state.collect { st ->
                if (st is TaskState.Held && prev !is TaskState.Held) seen += st
                prev = st
            }
        }
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (browser.loans.lentTo.value == conversationId) borrowed = true
            if (s != last) {
                note("state $s")
                if (s is TaskState.WaitingInput) asked = true
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                compose.mainClock.advanceTimeBy(250)
                mainShot(dir, "step-%02d".format(s.step))
            }
            if (touchAt > 0 && !touched && s is TaskState.Running && s.step >= touchAt) {
                touched = true
                note("finger swipe on the page at step ${s.step}: ${swipeOnPage()}")
                pausedByTouch = DeviceSupport.pollUntil(3_000) { true.takeIf { PageAskModel.pausedForUser(task.state.value) } } != null
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val banner = compose.onAllNodesWithTag("page-paused-banner").fetchSemanticsNodes().isNotEmpty()
                val card = compose.onAllNodesWithTag("page-card").fetchSemanticsNodes().isNotEmpty()
                note("paused by touch=$pausedByTouch banner=$banner card=$card state=${task.state.value}")
                mainShot(dir, "paused")
                Thread.sleep(resumeAfterMs)
                note("still paused after ${resumeAfterMs / 1000}s: ${PageAskModel.pausedForUser(task.state.value)}")
                if (banner) compose.onNodeWithTag("page-paused-resume").performClick() else TaskService.resume(context, task.key)
                resumed = DeviceSupport.pollUntil(5_000) { true.takeIf { !PageAskModel.pausedForUser(task.state.value) } } != null
                note("resumed=$resumed state=${task.state.value}")
                compose.waitForIdle()
                mainShot(dir, "resumed")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                mainShot(dir, "takeover")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                break
            }
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        watch.cancel()
        synchronized(seen) {
            pauses = seen.size
            userPauses = seen.count(PageAskModel::pausedForUser)
            note("pauses: ${seen.joinToString { (it as TaskState.Held).reason.why }}")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        mainShot(dir, "end")
        val userTabUrl = browser.page.value?.url
        val origin = app.conversations.pageOrigin(conversationId)
        val urls = recorder.toolCalls.filter { it.first == "web_navigate" }.mapNotNull { Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(it.second)?.groupValues?.get(1) }
        val reopened = urls.any { PageConversations.pageKey(it) == PageConversations.pageKey(url!!) }
        //Return to the conversation page and open the sidebar:This conversation is tagged "Web Page". First hide the notification bar and floating notifications(Covering it won’t cut off the sidebar);
        //The clock for interface testing must be pushed forward,The animation that slides out of the sidebar will complete.
        fun menuShown() = compose.onAllNodesWithContentDescription(appString(R.string.chat_menu)).fetchSemanticsNodes().isNotEmpty()
        for (i in 0 until 8) {
            if (menuShown()) break
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
        }
        var listedAsWeb = false
        for (attempt in 1..3) {
            sh("cmd statusbar collapse")
            Thread.sleep(1500)
            runCatching { compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performSemanticsAction(SemanticsActions.OnClick) }
                .onFailure { note("menu not clickable: $it") }
            repeat(8) {
                compose.mainClock.advanceTimeBy(250)
                Thread.sleep(150)
            }
            compose.waitForIdle()
            listedAsWeb = DeviceSupport.pollUntil(5_000) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { compose.onAllNodesWithTag("conversation-web", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } != null
            val drawerOpen = compose.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()
            note("drawer attempt $attempt: open=$drawerOpen webTag=$listedAsWeb")
            if (drawerOpen) break
        }
        mainShot(dir, "drawer")
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\nurl=$url\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\nasked=$asked\npauses=$pauses\nuserPauses=$userPauses\nwallSec=$wall\n" +
                "inputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "borrowed=$borrowed\nfirstToolCalls=${recorder.toolCalls.take(5).joinToString(" ; ") { "${it.first} ${it.second.take(120)}" }}\n" +
                "urlsOpened=${urls.joinToString(" ")}\nreopenedSameUrl=$reopened\nuserTabUrlAtEnd=$userTabUrl\n" +
                "touchAtStep=$touchAt\npausedByTouch=$pausedByTouch\nresumed=$resumed\n" +
                "conversationId=$conversationId\npageOrigin=${origin?.let { "${it.host} ${it.url} ${it.title}" }}\nlistedAsWeb=$listedAsWeb\n" +
                "answer=$answer\n",
        )
        //Mina's own clicking and scrolling should not make the task think that the user is operating
        if (touchAt <= 0) check(userPauses == 0) { "the run paused as if the user touched the page ($userPauses times) though nobody did" }
        else check(pausedByTouch && resumed) { "touch at step $touchAt: paused=$pausedByTouch resumed=$resumed" }
    }

    /**Use your finger to swipe up a short section of the web page on the browser page through the window.(Top half of web page,The panel cannot be covered;Swiping will not click on the link);Return to where it was drawn. */
    private fun swipeOnPage(): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        var at: IntArray? = null
        var size = 0 to 0
        instr.runOnMainSync {
            fun find(v: android.view.View): android.webkit.WebView? = when (v) {
                is android.webkit.WebView -> v.takeIf { it.isShown }
                is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            find(compose.activity.window.decorView)?.let { w ->
                at = IntArray(2).also(w::getLocationOnScreen)
                size = w.width to w.height
            }
        }
        val o = at ?: return "no page view"
        val x = o[0] + size.first / 2f
        val y0 = o[1] + size.second * 0.3f
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, t: Long) {
            val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            instr.sendPointerSync(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, y0, down)
        for (i in 1..5) send(android.view.MotionEvent.ACTION_MOVE, y0 - i * 12f, down + i * 16L)
        send(android.view.MotionEvent.ACTION_UP, y0 - 60f, down + 100)
        return "x=$x y=$y0"
    }

    /**Everything I learned right now:personal skills(In use and draft), various supplements of App knowledge, and the number of memorized routes. */
    private fun learned(memory: MemoryRuntime): String = buildString {
        val skills = memory.skills()
        skills.list().forEach { appendLine("skill ${it.name} status=${it.status} outcomes=${it.outcomes}\n${it.markdown}\n") }
        skills.drafts().forEach { (id, s) -> appendLine("skill-draft $id ${s.name}\n${s.markdown}\n") }
        memory.appKnowledge().all().forEach { appendLine("app ${it.appName} stage=${it.stage} lesson=${it.lesson} revises=${it.revises} trials=${it.trialOutcomes}\n${it.markdown}\n") }
        appendLine("routes=${(context.applicationContext as MinaApp).routes.count()}")
    }

    /**Mina The title, text and button of the current notification(from dumpsys notificationLi pick). */
    private fun minaNotifications(): String = sh("dumpsys notification --noredact")
        .split("NotificationRecord(").drop(1).filter { "pkg=com.meenai.mina " in it }
        .joinToString(" || ") { rec ->
            rec.lines().map { it.trim() }
                .filter { it.startsWith("android.title=") || it.startsWith("android.text=") || it.startsWith("[") && "\"" in it }
                .joinToString(" | ")
        }.take(1500)

    private fun frame(dir: File, name: String) {
        val bmp = DeviceTaskState.frames.value ?: return
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun mainShot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    /**record only,No change:Each response of the real model and the tool result of the previous step are written into the log. */
    private class Recorder(private val note: (String) -> Unit) {
        @Volatile var calls = 0
        @Volatile var inTokens = 0
        @Volatile var outTokens = 0
        /**Model tuning tools(name,parameter),In order. */
        val toolCalls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

        fun wrap(inner: ChatModel) = object : ChatModel {
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.forEach {
                    note("  result${if (it.isError) " ERROR" else ""}: ${it.content.take(400).replace('\n', ' ')}")
                }
                messages.lastOrNull()?.parts?.filterIsInstance<Part.Text>()?.forEach {
                    note("  user/tool text: ${it.text.take(300).replace('\n', ' ')}")
                }
                val t = System.currentTimeMillis()
                val reply = try {
                    inner.complete(system, messages, tools)
                } catch (e: Exception) {
                    note("model error: $e")
                    throw e
                }
                calls++
                reply.usage?.let { inTokens += it.inputTokens; outTokens += it.outputTokens }
                note("call#$calls ${System.currentTimeMillis() - t}ms stop=${reply.stopReason}")
                reply.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> note("  say: ${p.text.take(500).replace('\n', ' ')}")
                        is Part.ToolCall -> {
                            toolCalls += p.name to p.argsJson
                            note("  tool: ${p.name} ${p.argsJson.take(300)}")
                        }
                        is Part.Thinking -> note("  think: ${p.text.take(200).replace('\n', ' ')}")
                        else -> Unit
                    }
                }
                return reply
            }
        }
    }
}
