package com.meenai.mina.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.MinaApp
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.record.RunStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OtlpSendDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmedSendSurvivesCollapseAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "test-otlp").toFile()
        val runs = RunStore(dir, { null }, keep = 1, clock = System::currentTimeMillis) { it }
        val runId = runs.open("OTLP test").use { it.runId }
        val store = (context.applicationContext as MinaApp).detailedTraces.store
        try {
            val trace = store.start(runId)!!
            trace.event(RunEvent.TaskEnded(0, "succeeded", ""))
            store.finish(trace)
            val archive = store.archive(runId)!!
            val bytes = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
            MockWebServer().use { receiver ->
                val vm = RunTimelineViewModel(runs, runId)
                fun showTimeline() {
                    compose.activityRule.scenario.onActivity { it.setContent { RunTimelineScreen(vm, onBack = {}) } }
                    compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNode(hasScrollToIndexAction()).performScrollToKey("trace")
                    compose.awaitOne("detailed Trace")
                    if (compose.onAllNodesWithText("pass OTLP/HTTP send").fetchSemanticsNodes().isEmpty())
                        compose.onNodeWithText("detailed Trace").performScrollTo().performClick()
                }
                fun toggle() = compose.awaitOne("detailed Trace").performScrollTo().performClick()
                showTimeline()
                fun confirm() = compose.onNode(hasText("pass OTLP/HTTP send") and hasClickAction() and hasAnyAncestor(isDialog()))
                compose.onNodeWithText("pass OTLP/HTTP send").performScrollTo().performClick()
                compose.onNodeWithTag("trace-endpoint").performTextReplacement("http://8.8.8.8/v1/traces")
                confirm().assertIsNotEnabled()
                compose.onNodeWithTag("trace-endpoint").performTextReplacement(receiver.url("/v1/traces").toString())
                val auth = compose.onNodeWithTag("trace-auth-value").performScrollTo().assertIsDisplayed()
                auth.performTouchInput { click() }
                auth.performTextInput("Bearer test-only")
                assertEquals(0, receiver.requestCount)
                confirm().performClick()
                val request = receiver.takeRequest(12, TimeUnit.SECONDS)!!
                assertEquals("Bearer test-only", request.getHeader("Authorization"))
                assertArrayEquals(bytes, request.body.readByteArray())
                // Hold the acknowledgement until the controls have left and re-entered composition.
                toggle()
                toggle()
                compose.onNodeWithText("pass OTLP/HTTP send").assertExists()
                compose.onNode(hasText("pass OTLP/HTTP send") and hasClickAction()).assertDoesNotExist()
                compose.activityRule.scenario.recreate()
                showTimeline()
                compose.onNodeWithText("pass OTLP/HTTP send").assertExists()
                compose.onNode(hasText("pass OTLP/HTTP send") and hasClickAction()).assertDoesNotExist()
                receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf"))
                compose.awaitOne("The receiving end has accepted").assertExists()
                assertEquals(1, receiver.requestCount)
                compose.onNodeWithText("pass OTLP/HTTP send").performScrollTo().performClick()
                compose.onNodeWithTag("trace-auth-value").assert(hasText(""))
            }
        } finally {
            File(context.noBackupFilesDir, "harness-traces/$runId").deleteRecursively()
            dir.deleteRecursively()
        }
    }
}
