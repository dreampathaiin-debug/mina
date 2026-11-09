package com.meenai.mina.ui

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.MinaApp
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.SteerState
import com.meenai.mina.task.TaskEnvironment
import com.meenai.mina.task.TaskService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**Real conversation page and task service;Script model delayed cancellation closing,Let the message stay stable until it is handed over to the model. */
@RunWith(AndroidJUnit4::class)
class SteerEditingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MinaApp
    private val vm get() = app.chat
    private val script = ScriptedModel()
    private val release = CompletableDeferred<Unit>()
    private val first = AtomicBoolean(true)
    private var priorParallel = false
    private lateinit var conversation: String

    @Before fun setUp() {
        check(pollUntil(10_000) { true.takeIf { !vm.busy() && !app.taskState.busy.value } } != null)
        val flags = FeatureFlags.get(context)
        priorParallel = flags.speedFlags().parallelTasks
        flags.setSpeed(flags.speedFlags().copy(parallelTasks = false))
        val model = object : ChatModel {
            override val capabilities = script.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val hold = first.compareAndSet(true, false)
                try {
                    return script.complete(system, messages, tools)
                } finally {
                    if (hold) withContext(NonCancellable) { release.await() }
                }
            }
        }
        app.tasks.modelFactory = { _, _ -> model }
        app.tasks.lightModelFactory = { _, _ -> null }
        app.tasks.visionModelFactory = { _, _ -> null }
        app.tasks.evalHooks.rememberMemory = false
        app.tasks.evalHooks.learnSkills = false
        conversation = app.conversations.newConversation()
        app.conversations.setTitle(conversation, "Interjection withdrawal and edit verification")
        compose.runOnUiThread { vm.switchTo(conversation) }
        compose.waitForIdle()
        compose.runOnUiThread {
            val intent = Intent(compose.activity.intent).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, conversation)
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, intent)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer-field").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() {
        release.complete(Unit)
        if (::conversation.isInitialized) app.taskState.run(conversation)?.let { TaskService.cancel(context, it.key) }
        pollUntil(15_000) { true.takeIf { !app.taskState.busy.value && app.taskState.summarizing.value.isEmpty() } }
        FeatureFlags.get(context).let { it.setSpeed(it.speedFlags().copy(parallelTasks = priorParallel)) }
        app.tasks.evalHooks.reset()
        app.tasks.modelFactory = TaskEnvironment.defaultModelFactory
        app.tasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        app.tasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test fun withdrawalRemovesOnlyThatMessageBeforeModelPickup() {
        send("Please check today's information")
        script.awaitCall()
        val runId = app.taskState.run(conversation)!!.key
        send("Withdraw this addition")
        send("Keep this addition")
        compose.waitUntil(5_000) { vm.ui.value.exchanges.single().steers.size == 2 && vm.ui.value.exchanges.single().steers.all { it.state == SteerState.Received } }
        val (withdraw, keep) = vm.ui.value.exchanges.single().steers
        openSteer(withdraw.id)
        compose.onNodeWithTag("message-menu-edit").assertExists()
        compose.onNodeWithTag("message-menu-withdraw").assertExists()
        shot("withdraw-menu")
        compose.onNodeWithTag("message-menu-withdraw").performClick()
        waitMenuClosed()
        compose.onNodeWithTag("message-m0-${withdraw.id}").assertDoesNotExist()
        compose.onNodeWithTag("message-m0-${keep.id}").assertExists()
        release.complete(Unit)
        val next = script.awaitCall().messages.toString()
        assertFalse(next.contains(withdraw.text))
        assertTrue(next.contains(keep.text))
        assertEquals(runId, app.taskState.run(conversation)!!.key)
        assertEquals(1, vm.ui.value.exchanges.size)
        shot("withdraw-result")
    }

    @Test fun editRestoresTheQuoteAndProcessedMessagesHaveNoEditActions() {
        send("Please check today's information")
        script.awaitCall()
        val runId = app.taskState.run(conversation)!!.key
        compose.onNodeWithTag("message-u0").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-quote").performClick()
        waitMenuClosed()
        send("To summarize")
        compose.waitUntil(5_000) { vm.ui.value.exchanges.single().steers.singleOrNull()?.state == SteerState.Received }
        val old = vm.ui.value.exchanges.single().steers.single()
        openSteer(old.id)
        compose.onNodeWithTag("message-menu-edit").performClick()
        waitMenuClosed()
        compose.onNodeWithTag("composer-field").assertTextEquals("To summarize")
        compose.onNodeWithTag("quote-chip").assertExists()
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.You, "Please check today's information"), vm.ui.value.quote)
        compose.onNodeWithTag("message-m0-${old.id}").assertDoesNotExist()
        shot("edit-draft")
        compose.onNodeWithTag("composer-field").performTextClearance()
        send("Just make a summary")
        compose.waitUntil(5_000) { vm.ui.value.exchanges.single().steers.last().state == SteerState.Received }
        release.complete(Unit)
        val next = script.awaitCall().messages.toString()
        assertFalse(next.contains("To summarize"))
        assertTrue(next.contains("Just make a summary"))
        assertTrue(next.contains("Quoting my earlier message"))
        compose.waitUntil(5_000) { vm.ui.value.exchanges.single().steers.last().state == SteerState.Joined }
        assertEquals(runId, app.taskState.run(conversation)!!.key)
        openSteer(vm.ui.value.exchanges.single().steers.last().id)
        compose.onNodeWithTag("message-menu-copy").assertExists()
        compose.onNodeWithTag("message-menu-edit").assertDoesNotExist()
        compose.onNodeWithTag("message-menu-withdraw").assertDoesNotExist()
        shot("processed-menu")
    }

    //Interruptions added by the computer only belong to the current task:Long press on mobile phone can only copy, quote and select text.,Withdrawal was also rejected,Hand over to model as usual
    @Test fun remoteSteersOfferNoWithdrawOrEdit() {
        val started = app.remoteTasks.start("Please check today's information", conversation)
        val task = (started as com.meenai.mina.core.remote.StartOutcome.Started).task
        script.awaitCall()
        val id = checkNotNull(app.remoteTasks.steer(task.id, "Additional computer requirements"))
        compose.waitUntil(5_000) { vm.ui.value.exchanges.lastOrNull()?.steers?.any { it.id == id && it.state == SteerState.Received } == true }
        openSteer(id)
        compose.onNodeWithTag("message-menu-copy").assertExists()
        compose.onNodeWithTag("message-menu-edit").assertDoesNotExist()
        compose.onNodeWithTag("message-menu-withdraw").assertDoesNotExist()
        shot("remote-menu")
        assertFalse(app.taskState.run(conversation)!!.withdrawSteer(id))
        release.complete(Unit)
        assertTrue(script.awaitCall().messages.toString().contains("Additional computer requirements"))
    }

    private fun send(text: String) {
        compose.onNodeWithTag("composer-field").performTextInput(text)
        compose.onNodeWithContentDescription("").assertIsEnabled().performClick()
    }

    private fun openSteer(id: String) {
        compose.onNodeWithTag("message-m0-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu").assertExists()
    }

    private fun waitMenuClosed() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.waitForIdle(250, 5_000)
        android.os.SystemClock.sleep(500)
        val file = File(context.getExternalFilesDir(null), "steer-edit-shots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { ui.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
