package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.runAction
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**Real machine test:Unimportant nodes can be read, focus changes are counted as changes, prompts are marked, and input controls can still be verified after being replaced. */
@RunWith(AndroidJUnit4::class)
class ScreenAccuracyTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private suspend fun snapUntil(timeoutMs: Long = 5_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.packageName} ${s.elements.map { "${it.role}:${it.name}" }}", test(s))
        return s
    }

    private fun ok(r: ActionResult): ActionResult.Ok {
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        return r as ActionResult.Ok
    }

    private suspend fun openFields(): ScreenSnapshot {
        DeviceSupport.startTestActivity(agent.display.displayId, FieldsTestActivity::class.java)
        return snapUntil { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.name == "7" } }
    }

    private fun ScreenSnapshot.named(name: String): UiElement = elements.first { it.name == name }

    @Test
    fun notImportantResultTextIsReadAndItsChangeCounts() = runBlocking<Unit> {
        val s = openFields()
        assertTrue("result text not read: ${s.elements.map { it.name }}", s.elements.any { it.name == "0" })
        val r = ok(screen.tapRef(s.named("7").ref, s.snapshotId))
        assertEquals(true, r.changed)
        snapUntil { snap -> snap.elements.any { it.name == "07" } }
    }

    @Test
    fun focusingAFieldCountsAsChange() = runBlocking<Unit> {
        val s = openFields()
        val r = ok(screen.tapRef(s.named("Empty input box").ref, s.snapshotId))
        assertEquals(true, r.changed)
    }

    @Test
    fun hintTextIsMarked() = runBlocking<Unit> {
        val s = openFields()
        val field = s.named("Hot word examples")
        assertTrue("$field", field.editable && field.hint)
        assertFalse(s.named("7").hint)
        assertEquals(Verification.MATCH, ok(screen.typeText(field.ref, s.snapshotId, "coffee")).verification)
        val after = snapUntil { snap -> snap.elements.any { it.name == "coffee" } }
        assertFalse(after.named("coffee").hint)
    }

    @Test
    fun readbackFollowsAReplacedInputView() = runBlocking<Unit> {
        val s = openFields()
        val r = ok(screen.typeText(s.named("Frame that can be changed").ref, s.snapshotId, "weather"))
        assertEquals(Verification.MATCH, r.verification)
    }

    @Test
    fun typingIntoAFakeSearchBarTapsItAndUsesTheFocusedField() = runBlocking<Unit> {
        val s = openFields()
        val fake = s.named("fake search box")
        assertFalse(fake.editable)
        val r = ok(screen.typeText(fake.ref, s.snapshotId, "coffee"))
        assertEquals(Verification.MATCH, r.verification)
        assertTrue("${r.hint}", r.hint?.contains("tapped") == true)
        snapUntil { snap -> snap.elements.any { it.editable && it.name == "coffee" } }
    }

    @Test
    fun typingIntoAButtonDoesNotTapIt() = runBlocking<Unit> {
        val s = openFields()
        assertEquals(ActionResult.Failed("not_editable"), screen.typeText(s.named("7").ref, s.snapshotId, "coffee"))
        //Didn't click on it:The result is still 0
        assertTrue(screen.snapshot().elements.any { it.name == "0" })
    }

    /** scrolldown to see more below:Set the original bottom item on the home page to move up.(Or an item that wasn’t there originally appears.). */
    @Test
    fun scrollDownShowsContentFurtherDown() = runBlocking<Unit> {
        ok(screen.openApp(DeviceSupport.SETTINGS, cold = true))
        val before = snapUntil { s -> s.elements.any { it.clickable && it.name.startsWith("WLAN") } }
        val tools = com.meenai.mina.core.assembly.toolDispatcher(screen)
        val r = tools.runAction(com.meenai.mina.core.model.Part.ToolCall("c1", "phone_scroll", """{"direction":"down"}""")).content
        assertEquals(r.toString(), "true", r["changed"].toString())
        assertEquals(r.toString(), "\"up\"", r["fingerMoved"].toString())
        val after = screen.snapshot()
        val newNames = after.elements.map { it.name }.toSet() - before.elements.map { it.name }.toSet()
        val wlan = after.elements.firstOrNull { it.name.startsWith("WLAN") }
        val wlanBefore = before.elements.first { it.name.startsWith("WLAN") }
        assertTrue("content did not move up: new=$newNames", newNames.isNotEmpty() && (wlan == null || wlan.centerY < wlanBefore.centerY))
    }

    /**System Calculator(Find by AOSP calculator category,If not, skip it):Press the number to read the value in the display area. Only used on virtual screens. */
    @Test
    fun calculatorDisplayIsRead() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            .let { ctx.packageManager.resolveActivity(it, 0)?.activityInfo?.packageName }
        assumeTrue("no calculator app", pkg != null)
        ok(screen.openApp(pkg!!, cold = true))
        var s = snapUntil(8_000) { snap -> snap.elements.any { it.clickable && it.name == "1" } }
        //The calculator will retain the last input,It only requires that after pressing, a display value ending with 123 that did not exist before appears.
        val before = s.elements.map { it.name }.toSet()
        for (digit in listOf("1", "2", "3")) {
            val key = s.elements.first { it.clickable && it.name == digit }
            val r = ok(screen.tapRef(key.ref, s.snapshotId))
            assertEquals("tap $digit", true, r.changed)
            s = screen.snapshot()
        }
        //The display area may have formats such as thousandths and so on.,Just compare numbers
        snapUntil { snap -> snap.elements.any { e -> e.name !in before && e.name.filter(Char::isDigit).endsWith("123") } }
    }
}
