package com.meenai.mina.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.cap.web.SiteEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**Settings page "Website login":List, single station exit(With "maybe still" prompt), All exits require a second confirmation. */
@RunWith(AndroidJUnit4::class)
class WebSitesSectionTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(open: ((String) -> Unit)? = { calls += "open $it" }) {
        compose.setContent {
            Column {
                WebSitesSection(
                    WebSitesState(
                        sites = listOf(SiteEntry("shop.example.com", 1_000, true), SiteEntry("news.example.org", 2_000, false)),
                        onOpenLogin = open,
                        onLogout = { calls += "logout $it"; 2 },
                        onLogoutAll = { calls += "all" },
                    ),
                )
            }
        }
    }

    @Test
    fun listsSitesAndDataNote() {
        show()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("news.example.org").assertIsDisplayed()
        compose.onNodeWithText(WEB_DATA_NOTE).assertIsDisplayed()
    }

    @Test
    fun logoutOneSiteWarnsItMayPersist() {
        show()
        compose.awaitOne("shop.example.com").performClick()
        compose.clickInDialog("Exit this website")
        assertEquals(listOf("logout shop.example.com"), calls)
        compose.onNodeWithText("Exited shop.example.com.$WEB_LOGOUT_PARTIAL").assertIsDisplayed()
    }

    @Test
    fun openLoginFromTheSiteDialog() {
        show()
        compose.awaitOne("news.example.org").performClick()
        compose.clickInDialog("Open login")
        assertEquals(listOf("open news.example.org"), calls)
    }

    @Test
    fun logoutAllNeedsConfirmation() {
        show()
        compose.awaitOne("Exit all and clear web data").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.clickInDialog("Exit all")
        assertEquals(listOf("all"), calls)
    }
}
