package com.meenai.mina.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.MinaApp
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.BearerToken
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ResponsesModel
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.task.TaskEnvironment
import com.meenai.mina.task.testTasks
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed app's settings, TaskRun, recording and timeline with a local Responses endpoint. */
@RunWith(AndroidJUnit4::class)
class RunTraceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun releaseIgnoresAPreviouslyEnabledRecordingPreference() {
        org.junit.Assume.assumeFalse(com.meenai.mina.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MinaApp
        val prefs = app.getSharedPreferences("trace_settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("enabled", false)
        try {
            prefs.edit().putBoolean("enabled", true).apply()
            assertTrue(!app.detailedTraces.enabled && app.detailedTraces.ticket() == null)
            assertTrue(app.detailedTraces.start(null, "release-check") == null)
        } finally {
            prefs.edit().putBoolean("enabled", previous).apply()
        }
    }

    @Test fun recordsAndViewsARequestFromTheSettingsSwitch() {
        org.junit.Assume.assumeTrue(com.meenai.mina.record.DetailedTraces.supportedBuild)
        val app = compose.activity.application as MinaApp
        val previous = app.detailedTraces.enabled
        val previousLimit = app.detailedTraces.limitMiB
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Finish"}]}],"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":512},"output_tokens":2}}""")
                }
            }
            server.start()
            val model = ResponsesModel(server.url("/v1").toString(), BearerToken.fixed(null), "trace-device",
                Capabilities(true, false, 128_000, false), OkHttpClient())
            try {
                app.detailedTraces.setEnabled(false)
                // One local request exercises clipping without a long task or a large chat reply.
                val fixture = object : ChatModel by model {
                    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                        model.complete(system + " fixture".repeat(5_000), messages, tools)
                }
                testTasks.modelFactory = { _, _ -> fixture }
                testTasks.lightModelFactory = { _, _ -> null }
                testTasks.visionModelFactory = { _, _ -> null }
                // A fresh install starts on the optional setup page above the chat.
                compose.waitForIdle()
                if (compose.onAllNodesWithContentDescription("menu").fetchSemanticsNodes().isEmpty()) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                }
                compose.onNodeWithContentDescription("menu").performClick()
                compose.onNodeWithText("set up").performClick()
                compose.onNodeWithText("Record details Trace").assertDoesNotExist()
                compose.onNodeWithText("Developer options").performScrollTo().performClick()
                compose.onNodeWithText("Trace storage limit").performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("0")
                compose.onNode(hasText("save") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
                compose.onNode(hasSetTextAction()).performTextReplacement("37")
                compose.clickInDialog("save")
                compose.awaitOne("37 MiB").assertExists()
                assertTrue(com.meenai.mina.record.DetailedTraces(app).limitMiB == 37)
                compose.onNodeWithText("Record details Trace").performScrollTo().performClick()
                assertTrue(app.detailedTraces.enabled)
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.awaitOne("set up").assertExists()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.runOnUiThread {
                    app.chat.newConversation()
                    assertTrue(app.chat.send("Please reply to complete,TRACE_FIXTURE", Prerequisites.Check(emptyList())))
                }
                compose.waitUntil(30_000) { compose.onAllNodesWithText("View process").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("View process").performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Running process").fetchSemanticsNodes().isNotEmpty() }
                //On the running process page, the title is displayed first, and then the header of the run is read.:Wait for the head line to come out
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Export usage records").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("detailed Trace"))
                compose.onNodeWithText("detailed Trace").performScrollTo().performClick()
                //The page opens at the beginning of the task:The requested line will not appear until the request is recorded and the page is reread.
                val usage = "Input 1200 · Cache read 512 · Miss 688"
                compose.waitUntil(30_000) { compose.onAllNodesWithText(usage, substring = true).fetchSemanticsNodes().isNotEmpty() }
                val run = com.meenai.mina.record.RunStore(app).list().first { "TRACE_FIXTURE" in it.goal }
                compose.waitUntil(30_000) { !app.detailedTraces.store.recording(run.id) }
                //The request body was truncated,The entire Trace after recording is still marked as "Partial Recording"
                compose.waitUntil(5_000) { compose.onAllNodesWithText("partial record").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(usage, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("Save this time Trace").assertExists()
                val bytes = ByteArrayOutputStream().also { app.detailedTraces.store.archive(run.id)!!.save(it, false) }.toByteArray()
                assertTrue(bytes.isNotEmpty() && "TRACE_FIXTURE" !in bytes.decodeToString())
            } finally {
                app.detailedTraces.setEnabled(previous)
                app.detailedTraces.setLimitMiB(previousLimit)
                testTasks.modelFactory = TaskEnvironment.defaultModelFactory
                testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
                testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            }
        }
    }
}
