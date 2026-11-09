package com.meenai.mina.cap.web

import com.meenai.mina.core.cap.web.KeptPageClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**The pure logic of leaving a page:Keep, keep the new one and replace the old one, automatically shut down after 2 hours(fake clock), the reasons for various closing methods, and when closing, write down one sentence in the source dialogue round. */
class KeptPageSlotTest {
    private var now = 1_000L
    private val released = mutableListOf<String>()
    private val notes = mutableListOf<Triple<String, String?, KeptPageClose>>()
    private val slot = KeptPageSlot<String>({ now }, release = { released += it }, onClosed = { p, r -> notes += Triple(p.conversationId, p.runId, r) })

    private fun keep(handle: String, conversation: String = "c1", run: String = "r1") =
        slot.keep(handle, KeptOrigin(conversation, run, "Open this course,Don't close it"), "course.example.com", "https://course.example.com/3", "Section 3")

    @Test fun keepsOnePageForTwoHours() {
        val p = keep("tab1")
        assertEquals(p, slot.page.value)
        assertEquals(now + 2 * KeptPageSlot.HOUR_MS, p.closesAt)
        assertEquals("tab1", slot.handle())
        assertEquals(2 * KeptPageSlot.HOUR_MS, slot.remainingMs())
        //It doesn’t matter if it’s not there yet
        now += 2 * KeptPageSlot.HOUR_MS - 1
        assertFalse(slot.expireIfDue())
        assertEquals(1L, slot.remainingMs())
        //Automatically shut down when the time comes,Let go of things in your hands,Write down the reason on the source round
        now += 1
        assertTrue(slot.expireIfDue())
        assertNull(slot.page.value)
        assertNull(slot.handle())
        assertEquals(listOf("tab1"), released)
        assertEquals(listOf(Triple("c1", "r1", KeptPageClose.Expired)), notes)
        assertFalse(slot.expireIfDue())
    }

    @Test fun keepingAnotherPageClosesTheOldOne() {
        val first = keep("tab1", "c1", "r1")
        val second = keep("tab2", "c2", "r2")
        assertTrue(second.id != first.id)
        assertEquals(listOf("tab1"), released)
        assertEquals(listOf(Triple("c1", "r1", KeptPageClose.Replaced)), notes)
        assertEquals("c2", slot.page.value?.conversationId)
        assertEquals("tab2", slot.handle())
    }

    @Test fun closeReasonsAreReportedOnce() {
        for (reason in listOf(KeptPageClose.User, KeptPageClose.Memory, KeptPageClose.Crashed, KeptPageClose.AppExit)) {
            keep("tab-$reason")
            assertTrue(slot.close(reason))
            assertFalse(slot.close(reason))
        }
        assertEquals(listOf(KeptPageClose.User, KeptPageClose.Memory, KeptPageClose.Crashed, KeptPageClose.AppExit), notes.map { it.third })
        assertEquals(4, released.size)
    }

    @Test fun lateCallbackDoesNotCloseALaterPage() {
        val old = keep("tab1")
        keep("tab2")
        //The old page crash callback arrives late:Don't close the page left behind
        assertFalse(slot.closeIf(old.id, KeptPageClose.Crashed))
        assertEquals("tab2", slot.handle())
    }

    @Test fun recordForTheTurnEvenAfterItClosed() {
        assertNull(slot.recordFor("r1"))
        keep("tab1", run = "r1")
        assertEquals("course.example.com" to null, slot.recordFor("r1"))
        assertNull(slot.recordFor("r2"))
        assertNull(slot.recordFor(null))
        //That round crashed before it was written into the dialogue page.:I still remember to keep it, why did I close it?
        slot.close(KeptPageClose.Crashed)
        assertEquals("course.example.com" to "crashed", slot.recordFor("r1"))
    }
}
