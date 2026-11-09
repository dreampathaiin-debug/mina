package com.meenai.mina.takeover

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.shell.IMinaShell
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 *Real machine test:The script model requests takeover on the settings subpage of the virtual screen;"I'll handle it" move the settings to the home screen and restore the sound,
 *"It's taken care of" Move back to the virtual screen and mute the sound again,Agent observes first in the next round.
 *Will temporarily occupy the home screen(Special testing machine),When finished, turn off Settings from the home screen. Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class TakeoverTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMinaShell
    private val model = ScriptedModel()
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        //App stays on home screen when canceling(Don’t take away from the user’s eyes);Turn off the test machine by yourself
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    /**
     *When there are too many notifications in this App, the system will automatically add a group summary(No title or button),it may follow the same channel,to skip;
     *In the past, "I got an empty title after ranking behind other tests" because I got this summary.
     */
    private fun attentionRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun attention(): Notification? = attentionRecord()?.notification

    private fun attentionActions(): List<String>? = attention()?.actions?.map { it.title.toString() }

    /**
     *Click the notification button:The button should open without displaying the interface [TakeoverActivity](This will close the notification bar),It is forwarded to the task service.
     *Test the process's own send button PendingIntentWill be blocked by restrictions on starting Activity in the background(Unlimited clicks from the notification bar),
     *So here after checking the target of the button,Launch it directly with the same Intent.
     */
    private fun tapAttentionAction(title: String, action: String) {
        val a = attention()?.actions?.firstOrNull { it.title.toString() == title } ?: error("no action $title: ${attentionActions()}")
        assertTrue(a.actionIntent.isActivity)
        context.startActivity(Intent(context, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun attentionTitle(): String? = attention()?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun handOverMovesToMainAndBack() {
        TaskService.start(context, "test takeover")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        val appTask = shell.topTaskId(displayId)
        assertTrue(appTask >= 0)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        //Open the settings subpage and request takeover on it. Some systems open subpages in another task(App login and payment pages often look like this too.),
        //The task that takes over the move should be the one at the top of the virtual screen that the user wants to handle.
        sh("am start --display $displayId -a android.settings.DISPLAY_SETTINGS")
        settingsTask = pollUntil(3_000) { shell.topTaskId(displayId).takeIf { it != appTask } } ?: appTask
        android.util.Log.i("TakeoverTest", "app task $appTask, sub-page task $settingsTask")

        model.feed("hand_to_user", """{"reason":"captcha","message":"Please complete the verification on the page"}""")
        assertEquals(TaskState.WaitingTakeover("captcha", "Please complete the verification on the page"), awaitState { it is TaskState.WaitingTakeover })
        assertEquals("need you to deal with:Verification code", pollUntil(5_000) { attentionTitle() })
        assertEquals(listOf("I'll handle it", "jump over", "Cancel task"), pollUntil(5_000) { attentionActions() })

        //"I'll handle it":Set to the top of the home screen,Can make a sound;This move was intentional.,Not taken away by users
        tapAttentionAction("I'll handle it", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(priorAudio, pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == priorAudio } })
        assertEquals(listOf("Dealt with it", "I finished it myself", "Cancel task"),
            pollUntil(5_000) { attentionActions()?.takeIf { it.size == 3 } })
        Thread.sleep(1_500) //Let the "removed" poll run for at least two rounds
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingTakeover)
        assertEquals(Takeover.Stage.HandedOver, DeviceTaskState.takeoverStage.value)

        //"It's taken care of"(The buttons on the interface go to the same entrance):Return to the virtual screen and mute again,Agent receives a prompt to verify first
        TaskService.handBack(context)
        val history = model.awaitCall().messages
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        //The result returned is hand_to_usertool results:complete,and asked to verify first
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        val text = last.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(text, "\"outcome\":\"complete\"" in text && AgentLoop.HANDBACK_NOTE in text)
        assertEquals(true, pollUntil(3_000) { if (attentionActions() == null) true else null })

        model.feed("phone_observe")
        val afterObserve = model.awaitCall().messages
        val result = afterObserve.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content, "\"currentApp\":\"$SETTINGS\"" in result.content)

        //Answer by citing an element name just read:it really read,Literal verification is correct,The ending is verified completion
        val quote = Regex("\"name\":\"([^\"\\\\]{2,40})\"").findAll(result.content).map { it.groupValues[1] }
            .firstOrNull { n -> n.count { it.isLetter() } >= 2 && n.none { it.isDigit() } } ?: error("no element name on screen: ${result.content}")
        val answer = "done,In the settings, there is "$quote""
        model.answer(answer)
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == answer && end.verified)
        //The status changes to end first,Restoring the sound is done in the ending,Wait until busy changes back to false and then check again
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**Open settings and request takeover,Wait until the notification appears;Returns the Agent's virtual screen number. */
    private fun requestTakeover(): Int {
        TaskService.start(context, "test takeover")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        model.feed("hand_to_user", """{"reason":"login","message":"Please take a look in the settings"}""")
        awaitState { it is TaskState.WaitingTakeover }
        pollUntil(5_000) { attentionActions() } ?: error("no takeover notification")
        return displayId
    }

    private fun lastToolResult(history: List<Message>): String {
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        return last.parts.filterIsInstance<Part.ToolResult>().single().content
    }

    /**The center of the first clickable item in the settings window on the home screen that is not a switch;Clicking it will only take you to the sub-page. */
    private fun clickableOnMainScreen(): Rect? = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == SETTINGS }
        ?.let { root ->
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isClickable && !n.isCheckable && n.isVisibleToUser && !n.text.isNullOrBlank()) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
                return null
            }
            find(root)?.let { Rect().also(it::getBoundsInScreen) }
        }

    @Test
    fun skipButtonPresent() {
        requestTakeover()
        assertEquals(listOf("I'll handle it", "jump over", "Cancel task"), attentionActions())
        tapAttentionAction("jump over", TaskService.ACTION_SKIP)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"skipped\"" in text && "\"reason\":\"takeover_skipped\"" in text)
        //Never took over,App is always on the virtual screen
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
    }

    @Test
    fun handBackReportsInputCount() {
        requestTakeover()
        tapAttentionAction("I'll handle it", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(Takeover.Stage.HandedOver, pollUntil(5_000) { DeviceTaskState.takeoverStage.value.takeIf { it == Takeover.Stage.HandedOver } })

        //The user taps the app twice on the home screen:Enter an entry,return,Go in again
        repeat(2) { i ->
            val target = pollUntil(5_000) { clickableOnMainScreen() } ?: error("nothing clickable in settings on the main screen")
            sh("input -d ${Display.DEFAULT_DISPLAY} tap ${target.centerX()} ${target.centerY()}")
            Thread.sleep(1_000)
            if (i == 0) {
                sh("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
                Thread.sleep(1_000)
            }
        }

        TaskService.handBack(context)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"complete\"" in text)
        val count = Regex("\"inputCount\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: error("no inputCount: $text")
        assertTrue(text, count >= 2)
        assertTrue(text, "\"stoppedAt\":" in text)
    }
}
