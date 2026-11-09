package com.meenai.mina.ui

import com.meenai.mina.DeviceTaskState
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.MinaApp
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 *Real machine:Script model runs a 3-step task(Open settings, look at the screen, and submit the results),Click "View Process" on the results card,
 *There should be 3 lines on the timeline,Click on the line with the image to see the thumbnail of that time. Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RunTimelineDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = ScriptedModel()
    private val vm get() = (context.applicationContext as MinaApp).chat

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        compose.runOnUiThread { vm.newConversation() }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it == TaskState.Cancelled || it == TaskState.Idle } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test
    fun opensFromTaskCard() {
        compose.runOnUiThread { assertTrue(vm.send("What’s included in setting the home page?", Prerequisites.Check(emptyList()))) }
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "Open system settings first")
        model.awaitCall()
        model.feed("phone_observe", say = "Take a look at the settings home page")
        model.awaitCall()
        model.answer("Script demo:The settings home page includes network, battery, etc.")
        assertNotNull(pollUntil(15_000) { DeviceTaskState.state.value as? TaskState.Succeeded })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })

        compose.waitUntil(10_000) { compose.onAllNodesWithText("View process").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View process").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Step 1").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("3 steps in total").assertExists()
        for (n in 1..3) compose.onNodeWithText("No. $n steps").assertExists()
        compose.onNodeWithText("Step 4").assertDoesNotExist()
        compose.onNodeWithText("Done").assertExists()

        compose.onAllNodesWithText("Look at the scene at that time")[0].performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("picture", substring = true)).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(hasText("Close the screen")).fetchSemanticsNodes().let { assertTrue(it.size == 1) }
    }
}
