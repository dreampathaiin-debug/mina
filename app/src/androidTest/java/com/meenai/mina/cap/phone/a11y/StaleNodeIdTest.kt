package com.meenai.mina.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**Real machine test:The number is only valid in the snapshot it belongs to.,After the page is changed, the old number cannot be found. ref_expired. */
@RunWith(AndroidJUnit4::class)
class StaleNodeIdTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun oldIdAfterNavigationFails() = runBlocking<Unit> {
        val screen = agent.screen
        assertTrue(screen.openApp(SETTINGS) is ActionResult.Ok)
        val home = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.clickable && it.name.startsWith("Bluetooth") } }
        }!!
        val bluetooth = home.elements.first { it.clickable && it.name.startsWith("Bluetooth") }
        val wlan = home.elements.first { it.clickable && it.name.startsWith("WLAN") }

        val went = screen.tapRef(wlan.ref, home.snapshotId)
        assertTrue("$went", went is ActionResult.Ok && went.changed == true)
        val page = screen.snapshot()

        //"Bluetooth" on the home page has no corresponding element in the WLAN page
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef(bluetooth.ref, home.snapshotId))
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef("@e1", "no-such-snapshot"))
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef("@e${page.elements.size + 1}", page.snapshotId))
    }
}
