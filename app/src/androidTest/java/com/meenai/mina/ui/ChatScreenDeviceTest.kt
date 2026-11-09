package com.meenai.mina.ui

import com.meenai.mina.DeviceTaskState
import android.app.UiAutomation
import kotlinx.coroutines.runBlocking
import com.meenai.mina.cap.sandbox.ShareExporter
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.chat.ConversationStore
import androidx.compose.ui.test.onAllNodesWithContentDescription
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meenai.mina.ui.viewer.FileViewerActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.MinaApp
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 *Walk through the dialogue page on a real machine:Script model opens settings on virtual screen,Check steps, small windows(Collapse, expand, drag, automatically expand when needed)and result cards,
 *Capture a picture at each stage to the external file directory of the App shots/(by development machine adbPull out and check manually).
 *Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    //Tasks initiated from the interface must ask for the intention again before the first action.;Fixed answer "needs operation",Each question in the script is a step in the task cycle itself.
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val vm get() = (context.applicationContext as MinaApp).chat
    private val anyway = Prerequisites.Check(emptyList())
    private var savedSpeed: SpeedFlags? = null

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        File(context.getExternalFilesDir(null), "shots").mkdirs()
        //The interface state is shared by the entire process:Wait for all the tasks of the previous test to be completed,Change to a new conversation,so as not to pick up its end state
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        //Parallel switch is a persistent setting,Other tests(Or it collapsed midway)May keep true;Each test decides for itself,Default off
        FeatureFlags.get(context).let { f -> savedSpeed = f.speedFlags(); f.setSpeed(savedSpeed!!.copy(parallelTasks = false)) }
        //When the conversation page still has the end status of the previous conversation newConversationIt will be regarded as "busy" and not executed.:Wait for it to return to idle,And confirm that it has been replaced by an empty dialogue
        assertNotNull("composer never went idle", pollUntil(20_000) { true.takeIf { !vm.busy() } })
        compose.runOnUiThread { vm.newConversation() }
        compose.waitForIdle()
        assertTrue("not a fresh conversation: ${vm.ui.value.exchanges}", vm.ui.value.exchanges.isEmpty())
    }

    @After
    fun tearDown() {
        DeviceTaskState.runs.value.forEach { it.cancel() }
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.active() } }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
        //Restore persistent settings to how they were before this test,Do not leave equipment on the switch for testing
        savedSpeed?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    private fun TaskState.active() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled && this != TaskState.Idle

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        // UiAutomationWill suppress other accessibility services,Mina's service will be disconnected
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**The line of activity in dumpsys that is in the foreground at the moment. */
    private fun topResumed(dump: String): String? =
        dump.lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }

    /**Does the Activity in the foreground belong to this App? [cls](In dumpsys, it is written as "package name"/.Relative class name). */
    private fun onTop(cls: Class<*>): Boolean {
        val line = topResumed(sh("dumpsys activity activities")) ?: return false
        val short = "${context.packageName}/" + cls.name.removePrefix(context.packageName)
        return line.contains("$short ") || line.contains("${context.packageName}/${cls.name} ")
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    @Test
    fun runsTaskWithStepsPipAndResult() {
        shot("01-idle")

        compose.typeAndSend("Open settings and check the battery")
        if (vm.ui.value.blocked != null) {
            //This phone does not come with a model, etc.:I also took a screenshot of the notification about being stopped.,Then bypass the check and run it with a script model
            shot("02-blocked")
            //Bypass the check and hand it over directly to the interface state,The text interface in the input box will not be cleared for it;The input box cannot be edited after the task starts.,So clear it first
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.runOnUiThread { vm.dismissBlocked(); assertTrue(vm.send("Open settings and check the battery", anyway)) }
        }

        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS","reason":"Open system settings first"}""")
        assertEquals(2, model.awaitCall().number)
        model.feed("phone_observe", """{"reason":"Take a look at what's included in the home page settings"}""")
        assertEquals(3, model.awaitCall().number)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        //The progress of a line at the moment while running,The vertical lines below list what you have done:Action uses the model’s own words,Where did you read and write?(The purpose of not writing the model to reading).
        //Currently waiting for the third call of the model,The progress line says "Processing",The last step "View settings" is the current step
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Processing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Checked the settings").assertExists()
        compose.onNodeWithText(ChatCopy.BACKGROUND_NOTE).assertExists()
        compose.onNodeWithText("Open system settings first").assertExists()
        compose.onNodeWithText(ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true)).assertExists()
        assertTrue(vm.ui.value.pip.visible)
        shot("03-running-collapsed")

        compose.onNodeWithContentDescription("Mina's virtual screen").performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        shot("04-running-expanded")
        compose.onNodeWithContentDescription("").performClick()
        compose.waitForIdle()

        //The small window is usually in the lower right corner(above the input field):Throw it up and to the left,It should be sucked to the upper left corner
        val d = context.resources.displayMetrics.density
        val pip = compose.onNodeWithContentDescription("Mina's virtual screen").fetchSemanticsNode().boundsInWindow
        val x = pip.center.x
        val y = pip.center.y
        sh("input swipe ${x.toInt()} ${y.toInt()} ${(x - 250 * d).toInt()} ${(y - 300 * d).toInt()} 120")
        compose.waitForIdle()
        val moved = compose.onNodeWithContentDescription("Mina's virtual screen").fetchSemanticsNode().boundsInWindow
        assertTrue("pip did not move up-left: $pip -> $moved", moved.center.x < x && moved.center.y < y)
        shot("05-dragged")

        model.feed("hand_to_user", """{"reason":"captcha","message":"Verification pops up in settings(Script demo),You need to deal with it."}""")
        awaitState { it is TaskState.WaitingTakeover }
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        compose.onNodeWithText("need you to deal with:Verification code").assertExists()
        compose.onNodeWithText("Suspended").assertExists()
        shot("06-takeover")

        //The expanded window and mask cover the cards in the conversation:Hide it first and then click the button on the card
        compose.onNodeWithContentDescription("").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel task").performClick()
        awaitState { it == TaskState.Cancelled }
        //Complementary explanation after suspension(Script model is not written,Press record to spell directly)Ranked under "Stopped."
        compose.onNodeWithText("Stopped.").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Stopped. Here is what it did before stopping:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("06b-stopped-summary")

        //Then start a new task:The service may still be finishing up the previous one,You have to wait until the interface is collected before handing it over.
        compose.runOnUiThread { assertTrue(vm.send("How much is the battery in settings?", anyway)) }
        model.awaitCall() //The call number of the script model is counted downwards from the previous task.
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "Open settings")
        model.awaitCall()
        model.answer("Script demo:Battery information can be found in Settings ›"Battery".")
        awaitState { it is TaskState.Succeeded }
        compose.waitForIdle()
        compose.onNodeWithText("source", substring = true).assertExists()
        //The canceled question and the completed question each have a "view process"
        assertEquals(2, compose.onAllNodesWithText("View process").fetchSemanticsNodes().size)
        shot("07-done")

        //sidebar:Return to conversation page(Side slip)Open the sidebar first;Five portals and recent conversations
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithText("scheduled tasks").assertIsDisplayed()
        compose.onNodeWithText("memory and skills").assertIsDisplayed()
        compose.onNodeWithText("").assertIsDisplayed()
        compose.onNodeWithText("recent").assertIsDisplayed()
        shot("08-drawer")
    }

    /**Attachment button only appears when the sandbox is not "Not supported on this device"(Not installed, installed, or broken, it can still be installed and repaired.). */
    @Test
    fun attachButtonHiddenOnlyWhenSandboxUnsupported() {
        val unsupported = SandboxState.get(context).status.value is SandboxStatus.Unsupported
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithContentDescription("Add attachment").fetchSemanticsNodes()
        assertEquals("sandbox unsupported=$unsupported", !unsupported, nodes.isNotEmpty())
    }

    /**File card shows name and size;Click on the text file to enter the view page in the App(No longer left to the system to choose App),Can see file contents,Return to the conversation. */
    @Test
    fun fileCardOpens() {
        val ws = SandboxDirs(context).workspace("card-device-test").apply { mkdirs() }
        val f = File(ws, "card.txt").apply { writeText("hello") }
        val name = "mina-card-${System.currentTimeMillis()}.txt"
        val card = runBlocking { com.meenai.mina.cap.sandbox.AndroidFdSys.gate.openRead(ws, f.name).use { ShareExporter(context).export(it, name) } }
        val store = ConversationStore(context)
        val id = store.newConversation()
        store.append(id, Turn("Export", "alright", "succeeded"), listOf(card))
        try {
            compose.runOnUiThread { vm.openConversation(id) }
            compose.onNodeWithText(card.name).assertExists()
            compose.onNodeWithText(card.name).performClick()
            val shown = pollUntil(5_000) { true.takeIf { onTop(FileViewerActivity::class.java) } }
            assertNotNull("file viewer did not open: ${topResumed(sh("dumpsys activity activities"))}", shown)
            //View page is another Activity,But in the same process as the conversation page,Its interface can also be found here:name in top column,Content is displayed as is
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello", substring = true, useUnmergedTree = true).assertIsDisplayed()
            shot("file-viewer")
            sh("input keyevent KEYCODE_BACK")
            val back = pollUntil(5_000) { true.takeIf { onTop(MainActivity::class.java) } }
            assertNotNull("back did not return to the chat page", back)
        } finally {
            context.contentResolver.delete(Uri.parse(card.uri), null, null)
            ws.deleteRecursively()
        }
    }

    /**Parallel switch on:Each of the two conversations runs a task.,There are dots on the menu,Click on another conversation in the "In Progress" sidebar to switch to it. */
    @Test
    fun otherTaskChipSwitches() {
        val flags = FeatureFlags.get(context)
        val prior = flags.speedFlags()
        flags.setSpeed(prior.copy(parallelTasks = true))
        val models = listOf(ScriptedModel().apply { intent = "ACT" }, ScriptedModel().apply { intent = "ACT" })
        //The model factory not only collects tasks when it starts:The title of the new conversation is also picked up again in the background.,The sequence is uncertain. Rotating by number will cause the first task to get the second model;
        //Change it to the first task. Before I really asked the model, I always gave it the first one.,Then give it to the second one(Which model has a fixed answer to the title question?)
        val firstStarted = AtomicBoolean(false)
        testTasks.modelFactory = { _, _ -> if (firstStarted.get()) models[1] else models[0] }
        try {
            compose.runOnUiThread { assertTrue(vm.send("first task", anyway)) }
            assertEquals(1, models[0].awaitCall().number)
            firstStarted.set(true)
            val first = vm.ui.value.conversationId
            compose.runOnUiThread {
                vm.newConversation()
                assertTrue(vm.send("second task", anyway))
            }
            assertEquals(1, models[1].awaitCall().number)
            val second = vm.ui.value.conversationId
            assertTrue(first != second)

            compose.onNodeWithContentDescription("menu").performClick()
            compose.waitForIdle()
            //"In Progress" is the section title,It's also the small print under the line of another conversation.:Separate recognition by location - title at the top,Below is that line(The title and small text are combined in a clickable node)
            val inDrawer = hasAnyAncestor(hasTestTag("drawer"))
            val other = compose.onNode(hasText("first task") and hasText("in progress") and inDrawer)
            other.assertIsDisplayed()
            val header = compose.onNode(hasText("in progress") and !hasText("first task") and inDrawer)
            header.assertIsDisplayed()
            val headerTop = header.fetchSemanticsNode().boundsInWindow.top
            val otherTop = other.fetchSemanticsNode().boundsInWindow.top
            assertTrue("running entry not underthe in progress header: $headerTop vs $otherTop", otherTop > headerTop)
            other.performClick()
            compose.waitForIdle()
            assertEquals(first, vm.ui.value.conversationId)
            assertEquals("first task", vm.ui.value.exchanges.last().goal)
            shot("parallel-drawer")

            models.forEach { it.answer("done") }
            assertNotNull(pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } })
        } finally {
            DeviceTaskState.runs.value.forEach { it.cancel() }
            pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
            flags.setSpeed(prior)
        }
    }
}
