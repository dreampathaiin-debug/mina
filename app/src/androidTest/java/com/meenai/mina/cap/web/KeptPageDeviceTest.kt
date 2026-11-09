package com.meenai.mina.cap.web

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 *Real machine test:After the task is completed, a page is left and continues to run. A set of web pages that are not tied to tasks([WebStack],Build the same as in the mission),Open the local timing page,
 *use web_keep_openUnder that road sign it,Then "end the task"([WebStack.close]):That page is not closed with the task,Hand it over to the process [PageKeeper],
 *The timer and picture frame in the page continue to increase;The unmarked page will be closed as usual.;Leave a new page and replace the old one;last sutra [PageKeeper]Turn off.
 *seconds available `-e keptRunSec N`tune(default 5).
 */
@RunWith(AndroidJUnit4::class)
class KeptPageDeviceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val keeper = WebOwner.of(ctx).pageKeeper
    private val stacks = mutableListOf<WebStack>()
    private val seconds = InstrumentationRegistry.getArguments().getString("keptRunSec")?.toLongOrNull()?.coerceIn(1, 120) ?: 5L

    @Before
    fun setUp() {
        instr.runOnMainSync { keeper.close(KeptPageClose.User) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            stacks.forEach { runCatching { it.close() } }
            keeper.close(KeptPageClose.User)
        }
    }

    private fun stack(task: String): WebStack {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        lateinit var s: WebStack
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            s = WebStack.create(ctx, task, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi, listOf(loader))
            s.keepOrigin = { KeptOrigin("conv-$task", "run-$task", "Open timing page,Don't close it") }
        }
        stacks += s
        return s
    }

    private fun open(s: WebStack) = runBlocking { s.screen.navigate(TICKER, newTab = false) }.also { assertTrue("ticker loaded", it.loaded) }

    /**The count at the moment in the page(timer,picture frame). */
    private fun counts(tab: WebTab): Pair<Long, Long> {
        val latch = CountDownLatch(1)
        var raw = ""
        instr.runOnMainSync { tab.view.evaluateJavascript("window.ticks + ',' + window.frames") { raw = it; latch.countDown() } }
        assertTrue("page answered", latch.await(5, TimeUnit.SECONDS))
        val (a, b) = raw.trim('"').split(',')
        return a.toLong() to b.toLong()
    }

    /**Mission ended(Just like TaskRun ending, it is closed on the main thread.). */
    private fun endTask(s: WebStack) {
        instr.runOnMainSync { s.close() }
        stacks -= s
    }

    @Test
    fun keptPageKeepsRunningAfterTheTaskEnds() {
        val s = stack("keep-1")
        open(s)
        val tab = s.pool.current("keep-1")!!
        val r = runBlocking { s.screen.keepOpen(true, "test:Then run") }!!
        assertTrue(r.keep)
        assertEquals("appassets.androidplatform.net", r.host)
        assertNull("nothing kept before", r.replaces)
        endTask(s)

        //The task pool is closed,This page is not closed:Handed over PageKeeper,It's still hanging on its display
        assertFalse("kept tab not destroyed at task end", tab.destroyed)
        assertTrue(tab === keeper.keptTab())
        val page = keeper.page.value!!
        assertEquals("conv-keep-1", page.conversationId)
        assertEquals("run-keep-1", page.runId)
        assertEquals("appassets.androidplatform.net" to null, keeper.recordFor("run-keep-1"))
        assertNotNull("still on its display", tab.view.parent)

        //The page continues to run:The timer and picture frames are both increasing.
        val before = counts(tab)
        Thread.sleep(seconds * 1_000)
        val after = counts(tab)
        assertTrue("timer advanced ${before.first} -> ${after.first}", after.first - before.first >= seconds * 5)
        assertTrue("frames advanced ${before.second} -> ${after.second}", after.second > before.second)
        //Picture-in-picture pictures from the same source are also available
        assertNotNull("live frame", runBlocking { keeper.frames().let { f -> kotlinx.coroutines.withTimeoutOrNull(5_000) { f.first { it != null } } } })

        //through PageKeeperturn off:Tag destruction,write down the reason
        instr.runOnMainSync { assertTrue(keeper.close(KeptPageClose.User)) }
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
        assertEquals("appassets.androidplatform.net" to "user", keeper.recordFor("run-keep-1"))
    }

    @Test
    fun unmarkedPagesCloseWithTheTask() {
        val s = stack("plain")
        open(s)
        val tab = s.pool.current("plain")!!
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun takenBackMarkClosesWithTheTask() {
        val s = stack("back")
        open(s)
        val tab = s.pool.current("back")!!
        runBlocking { s.screen.keepOpen(true, "x") }
        assertFalse(runBlocking { s.screen.keepOpen(false, "No") }!!.keep)
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun keepingAnotherPageClosesTheOldOne() {
        val a = stack("first")
        open(a)
        val first = a.pool.current("first")!!
        runBlocking { a.screen.keepOpen(true, "x") }
        endTask(a)
        val b = stack("second")
        open(b)
        //One page has been saved:The result says clearly that it will be rejected.
        assertEquals("appassets.androidplatform.net", runBlocking { b.screen.keepOpen(true, "y") }!!.replaces)
        endTask(b)
        assertTrue("old kept page closed", first.destroyed)
        assertEquals("conv-second", keeper.page.value?.conversationId)
        assertEquals("appassets.androidplatform.net" to "replaced", keeper.recordFor("run-first"))
    }

    private companion object {
        const val TICKER = "https://appassets.androidplatform.net/assets/web-fixtures/ticker.html"
    }
}
