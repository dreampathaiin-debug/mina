package com.meenai.mina.cap.sandbox

import com.meenai.mina.DeviceTaskState
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 *Sandbox entry mission:The script model writes files, runs commands, and runs skill scripts in tasks. The sandbox needs to be installed(set up →sandbox →Install),Skip if not installed.
 *A real model(-e liveSandbox 1,Use configured model)Is it useful to record it? run_command.
 */
@RunWith(AndroidJUnit4::class)
class SandboxTaskDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val model = ScriptedModel()
    private val states = Collections.synchronizedList(mutableListOf<TaskState>())
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        watch.launch { DeviceTaskState.state.collect { states += it } }
    }

    @After fun tearDown() {
        watch.cancel()
        TaskService.cancel(context)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    private fun awaitEnd(timeoutMs: Long = 60_000): TaskState? =
        pollUntil(timeoutMs) { DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled } }

    @Test fun scriptedModelRunsCommandInTask() {
        TaskService.start(context, "Add up 1 to 10", "sandbox-task-AZZ")
        model.awaitCall()
        model.feed("write_file", """{"path":"sum.py","content":"print(sum(range(1, 11)))\n"}""")
        model.awaitCall()
        model.feed("run_command", """{"command":"python3 sum.py"}""")
        val after = model.awaitCall()
        val result = lastToolResult(after)
        assertTrue(result, result.contains("\"output\":\"55\\n\""))
        model.answer("55")
        val end = awaitEnd()
        assertTrue("$end", end is TaskState.Succeeded && "55" in end.answer)
        //The sandbox step is shown on the task card
        assertTrue(states.toString(), states.any { it is TaskState.Running && it.note.startsWith("Processing file:python3 sum.py") })
    }

    @Test fun scriptArgsWithSpacesReachScript() {
        //The test skills are added by "the packaged copy":The list and script verification are subject to the packaged version.,Anything written directly into the sandbox directory will be replaced.
        SkillsSync.extraForTests = mapOf(
            "skills/mina-device-test/scripts/manifest.json" to """[{"file":"scripts/echo.py","runnable":true}]""".toByteArray(),
            "skills/mina-device-test/scripts/echo.py" to "import sys\nfor a in sys.argv[1:]:\n    print(a)\n".toByteArray(),
        )
        try {
            TaskService.start(context, "Echo parameters", "sandbox-args-AZZ")
            model.awaitCall()
            model.feed("run_bundled_script", """{"source":"skills/mina-device-test","script":"scripts/echo.py","args":["Report (1).csv","a;rm -rf /","\"q\""]}""")
            val result = lastToolResult(model.awaitCall())
            assertTrue(result, result.contains("\"exitCode\":0"))
            assertTrue(result, result.contains("Report (1).csv\\na;rm -rf /\\n\\\"q\\\"\\n"))
            model.answer("alright")
            assertNotNull(awaitEnd())
        } finally {
            SkillsSync.extraForTests = emptyMap()
            runBlocking { SkillsSync.sync(SandboxDirs(context)) }
        }
    }

    /**real model(The one already configured in the App):Find the sum of squares from 1 to 100,Make note of whether or not a sandbox is used. only in -e liveSandbox1 hour run. */
    @Test fun liveModelUsesSandboxForArithmetic() {
        assumeTrue("pass -e liveSandbox 1 to run", args.getString("liveSandbox") == "1")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        TaskService.start(context, "Find the sum of the squares from 1 to 100", "sandbox-live-AZZ")
        val end = awaitEnd(300_000)
        val tools = states.mapNotNull { (it as? TaskState.Running)?.tool }.distinct()
        Log.i(TAG, "live sandbox run: end=$end tools=$tools usedRunCommand=${"run_command" in tools}")
        assertTrue("$end", end is TaskState.Succeeded && "338350" in end.answer)
        assertEquals(true, tools.isNotEmpty())
    }

    private companion object {
        const val TAG = "SandboxTaskDeviceTest"
    }
}
