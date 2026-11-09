package com.meenai.mina.browser

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StartPageModelTest {
    @get:Rule val texts = ChineseTexts()

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val name: (String) -> String = { if (it == "shop.example.com") "Shopping" else SiteNames.fallback(it) }

    @Test
    fun recentRowsShowSearchesAndPages() {
        val rows = StartPageModel.recentRows(
            listOf(
                RecentPage(AddressInput.search(""), " -search", 3_000),
                RecentPage("https://shop.example.com/item/1", "Fresh mint toothpaste", 2_000),
                RecentPage("https://news.example.org/a", "", 1_000),
            ),
            name,
        )
        assertEquals(listOf("Search: Where to go on the weekend", "Fresh mint toothpaste", "example"), rows.map { it.title })
        assertEquals(listOf("Bing", "Shopping", "example"), rows.map { it.subtitle })
        assertEquals(listOf(true, false, false), rows.map { it.search })
        assertEquals(listOf(null, "shop.example.com", "news.example.org"), rows.map { it.host })
    }

    @Test
    fun recentRowsKeepOnlyTheNewestOfTheSameAddress() {
        val rows = StartPageModel.recentRows(
            listOf(
                RecentPage("https://a.example.com/x#top", "old", 1_000),
                RecentPage("https://b.example.com/", "B", 2_000),
                RecentPage("https://a.example.com/x", "new", 3_000),
            ),
            name,
        )
        assertEquals(listOf("new", "B"), rows.map { it.title })
        assertEquals(3_000L, rows[0].openedAtMs)
    }

    @Test
    fun suggestionsStartWithSearchThenMatchingRecent() {
        val recent = StartPageModel.recentRows(
            listOf(
                RecentPage(AddressInput.search(""), "", 3_000),
                RecentPage("https://news.example.org/weekend", "Recommended weekend parent-child activities", 2_000),
                RecentPage("https://shop.example.com/", "Shopping home page", 1_000),
            ),
            name,
        )
        val s = StartPageModel.suggestions("weekend", recent)
        assertEquals(listOf("Search "weekend"", "Search: Where to go on the weekend", "Recommended weekend parent-child activities"), s.map { it.title })
        assertEquals(listOf("Bing", "Recently opened", "Recently opened"), s.map { it.subtitle })
        assertTrue(s[0].search)
        assertEquals(AddressInput.search("weekend"), s[0].url)
    }

    @Test
    fun suggestionsMatchAddressIgnoringCase() {
        val recent = StartPageModel.recentRows(listOf(RecentPage("https://Shop.example.com/cart", "shopping cart", 1_000)), name)
        assertEquals(listOf("Search "SHOP"", "shopping cart"),StartPageModel.suggestions("SHOP", recent).map { it.title })
    }

    @Test
    fun addressInputOpensDirectly() {
        val s = StartPageModel.suggestions("example.com", emptyList())
        assertEquals(1, s.size)
        assertEquals("https://example.com", s[0].url)
        assertEquals("Open URL", s[0].subtitle)
        assertEquals(false, s[0].search)
        assertEquals(emptyList<Suggestion>(), StartPageModel.suggestions("  ", emptyList()))
    }

    @Test
    fun suggestionsCapRecentHits() {
        val recent = StartPageModel.recentRows((1..9).map { RecentPage("https://s$it.example.com/", "page $it", it.toLong()) }, name)
        assertEquals(1 + StartPageModel.MAX_RECENT_SUGGESTIONS, StartPageModel.suggestions("page", recent).size)
    }

    @Test
    fun relativeTime() {
        val now = at(2026, 10, 28, 11, 30)
        fun rel(t: Long) = StartPageModel.relativeTime(t, now, zone)
        assertEquals("just", rel(now - 20_000))
        assertEquals("10 minutes ago", rel(now - 10 * 60_000))
        assertEquals("today 09:05", rel(at(2026, 10, 28, 9, 5)))
        assertEquals("today 00:10", rel(at(2026, 10, 28, 0, 10)))
        assertEquals("yesterday", rel(at(2026, 10, 27, 23, 50)))
        assertEquals("October 21", rel(at(2026, 10, 21, 8, 0)))
        assertEquals("December 31, 2025", rel(at(2025, 12, 31, 8, 0)))
    }
}
