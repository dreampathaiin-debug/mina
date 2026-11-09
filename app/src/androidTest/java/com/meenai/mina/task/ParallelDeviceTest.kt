package com.meenai.mina.task

import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.shell.IMinaShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 *Real machine test:switch parallelTaskswhen open,Two dialogue tasks run at the same time,Each occupies a virtual screen.
 *Requires Android 14+, the built-in assistant is running, and the Mina accessibility service is turned on. The script model is divided into two tasks in starting order.
 */
@RunWith(AndroidJUnit4::class)
class ParallelDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMinaShell
    private val models = listOf(ScriptedModel(), ScriptedModel())
    private val handedOut = AtomicInteger()
    private lateinit var priorFlags: SpeedFlags

    @Before
    fun setUp() {
        assumeTrue("parallel displays need Android 14+", Build.VERSION.SDK_INT >= ParallelCapability.MIN_SDK)
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val flags = FeatureFlags.get(context)
        //Second run of kill process test:The first time I was killed, I didn’t run to finish.,The switch is still on;To restore to what was written down before the first start,Not at this moment
        priorFlags = killRecord.takeIf { it.exists() }?.let { runCatching { SpeedFlags.parse(JSONObject(it.readText()).getString(FLAGS_KEY)) }.getOrNull() }
            ?: flags.speedFlags()
        flags.setSpeed(priorFlags.copy(parallelTasks = true))
        testTasks.modelFactory = { _, _ -> models[handedOut.getAndIncrement().coerceAtMost(1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        if (!::priorFlags.isInitialized) return
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        FeatureFlags.get(context).setSpeed(priorFlags)
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun clockPackage(): String =
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?: error("no clock app handles ACTION_SHOW_ALARMS")

    /**The task of starting a new conversation,Wait for its model to be tuned for the first time;Returns the conversation number. */
    private fun startTask(goal: String, model: ScriptedModel): String {
        val before = DeviceTaskState.conversationId.value
        TaskService.start(context, goal)
        assertEquals(1, model.awaitCall().number)
        return pollUntil(5_000) { DeviceTaskState.conversationId.value?.takeIf { it != before } } ?: error("task did not start")
    }

    private fun displayOf(conversationId: String): Int =
        pollUntil(10_000) { DeviceTaskState.run(conversationId)?.displayId?.value?.takeIf { it >= 0 } } ?: -1

    private fun finish(model: ScriptedModel) = model.answer("done")

    private fun ended(conversationId: String): TaskState? {
        val run = DeviceTaskState.run(conversationId) ?: return TaskState.Cancelled
        return run.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled }
    }

    /**Two tasks run on two screens at the same time,All can be done;During this period, the user's home screen will not be affected. */
    @Test
    fun twoTasksRunOnTwoDisplays() {
        val mainBefore = DeviceSupport.mainScreenState(shell)
        val (a, b) = models
        val first = startTask("Set version number", a)
        val second = startTask("Create a new marked alarm in the clock", b)
        assertEquals(2, DeviceTaskState.runs.value.size)

        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"${clockPackage()}"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        val displayA = displayOf(first)
        val displayB = displayOf(second)
        assertTrue(displayA > 0 && displayB > 0)
        assertNotEquals(displayA, displayB)
        assertTrue(shell.topTaskId(displayA) >= 0)
        assertTrue(shell.topTaskId(displayB) >= 0)
        //Two screens operate independently,The focus of the home screen and the top task remain unchanged.
        a.feed("phone_back")
        b.feed("phone_back")
        assertEquals(3, a.awaitCall().number)
        assertEquals(3, b.awaitCall().number)
        assertEquals(mainBefore, DeviceSupport.mainScreenState(shell))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(20_000) { ended(first) })
        assertNotNull(pollUntil(20_000) { ended(second) })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        assertFalse("Mina display left behind", "mina-agent" in sh("dumpsys display"))
    }

    /**Both tasks need to open settings:Get the one that opens behind you app_in_use_by_other_task,Don't take the settings away from the first task. */
    @Test
    fun sameAppSecondTaskGetsConflictError() {
        val (a, b) = models
        val first = startTask("Set version number", a)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, a.awaitCall().number)
        val displayA = displayOf(first)
        val settingsTask = shell.topTaskId(displayA)

        startTask("Check storage in settings", b)
        b.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        val result = b.awaitCall().messages.last().toString()
        assertTrue(result, "app_in_use_by_other_task" in result)
        assertEquals(settingsTask, shell.topTaskId(displayA))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
    }

    /**
     * Review Focus 5:The Mina process was killed while two tasks were in progress.,Next time start two packages PLAY_AUDIOAll restored,Mina’s virtual screen is gone.
     *Tests run in the Mina process,Killing the process will end the test together.,So run it twice,Kill the process from outside in the middle:
     *Arranged for the first time, in files/parallel-kill-test.json leaves a record(Two original packages PLAY_AUDIOand the switch before starting),then wait;
     *After seeing the record appear, run it on the development machine `adb shell am force-stop <Mina package name>`(This time the report crashed).
     *second time(The process is restarted,Do recovery on startup)Check by record,And restore the switch to the one in the record(The first time I was killed, I didn’t run to finish.).
     *Notice:force-stop will remove Mina from enabled accessibility services(system behavior),It needs to be turned on again before running it for the second time.
     */
    private val killRecord get() = File(context.filesDir, "parallel-kill-test.json")

    @Ignore("two-run manual procedure: needs an external `adb shell am force-stop` while this test waits; a normal run would sit for 120 s and then fail")
    @Test
    fun killDuringTwoTasksRecovers() {
        val record = killRecord
        if (record.exists()) {
            val saved = JSONObject(record.readText())
            record.delete()
            try {
                for (pkg in saved.keys()) {
                    if (pkg == FLAGS_KEY) continue
                    assertEquals(pkg, saved.getString(pkg), pollUntil(60_000) { shell.getAudioMode(pkg)?.takeIf { it == saved.getString(pkg) } })
                }
                assertFalse("Mina display left behind", "mina-agent" in sh("dumpsys display"))
            } finally {
                //It will also be restored at the end;Restore here first,The switch is not left open when the assertion fails.
                FeatureFlags.get(context).setSpeed(priorFlags)
            }
            return
        }
        val clock = clockPackage()
        val prior = JSONObject().put(SETTINGS, shell.getAudioMode(SETTINGS)).put(clock, shell.getAudioMode(clock))
            .put(FLAGS_KEY, priorFlags.describe())
        val (a, b) = models
        startTask("Set version number", a)
        startTask("Create a new marked alarm in the clock", b)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"$clock"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(clock)?.takeIf { it == "ignore" } })
        record.writeText(prior.toString())
        //The suicide command placed in the background within the process will not be executed.(Ends with the test's shell session),It can only end from outside:
        //After seeing the record file appear, run it on the development machine adb shell am force-stop <package name>. If you can't wait, clear the record and end it as failure.
        Thread.sleep(KILL_WAIT_MS)
        record.delete()
        error("Mina was not killed: run `adb shell am force-stop ${context.packageName}` after ${record.name} appears, then rerun this test")
    }

    private companion object {
        const val FLAGS_KEY = "speed_flags"
        const val KILL_WAIT_MS = 120_000L
    }
}
