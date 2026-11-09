package com.meenai.mina.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsNamingTest {
    @Test fun stripsSeparatorsKeepsChinese() {
        assertEquals("Report (1).csv", Attachments.safeName("Report (1).csv"))
        assertEquals("etcpasswd", Attachments.safeName("../etc/passwd"))
        assertEquals("ab.txt", Attachments.safeName("a\\b.txt"))
        assertEquals("ab.txt", Attachments.safeName("a\u0000\u0007\nb.txt"))
        //Overwriting direction and zero-width characters:Doesn’t the displayed name match the actual one?
        assertEquals("invoicefdp.exe", Attachments.safeName("invoice‮fdp.exe"))
        assertEquals("ab", Attachments.safeName("a​ b"))
        assertEquals("attachment", Attachments.safeName("/../\u0001"))
        assertEquals("hidden", Attachments.safeName(".hidden"))
        //Maximum 120 words,and UTF-8 No more than 240 bytes(Leave room for adding serial numbers to the system),Extension reserved
        val long = Attachments.safeName("long".repeat(300) + ".txt")
        assertTrue(long.toByteArray().size <= 240)
        assertTrue(long.endsWith(".txt"))
        assertEquals(120, Attachments.safeName("a".repeat(300)).length)
        val emoji = Attachments.safeName("😀".repeat(200))
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test fun numberedKeepsExtension() {
        assertEquals("a.csv", Attachments.numbered("a.csv", 1))
        assertEquals("a (2).csv", Attachments.numbered("a.csv", 2))
        assertEquals("README (3)", Attachments.numbered("README", 3))
        assertTrue(Attachments.numbered("x.tar.gz", 2).endsWith(".gz"))
    }

    @Test fun shareQuotaPerTask() {
        val q = ShareQuota(maxFiles = 2, maxBytes = 100)
        q.reserve(10); q.reserve(20)
        val third = runCatching { q.reserve(1) }.exceptionOrNull() as com.meenai.mina.core.workspace.SandboxError
        assertEquals("share_limit", third.code)
        //Failed export does not count against the quota
        q.release(20)
        q.reserve(60)
        //Settled based on actual copied bytes:less than reserved,What you save can still be used
        q.settle(60, 10)
        val bytes = ShareQuota(maxBytes = 100)
        bytes.reserve(90); bytes.settle(90, 50)
        bytes.reserve(50)
        assertEquals("share_limit", (runCatching { bytes.reserve(1) }.exceptionOrNull() as com.meenai.mina.core.workspace.SandboxError).code)
    }
}
