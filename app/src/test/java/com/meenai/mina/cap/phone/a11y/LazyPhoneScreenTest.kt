package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**No built-in helper/When accessible:Steps as usual without using a mobile phone,Use your mobile phone to report phone_unavailable(How to get it done),You can try again later. */
class LazyPhoneScreenTest {
    private var attempts = 0
    private var available = false
    private val opened = mutableListOf<String>()

    private val real: ScreenPort = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, args ->
        if (m.name == "openApp") { opened += args[0] as String; ActionResult.Ok() } else throw UnsupportedOperationException(m.name)
    } as ScreenPort

    private val screen = LazyPhoneScreen(1080, 2400, listApps = { listOf("a.b" to "A") }) {
        attempts++
        if (!available) throw PhoneUnavailable("Mobile operation assistant is not running")
        real
    }

    @Test fun blockedAppDoesNotConnectHelper() = runTest {
        val lazy = LazyPhoneScreen(1080, 2400, listApps = { emptyList() }) { attempts++; real }
        val guarded = com.meenai.mina.core.uistate.AppAccessScreenPort(lazy,
            com.meenai.mina.core.access.AppAccessPolicy {
                com.meenai.mina.core.access.AppAccessState(blocked = setOf("bank"))
            }, packages = { emptyList() })
        try {
            guarded.openApp("bank")
            org.junit.Assert.fail("blocked app must be refused")
        } catch (e: com.meenai.mina.core.access.AppAccessDenied) {
            assertEquals("app_blocked", e.code)
        }
        assertEquals(0, attempts)
    }

    @Test fun readsAndAppListWorkWithoutConnecting() = runTest {
        assertFalse(screen.snapshot().hasWindow)
        assertEquals(listOf("a.b" to "A"), screen.listApps())
        assertEquals(ActionResult.Failed("no_app_open"), screen.tapXY(1, 1))
        assertEquals(0, attempts)
        assertFalse(screen.acquired)
    }

    @Test fun launchingAnAppConnectsOrSaysWhy() = runTest {
        assertEquals(ActionResult.Failed(LazyPhoneScreen.PHONE_UNAVAILABLE, "Mobile operation assistant is not running"), screen.openApp("a.b"))
        assertFalse(screen.acquired)
        available = true
        assertTrue(screen.openApp("a.b") is ActionResult.Ok)
        assertTrue(screen.acquired)
        assertEquals(listOf("a.b"), opened)
        assertEquals(2, attempts)
    }
}
