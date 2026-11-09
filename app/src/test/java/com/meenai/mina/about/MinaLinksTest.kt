package com.meenai.mina.about

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class MinaLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = MinaLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = MinaLinks.issueBody(device).lines()
        assertEquals("-model:Google Pixel 8", lines[0])
        assertEquals("-Android version: 14 (SDK 34)", lines[1])
        assertEquals("-Mina version: 0.1.0 (build 65fcf92)", lines[2])
        assertTrue(lines.contains("###What problems did you encounter?"))
        assertTrue(lines.contains("###What steps did you take?"))
        assertTrue(lines.contains("###What to expect"))
        //Diagnostic information is not automatically brought:Only prompt the user where to copy
        assertTrue(lines.contains("###diagnostic information"))
        assertTrue(lines.contains("(Optional) In Mina's About ›Feedback question ›Copy diagnostic information" Copy and paste here"))
    }

    /**If the manufacturer's name is already included in the model number, do not write it again. */
    @Test
    fun modelNotDoubled() {
        val body = MinaLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("-model:SAMSUNG SM-S9180\n"))
    }

    @Test
    fun issueUrlIsEncoded() {
        val url = MinaLinks.issueUrl(device)
        assertTrue(url.startsWith("https://github.com/wangzan101/Mina/issues/new?title=&body="))
        val query = url.substringAfter('?')
        assertFalse("no raw spaces or newlines", query.any { it == ' ' || it == '\n' || it == '+' })
        assertFalse("no raw non-ASCII", query.any { it.code > 127 })
        //Can be parsed as a legitimate URI,Decode the text back to its original form
        URI(url)
        val body = URLDecoder.decode(query.substringAfter("body="), "UTF-8")
        assertEquals(MinaLinks.issueBody(device), body)
    }
}
