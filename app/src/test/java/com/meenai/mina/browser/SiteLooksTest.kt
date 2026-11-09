package com.meenai.mina.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteLooksTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun store() = SiteLooks(tmp.root, clock = { now })

    @Test
    fun fallbackNameDropsWwwSubdomainsAndSuffix() {
        assertEquals("example", SiteNames.fallback("www.example.com"))
        assertEquals("example", SiteNames.fallback("shop.example.com"))
        assertEquals("example", SiteNames.fallback("mail.example.com.cn"))
        assertEquals("localhost", SiteNames.fallback("localhost"))
        assertEquals("192.168.1.1", SiteNames.fallback("192.168.1.1"))
    }

    @Test
    fun initialIsFirstCharacterUppercased() {
        assertEquals("E", SiteNames.initial("example"))
        assertEquals("S", SiteNames.initial("Shopping"))
        assertEquals("?", SiteNames.initial(" "))
    }

    @Test
    fun tileColorIsStablePerHostAndFromThePalette() {
        val a = SiteTiles.color("shop.example.com")
        assertEquals(a, SiteTiles.color("shop.example.com"))
        assertEquals(SiteTiles.color("example.com"), SiteTiles.color("www.example.com"))
        assertTrue(a in SiteTiles.PALETTE)
        //Different host names are generally scattered into different colors
        val colors = (1..40).map { SiteTiles.color("site$it.example.com") }.toSet()
        assertTrue(colors.size >= 6)
    }

    @Test
    fun pickPrefersDeclaredNameThenTitlePart() {
        assertEquals("Shopping" to SiteNames.RANK_DECLARED, SiteNames.pick("Shopping", "whatever -Others", false))
        assertEquals("Shopping" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "Shopping - All the good stuff is here", true))
        assertEquals("Shopping" to SiteNames.RANK_DECLARED, SiteNames.pick("Shopping", "Log in -Shopping", false))
        assertEquals("12306-railwa" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "12306-railwa", true))
        assertNull(SiteNames.pick(null, "  ", true))
        assertEquals(SiteNames.MAX_LEN, SiteNames.pick("One, two, three, four, five, six, seven, eight, ninety-one, two, three, four, five", null, true)!!.first.length)
    }

    @Test
    fun otherPagesTitlesDoNotNameTheSite() {
        //It is unclear which paragraph of the title of pages such as login and articles is the website name.:Unnamed
        assertNull(SiteNames.pick(null, "Example - Unified login", false))
        assertNull(SiteNames.pick(" ", "Fresh mint toothpaste |Shopping", false))
        //Take the first paragraph of the same title on the homepage
        assertEquals("Example" to SiteNames.RANK_HOME_TITLE, SiteNames.pick(null, "Example - Unified login", true))
    }

    @Test
    fun namesFromOtherPagesTitlesSavedEarlierAreDropped() {
        tmp.root.resolve("browser_sites.json").writeText(
            """{"example.com":{"name":"Unified login","rank":1,"iconAt":5},"shop.example.com":{"name":"Shopping","rank":2}}""",
        )
        val s = store()
        assertEquals(SiteLook(null, 0, 5), s.entries.value["example.com"])
        assertEquals("example", s.face("m.example.com").name)
        assertEquals("Shopping", s.face("shop.example.com").name)
        //Fill in the name of the homepage
        s.named("example.com", "Example", SiteNames.RANK_HOME_TITLE)
        assertEquals("Example", s.face("m.example.com").name)
    }

    @Test
    fun homePageIsRootPath() {
        assertTrue(SiteNames.isHomePage("https://example.com"))
        assertTrue(SiteNames.isHomePage("https://example.com/?from=x"))
        assertEquals(false, SiteNames.isHomePage("https://example.com/a"))
    }

    @Test
    fun parseReadsTheScriptResult() {
        assertEquals("Shopping" to "front page", SiteNames.parse("\"{\\\"site\\\":\\\"Shopping\\\",\\\"title\\\":\\\"front page\\\"}\""))
        assertEquals(null to "front page", SiteNames.parse("\"{\\\"site\\\":\\\"\\\",\\\"title\\\":\\\"front page\\\"}\""))
        assertNull(SiteNames.parse("null"))
        assertNull(SiteNames.parse(null))
    }

    @Test
    fun namesKeepTheMostReliableAndReachTheRegistrableDomain() {
        val s = store()
        s.named("shop.example.com", "shopping channel", SiteNames.RANK_HOME_TITLE)
        assertEquals("shopping channel", s.face("shop.example.com").name)
        assertEquals("shopping channel", s.face("m.example.com").name)
        s.named("example.com", "Shopping", SiteNames.RANK_DECLARED)
        //If you don’t want to grab the homepage title of another subdomain name, you can register a more reliable name on the domain name.
        s.named("m.example.com", "mobile version", SiteNames.RANK_HOME_TITLE)
        assertEquals("Shopping", s.face("example.com").name)
        assertEquals("mobile version", s.face("m.example.com").name)
        //hostname itself:If it's no worse than the original, replace it.
        s.named("shop.example.com", "shopping mall", SiteNames.RANK_DECLARED)
        s.named("shop.example.com", "Home page title", SiteNames.RANK_HOME_TITLE)
        assertEquals("shopping mall", s.face("shop.example.com").name)
        //Saved
        assertEquals(s.entries.value, store().entries.value)
    }

    @Test
    fun faceFallsBackWhenNothingKnown() {
        val f = store().face("news.example.org")
        assertEquals("example", f.name)
        assertEquals("E", f.initial)
        assertEquals(SiteTiles.color("news.example.org"), f.color)
        assertNull(f.icon)
    }

    @Test
    fun iconsGoToHostAndToRegistrableOnlyWhenMissing() {
        val s = store()
        assertEquals(listOf("m.example.com", "example.com"), s.iconTargets("m.example.com"))
        s.iconFile("m.example.com").apply { parentFile?.mkdirs(); writeText("x") }
        s.iconSaved("m.example.com")
        s.iconSaved("example.com")
        assertEquals(listOf("shop.example.com"), s.iconTargets("shop.example.com"))
        //What has just been saved will not be saved again.,Save after a while
        assertEquals(emptyList<String>(), s.iconTargets("example.com"))
        assertEquals(emptyList<String>(), s.iconTargets("m.example.com"))
        //Subdomains that do not have their own icons use registrable domain names.
        val f = s.face("shop.example.com")
        assertEquals(s.iconFile("example.com"), f.icon)
        assertEquals(1_000L, f.iconAtMs)
        now = 1_000L + SiteLooks.ICON_REFRESH_MS
        assertEquals(listOf("m.example.com"), s.iconTargets("m.example.com"))
        s.iconSaved("shop.example.com")
        assertEquals(s.iconFile("shop.example.com"), s.face("shop.example.com").icon)
        assertNotEquals(f.iconAtMs, s.face("shop.example.com").iconAtMs)
    }

    @Test
    fun corruptFileStartsEmpty() {
        tmp.root.resolve("browser_sites.json").writeText("{not json")
        assertEquals(emptyMap<String, SiteLook>(), store().entries.value)
    }
}
