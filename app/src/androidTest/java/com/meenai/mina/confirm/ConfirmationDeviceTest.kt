package com.meenai.mina.confirm

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.shell.IMinaShell
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 *Real machine test:The script model declares a money-spending operation in the virtual screen settings.(Just declare,never actually do it;Agree only if you pay money,All declarations include the amount),
 *Check the title, body, and Agree or Disagree buttons of high-priority notifications,There are three ways to disagree, agree, and comment and automatically reject.
 *Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMinaShell
    //Tasks initiated by the interface will ask for the intention again before the first tool is called.(This is the model you ask when you don’t have a lightweight model.):Fixed answer ACT,Does not occupy script
    private val model = ScriptedModel().apply { intent = "ACT" }
    private var appTask = -1

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        if (appTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == appTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun attention(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        ?.notification

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content

    /**Open settings on virtual screen,Then report "Place an order";The task is waiting for user consent when returned. */
    private fun startAndRequest() {
        TaskService.start(context, "Test confirmation level")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"order","target":"Test items","content":"Buy test items\nSecond line","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation }
    }

    @Test
    fun notificationHasApproveDecline() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        assertEquals("I want your consent:Place an order", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("exist ") && "object:Test items" in text && "content:Buy test item second line" in text && "Amount:¥25" in text)
        assertEquals(listOf("agree", "disagree"), n.actions.map { it.title.toString() })
        //and take over the same channel. High priority when creating a channel;The user or system may later adjust the(App cannot be changed back),The only requirement here is to be no lower than the default,and note the actual level
        assertEquals(TaskService.CHANNEL_ATTENTION, n.channelId)
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TaskService.CHANNEL_ATTENTION).importance
        Log.i("ConfirmationDeviceTest", "attention channel importance $importance")
        assertTrue("importance $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
        //Only actions are shown on the lock screen,Keep objects and content private
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        //The general prompt originally contains the word "object",What we want to check is that the specific objects and contents have not been revealed.
        val publicText = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, "Test items" !in publicText && "second line" !in publicText)
        //These two buttons can also be seen in the system notification list.
        val dump = sh("dumpsys notification --noredact")
        assertTrue("buttons not in dumpsys notification", "agree" in dump && "disagree" in dump)

        //Click answer on old notification(The declaration is not correct)Doesn't count
        context.startService(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_APPROVE)
            .putExtra(ConfirmationNotifier.EXTRA_REQUEST, 12345))
        Thread.sleep(1_000)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)

        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        assertEquals(true, pollUntil(3_000) { if (attention() == null) true else null })
        model.feed("end_not_done", """{"message":"The user did not agree to place the order,Didn't do this step"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed && "The user did not agree to place the order" in end.reason)
    }

    @Test
    fun approveButtonAnswersTheLoop() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        //By clicking the "Agree" button itself on the notification Intent
        n.actions.first { it.title.toString() == "agree" }.actionIntent.send()
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "\"approved\":true" in result)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value !is TaskState.WaitingConfirmation)
        //Don’t actually do it after agreeing:end directly
        model.feed("end_not_done", """{"message":"The test ends here"}""")
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
    }

    @Test
    fun evalAutoDeclineSkipsTheUser() {
        testTasks.evalHooks.autoDecideConfirmation = { false }
        TaskService.start(context, "Test confirmation level")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"send","target":"test","content":"transfer","amount":"¥25","app":"$SETTINGS"}""")
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        model.feed("end_not_done", """{"message":"No consent"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed)
    }

    /**After the second declaration,Clicking the "Agree" button on the first notification to yourself does not count. */
    @Test
    fun firstNotificationCannotApproveSecondRequest() {
        startAndRequest()
        val first = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        val firstApprove = first.actions.first { it.title.toString() == "agree" }.actionIntent
        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        assertTrue(lastToolResult(model.awaitCall()).contains("confirmation_declined"))
        //Change the type of action and declare it again(Apps of the same type will not be asked again if they have been rejected.)
        model.feed("request_confirmation", """{"action":"submit","target":"Test items","content":"Submit test item","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation && it.request.action.name == "submit" }
        pollUntil(5_000) { attention()?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "I want your consent:submit" } }
        runCatching { firstApprove.send() } //The old button may have been disabled by the system,Even if it is invalidated, it will be considered passed.
        Thread.sleep(1_500)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)
    }
}

