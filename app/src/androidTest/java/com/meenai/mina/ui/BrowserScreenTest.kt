package com.meenai.mina.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.browser.AddressInput
import com.meenai.mina.browser.BrowserPage
import com.meenai.mina.browser.FavoriteSite
import com.meenai.mina.browser.RecentPage
import com.meenai.mina.browser.SiteLook
import com.meenai.mina.browser.SiteLooks
import com.meenai.mina.browser.StartPageModel
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**;(),The top bar and toolbar of the web page, the "More" panel, and the "Login for Mina" banner. */
@RunWith(AndroidJUnit4::class)
class BrowserScreenTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val looks = mapOf("shop.example.com" to SiteLook("Shopping", 3, null), "example.org" to SiteLook("news", 3, null))
    private val face = { host: String -> SiteLooks.faceOf(host, looks) { null } }
    private val sites = listOf(FavoriteSite("shop.example.com", true), FavoriteSite("news.example.org", false))
    private val recent = StartPageModel.recentRows(
        listOf(
            RecentPage(AddressInput.search(""), "", 3_000),
            RecentPage("https://docs.example.net/guide", "User Guide", 2_000),
        ),
    ) { face(it).name }

    private fun start() {
        compose.setContent {
            MinaTheme {
                BrowserStart(
                    sites, recent, face,
                    onBack = { calls += "back" },
                    onSubmit = { calls += "submit $it" },
                    onOpenUrl = { calls += "open $it" },
                    onOpenSite = { calls += "site ${it.host}" },
                    onRemoveSite = { calls += "remove ${it.host}" },
                    onAddHint = { calls += "add" },
                    onClearRecent = { calls += "clear" },
                    now = 3_000,
                )
            }
        }
    }

    @Test
    fun drawerHasBrowserEntryThatOpensIt() {
        compose.setContent {
            MinaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {},
                    onBrowser = { calls += "browser" },
                    onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("").assertIsDisplayed().performClick()
        assertEquals(listOf("browser"), calls)
    }

    @Test
    fun startPageShowsSearchSitesAndRecent() {
        start()
        compose.onNodeWithText(BROWSER_SEARCH_HINT).assertIsDisplayed()
        compose.onNodeWithText(BROWSER_ENGINE_NOTE).assertIsDisplayed()
        compose.onNodeWithText("Frequently used websites").assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_LEGEND).assertIsDisplayed()
        compose.onAllNodesWithTag("browser-site-logged-in", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("Recently opened").assertIsDisplayed()
        compose.onNodeWithText("Search: Where to go on the weekend").assertIsDisplayed()
        compose.onNodeWithText("User Guide").performClick()
        compose.onNodeWithText("").performClick()
        compose.onNodeWithText("Add to").performClick()
        assertEquals(listOf("open https://docs.example.net/guide", "clear", "add"), calls)
    }

    @Test
    fun typingShowsSuggestions() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("weekend")
        compose.onNodeWithTag("browser-suggestions").assertIsDisplayed()
        compose.onNodeWithText("Search "weekend"").assertIsDisplayed()
        compose.onNodeWithText("Search: Where to go on the weekend").performClick()
        assertEquals(listOf(""), calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardGoAndEnterSubmitTheSearch() {
        start()
        compose.onNodeWithTag("browser-search").performTextInput("weather")
        compose.onNodeWithTag("browser-search").performImeAction()
        compose.onNodeWithTag("browser-search").performTextInput("")
        compose.onNodeWithTag("browser-search").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("submit weather", ""), calls)
    }

    @Test
    fun editModeRemovesAndFinishes() {
        start()
        compose.onNodeWithText("").performClick()
        compose.onNodeWithText("Organize frequently used websites").assertIsDisplayed()
        compose.onNodeWithContentDescription("", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Finish").performClick()
        compose.onNodeWithText("").assertIsDisplayed()
        assertEquals("remove shop.example.com", calls.first())
    }

    @Test
    fun longPressEntersEditMode() {
        start()
        compose.onAllNodesWithTag("browser-sites")[0].performTouchInput { longClick(center.copy(x = width / 8f, y = 40f)) }
        compose.onNodeWithText("Organize frequently used websites").assertIsDisplayed()
    }

    @Test
    fun pageBarAndToolbar() {
        val page = BrowserPage("https://shop.example.com/", "Shopping", 40, loading = true, canBack = false, canForward = false, desktop = true)
        compose.setContent {
            MinaTheme {
                Column {
                    BrowserPageBar(page, face("shop.example.com"), onClose = { calls += "close" }, onSubmit = {}, onReload = {}, onStop = { calls += "stop" })
                    BrowserToolbar(page, onBackPage = {}, onForward = {}, onHome = { calls += "home" }, onShare = { calls += "share" }, onMore = { calls += "more" })
                }
            }
        }
        compose.onNodeWithText(BROWSER_DESKTOP_TAG).assertIsDisplayed()
        compose.onNodeWithTag("browser-progress").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
        compose.onNodeWithContentDescription("stop").performClick()
        compose.onNodeWithContentDescription("").performClick()
        compose.onNodeWithContentDescription("share").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithTag("browser-address-pill").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals(listOf("stop", "home", "share", "more"), calls)
    }

    @Test
    fun moreSheetOffersSiteActions() {
        compose.setContent {
            MinaTheme {
                BrowserMoreSheet(
                    face("shop.example.com"), "shop.example.com", favorite = false, desktop = false,
                    onDismiss = { calls += "dismiss" }, onReload = {}, onCopy = { calls += "copy" }, onOpenOutside = {},
                    onToggleFavorite = { calls += "fav" }, onToggleDesktop = { calls += "desktop" },
                )
            }
        }
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        compose.onNodeWithText("Open in other browsers").assertIsDisplayed()
        compose.onNodeWithText("Join frequently used websites").assertIsDisplayed()
        compose.onNodeWithText("I will also use the computer version of this website next time").assertIsDisplayed()
        compose.onNodeWithText("Switch to PC version").performClick()
        assertEquals(listOf("dismiss", "desktop"), calls)
    }

    @Test
    fun loginBannerConfirms() {
        compose.setContent { MinaTheme { LoginBanner(desktop = true, onDone = { calls += "done" }) } }
        compose.onNodeWithTag("browser-login-banner").assertIsDisplayed()
        compose.onNodeWithText(BROWSER_LOGIN_DONE).performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test
    fun siteTileFallsBackToInitial() {
        compose.setContent { MinaTheme { SiteTile(face("news.example.org"), 54.dp, RoundedCornerShape(16.dp), MinaType.Title) } }
        compose.onNodeWithText("new").assertIsDisplayed()
    }
}
