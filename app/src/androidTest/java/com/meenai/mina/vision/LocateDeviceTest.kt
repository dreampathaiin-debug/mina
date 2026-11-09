package com.meenai.mina.vision

import com.meenai.mina.runAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.vision.VisionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import com.meenai.mina.core.assembly.toolDispatcher

/**
 * phone_find_visuallyRead-only browsing of system settings on the Agent virtual screen(Requires built-in assistants and accessibility services).
 *Didn't really look at the model:Script model for visual paths,The frame is calculated based on the coordinates of the "WLAN" row in the control tree.
 */
@RunWith(AndroidJUnit4::class)
class LocateDeviceTest {
    private var agent: DeviceSupport.Agent? = null

    @After
    fun tearDown() {
        agent?.release()
    }

    /**Visual models of fixed responses are given in turn. */
    private class ScriptedVision(vararg answers: String, verified: Boolean = true) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = true, contextTokens = 32_000, reasoning = false, groundingVerified = verified)
        private val answers = ArrayDeque(answers.toList())
        var asked = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            asked++
            return ModelReply(listOf(Part.Text(answers.removeFirst())), StopReason.EndTurn, null)
        }
    }

    private fun DeviceSupport.Agent.snapUntil(test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun openSettingsHome(): Pair<DeviceSupport.Agent, ScreenSnapshot> {
        val a = DeviceSupport.newAgent().also { agent = it }
        val r = runBlocking { a.screen.openApp(SETTINGS) }
        assertTrue("openApp: $r", r is ActionResult.Ok)
        val home = a.snapUntil { s -> s.elements.any { it.name.trim() == "WLAN" } }
        //Transition animation ends,It takes two screenshots to get it right
        Thread.sleep(1_000)
        return a to home
    }

    private fun ToolDispatcher.run(name: String, args: String): JsonObject =
        runBlocking { runAction(Part.ToolCall("d", name, args)).content }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun tapAndExpectWlanPage(a: DeviceSupport.Agent, d: ToolDispatcher, r: JsonObject) {
        val tap = d.run("phone_tap", """{"ref":"${r.s("ref")}","snapshotId":"${r.s("snapshotId")}"}""")
        assertEquals(tap.toString(), "true", tap.s("ok"))
        a.snapUntil { s -> s.elements.any { it.role == "Switch" } }
    }

    @Test
    fun treePathFindsWlan() {
        val (a, _) = openSettingsHome()
        val model = ScriptedVision()
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("elements", r.s("source"))
        assertEquals("Control tree hits regardless of visual model", 0, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun visionPathTapsWlan() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        //screen reader 0–999 is used to replace the model. 0–1000
        val box = row.map { (it * 1000 / 999.0).roundToInt() }
        val model = ScriptedVision("""{"bbox_2d": [AZZ], "seen": "WLAN"}""", "yes")
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        //Don't give text:Don’t go through the control tree
        val r = d.run("phone_find_visually", """{"target":"WLAN line"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("vision", r.s("source"))
        assertEquals(2, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun unverifiedVisionNeedsSecondLook() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        val box = """{"bbox_2d": [${row.map { (it * 1000 / 999.0).roundToInt() }.joinToString(", ")}], "seen": "WLAN"}"""
        //Positioning accuracy has not been verified:Frame it again and check it twice before handing it over.
        val model = ScriptedVision(box, "yes", box, "yes", verified = false)
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN line"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals(4, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }
}
