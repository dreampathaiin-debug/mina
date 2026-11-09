package com.meenai.mina.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *:When the screen is on, it will report user operations on time.,Stop when the screen is released;If you can’t apply, then you won’t even try again.;
 *Only the interface for press-on-screen reporting is recognized,If you don’t recognize it, it will be reported to the default group.(User home screen)old interface.
 */
class DisplayAwakeTest {
    private val ownGroup = 1 shl 11

    /**Note down the registered periodic tasks,Manually "get to the point" by testing. */
    private class Timer {
        val tasks = mutableListOf<Runnable>()
        val cancelled = mutableSetOf<Runnable>()
        fun repeat(r: Runnable): () -> Unit { tasks += r; return { cancelled += r } }
        fun tick() = tasks.toList().forEach { if (it !in cancelled) it.run() }
        val live get() = tasks.count { it !in cancelled }
    }

    @Test fun pokesEachDisplayUntilItIsReleased() {
        val timer = Timer()
        val poked = mutableListOf<Int>()
        val awake = DisplayAwake({ poked += it; true }, timer::repeat)
        awake.start(5)
        awake.start(7)
        timer.tick()
        timer.tick()
        assertEquals(listOf(5, 7, 5, 7), poked)
        awake.stop(5)
        poked.clear()
        timer.tick()
        assertEquals(listOf(7), poked)
        awake.stopAll()
        assertEquals(0, timer.live)
    }

    @Test fun startingTheSameDisplayAgainKeepsOnlyOneTimer() {
        val timer = Timer()
        val awake = DisplayAwake({ true }, timer::repeat)
        awake.start(5)
        awake.start(5)
        assertEquals(1, timer.live)
        awake.stop(5)
        assertEquals(0, timer.live)
    }

    @Test fun givesUpEverywhereOnceThePowerCallIsUnavailable() {
        val timer = Timer()
        var calls = 0
        val awake = DisplayAwake({ calls++; false }, timer::repeat)
        awake.start(5)
        awake.start(7)
        timer.tick()
        assertEquals(0, timer.live)
        //Screens built later will no longer be registered.
        awake.start(9)
        assertEquals(0, timer.live)
        assertEquals(1, calls)
    }

    @Test fun onlyDisplaysInTheirOwnGroupAreKeptAwake() {
        assertTrue(DisplayAwake.needed(ownGroup or 1, ownGroup, groupId = 3))
        //Cannot read the group I am in:Calculate according to the mark when building the screen
        assertTrue(DisplayAwake.needed(ownGroup, ownGroup, groupId = null))
        //No own group(Return to untrustworthy screen)or actually fall into the default group:Reporting will make the user's home screen immortal.
        assertFalse(DisplayAwake.needed(1, ownGroup, groupId = null))
        assertFalse(DisplayAwake.needed(ownGroup, ownGroup, groupId = 0))
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class OldPower { fun userActivity(time: Long, event: Int, flags: Int) {} }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class NewPower {
        fun userActivity(time: Long, event: Int, flags: Int) {}
        fun userActivity(displayId: Int, time: Long, event: Int, flags: Int) {}
    }

    @Test fun picksOnlyThePerDisplayUserActivity() {
        assertNull(DisplayAwake.userActivityMethod(OldPower::class.java))
        val m = DisplayAwake.userActivityMethod(NewPower::class.java)
        assertNotNull(m)
        m!!
        assertEquals(4, m.parameterCount)
        assertEquals(Int::class.javaPrimitiveType, m.parameterTypes[0])
    }
}
