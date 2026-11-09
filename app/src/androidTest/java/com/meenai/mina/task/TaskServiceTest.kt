package com.meenai.mina.task

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.verify.Verifier
import com.meenai.mina.shell.IMinaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 *Real machine test:Driven by script model TaskService,Open system settings on the virtual screen,Check the always on, mute, removed pause and lock screen waiting.
 *Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMinaShell
    /**
     *One script model per task:The model call number and verification times are numbered separately for each task. share one hour,A call that was not taken away from the previous task
     * (Verification, closing)Will be fetched by waiting for the next task,The test ran to the front of the task.
     *In a task, it is not only the task itself that requires models from the factory:It also takes one time to create a title for a new conversation and to write an explanation after it has stopped.,And it is concurrent with the task,
     *No one can tell who will get it first. Therefore, the factory does not issue models according to "how many times they are requested",Always give the model of the most recently started task(See [model]).
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    /**The factory reads it on the task service's thread. */
    @Volatile private var started = 0
    /**most recent [start]The model of the starting task(It is used for the first task before starting the task.). */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        //The tasks here are all initiated from the interface,It is proposed that the first tool call will ask for the intent again.;Always answer "It takes action",Does not occupy script
        models.forEach { it.intent = "ACT" }
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        //Settings may remain on the home screen when the test fails midway:Just end this test and move it over by yourself
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /**Open the task and let it open the settings on the virtual screen,Return virtual screen number;At this point the model is waiting for the second step. */
    private fun startWithSettingsOpen(): Int {
        start("Test tasks")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /**Submit an answer with no verifiable value,Wait for the task to end:There is nothing to be correct in literal checking,No additional verification please,The outcome is accomplished.[appOpened]Just for clarity at the call site. */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        //The status changes to end first,Restore the sound and wait until the finishing touches are completed before changing back to busy false
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun minaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Mina:task'" in it }

    /**
     *The answer on the product path is no, please check again.(The task starts with reviewFinalAnswer =false run):Submit the answers given by the model as they are,The outcome is that it is done without verification.;
     *Neither the main model nor the lightweight model receives verification requests. The rules for verification itself are managed by core unit tests.
     */
    @Test
    fun answerHandedOverWithoutReview() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    //When equipped with a lightweight model,To judge the intention and create the title, ask it first;The only other option is verification,Count down
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system == TITLE) return ModelReply(listOf(Part.Text("test conversation")), StopReason.EndTurn, null)
                    if (system == Verifier.PROMPT) lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("Test tasks")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("Power is 9731 mAh")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "Power is 9731 mAh" && !end.reviewed && end.unconfirmed.isEmpty())
        assertEquals(0, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    /**Tasks that do not open the app do not create a virtual screen: phone_launch_app,Write operation failed no_app_open,No picture. */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("Hello")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        //first phone_launch_appCai Jianping,And only build one piece. The second task has its own script model,Calls are numbered starting from 1
        start("Test tasks")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        //Mute follows the open app in the bypass coroutine.,Hold on
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        //The simulated user opens the same app on the home screen App:The task has been moved to the home screen
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        //The model is still "reasoning"(Didn't get there gate)Pause should be displayed immediately when
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        //Does not automatically move back during pause
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     *Just watch dumpsys power,Do not change any global settings:Temporarily turn off "Always on USB" to wait for the actual screen-off timeout,
     *When a long time has passed since the last user operation, the home screen will be turned off and locked immediately.(Tested and stepped on),Not acceptable when left unattended.
     *The lock is disabled by the system(For example, background restrictions)dumpsys monogram DISABLED,Excluded here.
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = minaScreenLock()
        assertNotNull("Mina holds no screen wake lock while running", line)
        //Some systems add the lock object number before the type.(mLock:<id>),Only recognize type words
        //Only keep the screen on and allow the system to dim,Not locked to full brightness
        assertTrue(line!!, "SCREEN_DIM_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        //summary is a bitmask(0x..),AOSP's WAKE_LOCK_SCREEN_DIM = 1 shl 2;Print part of the version as text
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 2) != 0 else "SCREEN_DIM" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (minaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${minaScreenLock()}", released == true)
    }

    /**
     *stop:The interface has stopped on the spot,Then add a paragraph of explanation as the answer for this round.(The ending is still cancellation). Please write a lightweight model,Don't give tools;
     *Change the main model when an error occurs in the lightweight model,If you can’t write the main model, just press record and spell it out directly.
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    //The question and answer on judging the intention at the start of the game "requires operation",Other lightweight calls can only be written this time
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    //Don’t finish writing the explanation yet:You can see "organizing"
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("Settings were opened before stopping,Haven't read any pages yet.")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.meenai.mina.chat.ConversationStore.shared(context)
        //Make a note of "Cancelled" first,Stop waiting for explanation
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to "Canceled", placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != "Canceled" } }!!
        assertEquals("Settings were opened before stopping,Haven't read any pages yet.", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        //The lightweight model is wrong and the main model is in vain.:Press record to spell directly
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != "Canceled" } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("Stopped. Here is what it did before stopping:"))
        assertEquals(1, model.summaryRequests.get())
    }

    //Never enable unattended:it will send KEYCODE_SLEEPTurn off the screen and lock the phone,Only people can enter the password to unlock it.
    //Only remove manually when the user is present and ready to unlock @Ignore runs.
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        //Unlocked by human;It should automatically continue after unlocking,No need to click continue
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.meenai.mina.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.meenai.mina.core.prompt.Prompts.role("stop_summary") }
        val TITLE by lazy { com.meenai.mina.core.prompt.Prompts.role("conversation_title") }
    }
}
