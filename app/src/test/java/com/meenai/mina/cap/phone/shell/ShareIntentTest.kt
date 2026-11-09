package com.meenai.mina.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentTest {
    private val text = "See you tomorrow at 7 o'clock; \"good\" && echo hi"

    @Test fun singleShareScreenIsNamedDirectly() {
        assertEquals(
            listOf("am", "start", "--display", "7", "-a", "android.intent.action.SEND", "-t", "text/plain",
                "--es", "android.intent.extra.TEXT", text, "-n", "com.example.chat/.ShareActivity"),
            ShareIntent.amArgs(7, "com.example.chat", text, "com.example.chat/.ShareActivity"),
        )
    }

    @Test fun severalShareScreensOnlyLimitThePackage() {
        val args = ShareIntent.amArgs(7, "com.example.chat", text, null)
        assertEquals(listOf("-p", "com.example.chat"), args.takeLast(2))
        //The text as it is is used as a parameter,Without shell disassembly
        assertEquals(text, args[args.indexOf("android.intent.extra.TEXT") + 1])
    }
}
