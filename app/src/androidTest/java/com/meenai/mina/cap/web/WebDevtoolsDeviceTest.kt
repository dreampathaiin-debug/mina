package com.meenai.mina.cap.web

import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.core.cap.web.cdp.CdpSession
import com.meenai.mina.core.cap.web.cdp.dispatchMouseEvent
import com.meenai.mina.core.cap.web.cdp.evaluate
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 *Real machine test:DevTools session for web page tags(Direct connection, forced proxy connection, forced shutdown), Correspondence between tags and targets, default desktop version, and change to mobile version by site.
 *The agent connection item must have a built-in assistant running.(Run first BuiltinHelperDeviceTest),If not, skip it.
 */
@RunWith(AndroidJUnit4::class)
class WebDevtoolsDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /**Ben tests his own copy:Changing the session source and connection method will not affect other tests. */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var pool: WebTabPool
    private var phoneWidth = 0

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        devtools.resetProvider()
        devtools.forceRoute = null
        devtools.resetRoute()
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        phoneWidth = bounds.width()
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            pool = WebTabPool(ctx, host, devtools = devtools, assetLoaders = listOf(main))
        }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            host.close()
        }
        devtools.setSiteMode("appassets.androidplatform.net", mobile = false)
    }

    private fun open(task: String = "t", url: String? = fixture("elements.html")): WebTab {
        val tab = runBlocking(Dispatchers.Main) { pool.open(task, null) }
        if (url != null) assertTrue(runBlocking { tab.load(url) })
        return tab
    }

    /**The page you see(Injected via WebView,without experience DevTools). */
    private fun js(tab: WebTab, script: String): String = runBlocking(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun pageFacts(tab: WebTab): JsonObject {
        val raw = js(
            tab,
            "JSON.stringify({ua:navigator.userAgent,mobile:navigator.userAgentData?navigator.userAgentData.mobile:null," +
                "platform:navigator.userAgentData?navigator.userAgentData.platform:null,navPlatform:navigator.platform," +
                "iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,vvs:visualViewport?visualViewport.scale:null})",
        )
        // evaluateJavascriptWrap the string result in another layer JSON
        return Json.parseToJsonElement(Json.parseToJsonElement(raw).jsonPrimitive.content).jsonObject.also { Log.i(TAG, "page facts: $it") }
    }

    private fun session(tab: WebTab): CdpSession? = runBlocking { devtools.awaitSession(tab, 5_000) }

    private fun eval(s: CdpSession, expr: String): String? =
        runBlocking { s.evaluate(expr, timeoutMs = 3_000)["value"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun sessionAttachesAndEvaluates() {
        val tab = open()
        val s = session(tab)
        assertNotNull("no devtools session (route ${devtools.route.value})", s)
        assertEquals(WebDevtools.Route.Direct, devtools.route.value)
        assertTrue(devtools.available.value)
        assertEquals("elements", eval(s!!, "document.title"))
        //Reuse the same conversation with the same label
        assertTrue(s === devtools.session(tab))
        assertNotNull(devtools.targetId(tab))
        //Deleted after running out of tags
        assertEquals("\"undefined\"", js(tab, "typeof window.__minaDevtoolsMark"))
    }

    @Test
    fun sameUrlTabsMapToTheirOwnTargets() {
        val a = open("t")
        val b = open("t")
        js(a, "window.__who='a'")
        js(b, "window.__who='b'")
        val sa = session(a)!!
        val sb = session(b)!!
        assertNotEquals(devtools.targetId(a), devtools.targetId(b))
        assertEquals("a", eval(sa, "window.__who"))
        assertEquals("b", eval(sb, "window.__who"))
        //Corresponds to the same cross-navigation
        assertTrue(runBlocking { a.load(fixture("inputs.html")) })
        assertEquals("inputs", eval(sa, "document.title"))
        assertEquals("elements", eval(sb, "document.title"))
    }

    @Test
    fun desktopByDefault() {
        val tab = open()
        assertNotNull(session(tab))
        val f = pageFacts(tab)
        assertTrue(f["ua"]!!.jsonPrimitive.content, "X11; Linux x86_64" in f["ua"]!!.jsonPrimitive.content)
        assertFalse(f["mobile"]!!.jsonPrimitive.boolean)
        assertEquals("Linux", f["platform"]!!.jsonPrimitive.content)
        assertEquals("Linux x86_64", f["navPlatform"]!!.jsonPrimitive.content)
        assertTrue("innerWidth ${f["iw"]}", abs(f["iw"]!!.jsonPrimitive.double - 1280) <= 2)
        //The height is enlarged according to the host aspect ratio
        val expectH = 1280.0 * host.height / host.width
        assertTrue("innerHeight ${f["ih"]} vs $expectH", abs(f["ih"]!!.jsonPrimitive.double - expectH) <= 4)
    }

    @Test
    fun desktopTabsRenderOnADesktopSizeHost() {
        val phoneW = phoneWidth
        val tab = open()
        val s = session(tab)!!
        //Desktop version:hang on 1280×800 on the host display,1CSS pixels vs. 1 view pixels,Input coordinates are consistent with CSS
        assertEquals(1280, host.width)
        assertEquals(800, host.height)
        val viewW = runBlocking(Dispatchers.Main) { tab.view.width }
        assertEquals(1280, viewW)
        val f = pageFacts(tab)
        assertEquals(1280.0, f["iw"]!!.jsonPrimitive.double, 1.0)
        assertEquals(800.0, f["ih"]!!.jsonPrimitive.double, 2.0)
        Thread.sleep(500)
        val png = host.latestFramePng()!!
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        Log.i(TAG, "desktop host: inputScale=${s.inputScale} frame=${bmp.width}x${bmp.height} dpr=${f["dpr"]}")
        assertEquals(1280, bmp.width)
        assertEquals(800, bmp.height)
        //Change mobile version:Return to the host display as large as the home screen;Switch back to desktop version
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        assertTrue(runBlocking { screen.setSiteMode(true) }!!.mobile)
        assertEquals(phoneW, host.width)
        assertEquals(1.0, s.inputScale, 1e-9)
        assertFalse(runBlocking { screen.setSiteMode(false) }!!.mobile)
        assertEquals(1280, host.width)
        assertEquals(1280.0, pageFacts(tab)["iw"]!!.jsonPrimitive.double, 1.0)
    }

    @Test
    fun tabAttachedOutsideThePoolFollowsItsMode() {
        val phoneW = phoneWidth
        lateinit var tab: WebTab
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            tab = WebTab(ctx, "solo", "solo-task", listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()), devtools = devtools)
            host.attach(tab)
        }
        try {
            assertEquals(1280, host.width)
            devtools.setSiteMode("appassets.androidplatform.net", mobile = true)
            assertTrue(runBlocking { tab.load(fixture("elements.html")) })
            //Switch to mobile version:Without tag pool,The view is also changed to a display as large as the main screen
            assertEquals(phoneW, host.width)
            assertTrue(pageFacts(tab)["iw"]!!.jsonPrimitive.double < 1000)
        } finally {
            instr.runOnMainSync { tab.destroy() }
        }
    }

    @Test
    fun tapsLandInDesktopLayout() {
        val tab = open()
        assertNotNull(session(tab))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        val snap = runBlocking { screen.snapshot() }
        val button = snap.elements.first { it.role == "button" && it.name == "button text" }
        js(tab, "window.__hits=[];document.addEventListener('click',function(e){window.__hits.push((e.target.textContent||'').trim())},true)")
        val r = runBlocking { screen.tapRef(button.ref, snap.snapshotId) }
        assertFalse("tap failed: $r", r is ActionResult.Failed)
        val hits = js(tab, "JSON.stringify(window.__hits)")
        assertTrue("clicked $hits", "button text" in hits)
        //Reading screen coordinates are within the view(full page width 1280CSS pixel shrink to view width)
        assertTrue(snap.elements.all { it.centerX in 0..host.width })
    }

    @Test
    fun siteModeSwitchesToMobileAndBack() {
        val tab = open()
        assertNotNull(session(tab))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        val m = runBlocking { screen.setSiteMode(true) }!!
        assertEquals("appassets.androidplatform.net", m.host)
        assertTrue(m.mobile)
        assertTrue(m.reloaded)
        val mf = pageFacts(tab)
        assertTrue(mf["mobile"]!!.jsonPrimitive.boolean)
        assertFalse("X11" in mf["ua"]!!.jsonPrimitive.content)
        assertTrue("innerWidth ${mf["iw"]}", mf["iw"]!!.jsonPrimitive.double < 1000)
        //Remembered:Open the same website in a new tab and also have a mobile version
        val other = open()
        assertTrue(pageFacts(other)["mobile"]!!.jsonPrimitive.boolean)
        val d = runBlocking { screen.setSiteMode(false) }!!
        assertFalse(d.mobile)
        assertFalse(pageFacts(other)["mobile"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun relayWhenForced() {
        assumeTrue("phone helper is not running", PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub.shell.value != null)
        devtools.forceRoute = WebDevtools.Route.Relay
        devtools.resetRoute()
        val tab = open()
        val s = session(tab)
        if (s == null) {
            //System policy may not allow the shell to connect to the App's socket(This is the actual measurement in SELinux enforcement mode):To downgrade cleanly
            assertEquals(WebDevtools.Route.None, devtools.route.value)
            assertNull(devtools.session(tab))
            assumeTrue("the system refused the helper's connection to the devtools socket (see avc in logcat)", false)
        }
        assertEquals(WebDevtools.Route.Relay, devtools.route.value)
        Log.i(TAG, "relay session attached via the phone helper")
        assertEquals("elements", eval(s!!, "document.title"))
        assertFalse(pageFacts(tab)["mobile"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun degradesWhenForcedOff() {
        devtools.forceRoute = WebDevtools.Route.None
        devtools.resetRoute()
        val tab = open()
        assertNull(session(tab))
        assertNull(devtools.session(tab))
        assertFalse(devtools.available.value)
        assertEquals(WebDevtools.Route.None, devtools.route.value)
        //retreat:Desktop UA in WebView settings is still there,Page as usual
        val f = pageFacts(tab)
        assertTrue("X11" in f["ua"]!!.jsonPrimitive.content)
        assertEquals("\"elements\"", js(tab, "document.title"))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        assertTrue(runBlocking { screen.snapshot() }.elements.isNotEmpty())
    }

    @Test
    fun closedTabDropsItsSession() {
        val tab = open()
        val s = session(tab)!!
        runBlocking(Dispatchers.Main) { pool.close(tab.id) }
        assertTrue(runBlocking { withTimeoutOrNull(3_000) { s.closed.first { it } } } == true)
        assertNull(devtools.session(tab))
    }

    @Test
    fun cdpMouseUsesCssPixelsUnderDesktopEmulation() {
        val tab = open()
        val s = session(tab)!!
        js(tab, "window.__p=[];document.addEventListener('pointerdown',function(e){window.__p.push(Math.round(e.clientX)+','+Math.round(e.clientY))},true)")
        runBlocking {
            s.dispatchMouseEvent("mousePressed", 640.0, 900.0, "left", 1)
            s.dispatchMouseEvent("mouseReleased", 640.0, 900.0, "left", 1)
        }
        Thread.sleep(200)
        assertEquals("\"[\\\"640,900\\\"]\"", js(tab, "JSON.stringify(window.__p)"))
    }

    private companion object {
        const val TAG = "WebDevtoolsTest"
    }
}
