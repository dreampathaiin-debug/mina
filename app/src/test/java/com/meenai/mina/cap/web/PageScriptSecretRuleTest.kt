package com.meenai.mina.cap.web

import com.meenai.mina.core.cap.web.PageScriptProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** web_script"Unreadable value input box" and screen reading script page.js is the same rule:both sides autocompleteLists need to be consistent. */
class PageScriptSecretRuleTest {
    private val pageJs = File("src/main/assets/web/page.js").readText()

    private fun tokens(name: String): Set<String> {
        val body = Regex("var $name = \\{([^}]*)\\}").find(pageJs)!!.groupValues[1]
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun autocompleteListMatchesPageScript() {
        assertEquals(tokens("PASSWORD_AUTOCOMPLETE") + tokens("SECRET_AUTOCOMPLETE"), PageScriptProtocol.SECRET_AUTOCOMPLETE.toSet())
    }
}
