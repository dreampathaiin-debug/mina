package com.meenai.mina.cap.phone.shell

import com.meenai.mina.cap.phone.shell.ShellClip.Now
import com.meenai.mina.cap.phone.shell.ShellClip.Read
import com.meenai.mina.cap.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

/**Paste input to the clipboard and then restore:If it cannot be read, it will never be regarded as empty.,The clipboard won't move if it's not Mina's. */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        //What is obtained is empty,But the system says there is content:Can't read,not empty
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        //The user copied something else during the process or cannot read it at the moment.:Not moving
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        //Mina's content was cleared by someone else(For example, the system is cleared regularly):If there is original content, put it back,It turns out it's empty, so don't worry about it.
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}
