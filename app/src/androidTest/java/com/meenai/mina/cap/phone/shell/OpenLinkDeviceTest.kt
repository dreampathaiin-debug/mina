package com.meenai.mina.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.SystemPickers
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.tool.LinkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**Real machine:All settings pages in the whitelist can be opened on the Agent's screen.,Home screen is not affected;Links that no app can open return 5. */
@RunWith(AndroidJUnit4::class)
class OpenLinkDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun everySettingsActionOpensOnAgentDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val settings = SystemPickers.settingsPackage(InstrumentationRegistry.getInstrumentation().targetContext.packageManager)
        assertNotNull("no settings app on this device", settings)
        val main = DeviceSupport.mainScreenState(shell)
        val failed = mutableListOf<String>()
        for (action in LinkPolicy.SETTINGS_ACTIONS.sorted()) {
            val uri = if (action == LinkPolicy.APP_DETAILS) "package:${DeviceSupport.TEST_PACKAGE}" else null
            val r = agent.screen.openLink(action, uri, null)
            //Some systems have removed individual original settings pages:Failure to parse is considered a downgrade,Not considered a failure;Once turned on, it must be placed in the system settings.
            if (r is ActionResult.Failed && r.reason == "link_unresolved") continue
            val top = agent.screen.snapshot().packageName
            if (r !is ActionResult.Ok || top != settings) failed += "$action -> $r on $top"
            assertEquals("main screen changed after $action", main, DeviceSupport.mainScreenState(shell))
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test
    fun unresolvableUriReturns5() {
        val shell = DeviceSupport.shell()
        val id = agent.display.displayId
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "mina-no-such-scheme://page/1", null))
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "https://example.com/", "com.meenai.mina.no.such.app"))
        //Check again on the shell side:
        assertEquals(ShellService.START_INVALID_LINK, shell.startView(id, null, "tel:10086", null))
        val r = runBlocking { agent.screen.openLink(null, "mina-no-such-scheme://page/1", null) }
        assertEquals(ActionResult.Failed("link_unresolved"), r)
    }
}
