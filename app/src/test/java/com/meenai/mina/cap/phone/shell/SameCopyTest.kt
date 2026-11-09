package com.meenai.mina.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**Home screen occupancy, Mina other screen occupancy, cold start viewing tasks:For an app with two copies installed, click "Package Name" +Users" are counted separately,Only one copy remains as usual. */
class SameCopyTest {
    private val chat = "com.example.chat"

    @Test fun theUserOnTheMainCopyDoesNotBlockTheCloneAndViceVersa() {
        //The home screen is for user 0
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf(chat), 0, chat, 128))
        //The home screen is the clone
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, 128))
        assertFalse(ShellService.sameCopy(listOf(chat), 128, chat, 0))
    }

    @Test fun unknownTaskUserCountsAsTheSameCopy() {
        //Cannot read which user the task belongs to:Rather treat it as occupation
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 128))
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 0))
    }

    @Test fun singleCopyAndPackageOnlyChecksAreUnchanged() {
        //Only packed one copy:The tasks are all on the user 0,Calculated by package name as before
        assertTrue(ShellService.sameCopy(listOf("x.launcher", chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf("x.other"), 0, chat, 0))
        //No matter which portion(share):Any portion counts
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, null))
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, null))
    }
}
