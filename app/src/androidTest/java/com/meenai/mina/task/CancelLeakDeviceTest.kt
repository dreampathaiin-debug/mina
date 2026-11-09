package com.meenai.mina.task

import com.meenai.mina.DeviceTaskState
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 *Real machine test:The task was canceled just after building the virtual screen or web page display.,Repeat 20 times,After the completion, Mina will not be displayed in the system.
 *The timing of cancellation is staggered,Falling in different positions before and after building the screen. Requires the built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class CancelLeakDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = List(ROUNDS) { ScriptedModel() }
    private val handed = AtomicInteger()

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> models[handed.getAndIncrement().coerceAtMost(ROUNDS - 1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test
    fun quickCancelLeavesNoDisplay() {
        repeat(ROUNDS) { i ->
            val model = models[i]
            TaskService.start(context, "Test tasks $i")
            assertEquals(1, model.awaitCall().number)
            //Take turns to build virtual screens and web page displays
            if (i % 2 == 0) model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            else model.feed("web_navigate", """{"url":"https://example.com/"}""")
            Thread.sleep((i / 2 % 5) * 40L)
            TaskService.cancel(context)
            assertNotNull("round $i did not end", pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        }
        val displays = sh("dumpsys display")
        assertFalse("Mina agent display left behind", "mina-agent" in displays)
        assertFalse("Mina web display left behind", "mina-web" in displays)
    }

    private companion object {
        const val ROUNDS = 20
    }
}
