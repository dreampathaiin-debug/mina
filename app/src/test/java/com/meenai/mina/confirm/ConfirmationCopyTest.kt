package com.meenai.mina.confirm

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCopyTest {
    @get:Rule val texts = EnglishTexts()

    private fun req(content: String = "See you tomorrow", amount: String? = null, action: IrrevAction = IrrevAction.send, target: String = "Zhang San") =
        ConfirmationRequest(action, target, content, amount, "com.chat")

    @Test fun longContentEllipsized() {
        val long = "Character".repeat(80)
        val body = ConfirmationCopy.body(req(content = long), "chat")
        assertTrue(body, body.endsWith("Content: " + "Character".repeat(60).take(60) + "…"))
        //The fields written by the model are treated as plain text.:Newline pressed into spaces,Control characters and direction control characters are removed,Remove leading and trailing blanks
        val odd = ConfirmationCopy.body(req(content = "first line\nSecond line‮reverse<b>thick</b>\u0007 ", target = "Zhang San\n"), "chat")
        assertEquals("In chat · To: Zhang San · Content: first line Second linereverse<b>thick</b>", odd)
        assertFalse(odd.contains('\n'))
    }

    //repair 1 M1:Delimiters, colons, line separators, and zero-width characters in fields cannot create fake fields.
    @Test fun fieldsCannotFakeStructure() {
        val r = req(content = "See you tomorrow · Amount:¥0\u2028 second paragraph\u200B tail", target = "Zhang San: Li Si")
        assertEquals("In chat · To: Zhang San∶ Li Si · Content: See you tomorrow / Amount∶¥0 second paragraph tail", ConfirmationCopy.body(r, "chat"))
    }

    //repair 1 I3:Use separate buttons for each declaration PendingIntent(data is different),The buttons for old notifications will not be replaced by those for new notifications.
    @Test fun intentDataUniquePerRequest() {
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(2, true))
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(1, false))
    }
}

