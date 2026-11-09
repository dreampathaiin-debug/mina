package com.meenai.mina.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 *English(default)The three interface texts, Simplified Chinese and Traditional Chinese, must be matched one by one.:Same entries, same placeholders(Traditional Chinese vs. Simplified Chinese);There should be no untranslated Chinese in English.
 *other languages([others])Also have to match up with English,Write the plural according to the quantifier in the respective language.
 */
class StringResourcesTest {
    /**An interface language other than Chinese and English:Resource folder,Which quantifiers should be written with the plural form in this language?(Pluralization rules by CLDR). */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-ja", Locale.JAPANESE, setOf("other")),
        Other("values-ko", Locale.KOREAN, setOf("other")),
        Other("values-es", Locale.forLanguageTag("es"), setOf("one", "many", "other")),
        Other("values-pt", Locale.forLanguageTag("pt"), setOf("one", "many", "other")),
        Other("values-ru", Locale.forLanguageTag("ru"), setOf("one", "few", "many", "other")),
        //The Indonesian resource folder follows Android’s old language code in
        Other("values-in", Locale.forLanguageTag("id"), setOf("other")),
    )

    private val en = ResFileTexts.english
    private val zh = ResFileTexts.chinese
    private val hant = ResFileTexts.traditional

    /**Bid translatable="false"Entries are only in the default file. */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun everyStringHasBothLanguages() {
        assertEquals(en.strings.keys - fixed, zh.strings.keys)
        assertEquals(en.plurals.keys, zh.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun placeholdersMatch() {
        //The date fields in the two languages ​​are different(Write month numbers in Chinese,Write the month name in English)
        for (name in zh.strings.keys - setOf("date_month_day", "date_year_month_day")) assertEquals(name, args(en.strings.getValue(name)), args(zh.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            val expected = args(zh.plurals.getValue(name).getValue("other"))
            en.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun traditionalMatchesSimplified() {
        assertEquals(zh.strings.keys, hant.strings.keys)
        assertEquals(zh.plurals.keys, hant.plurals.keys)
        for (name in zh.strings.keys) assertEquals(name, args(zh.strings.getValue(name)), args(hant.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            assertEquals(name, zh.plurals.getValue(name).keys, hant.plurals.getValue(name).keys)
            zh.plurals.getValue(name).forEach { (q, v) -> assertEquals("$name/$q", args(v), args(hant.plurals.getValue(name).getValue(q))) }
        }
    }

    @Test
    fun otherLanguagesMatchEnglish() {
        for (o in others) {
            val t = o.texts
            assertEquals(o.folder, en.strings.keys - fixed, t.strings.keys)
            assertEquals(o.folder, en.plurals.keys, t.plurals.keys)
            for (name in t.strings.keys - setOf("date_month_day", "date_year_month_day")) {
                assertEquals("${o.folder}/$name", args(en.strings.getValue(name)), args(t.strings.getValue(name)))
            }
            for (name in t.plurals.keys) {
                val forms = t.plurals.getValue(name)
                assertEquals("${o.folder}/$name", o.quantities, forms.keys)
                val expected = args(en.plurals.getValue(name).getValue("other"))
                forms.forEach { (q, v) -> assertEquals("${o.folder}/$name/$q", expected, args(v)) }
            }
        }
    }

    /**There should be no untranslated Chinese characters in languages ​​that do not use Chinese characters.;Japanese originally uses Chinese characters,Don’t check. */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others.filter { it.folder != "values-ja" }) {
            val left = o.texts.strings.filter { (k, v) -> k != "chat_example_creator" && cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /**Each interface language has a corresponding folder in the resources,ui_localeThis is the language in which it is written. */
    @Test
    fun everyAppLanguageHasItsTexts() {
        val byLanguage = others.associateBy { AppLanguage.modernCode(it.locale.language) }
        for (l in AppLanguage.entries) {
            val code = l.language ?: continue
            if (code == "zh" || code == "en") continue
            val o = byLanguage[code]
            assertTrue("$l has no folder", o != null)
            assertEquals(l.toString(), code, AppLanguage.modernCode(Locale.forLanguageTag(o!!.texts.strings.getValue("ui_locale")).language))
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        //The blogger’s name in the example is originally in Chinese.
        val allowed = fixed + "chat_example_creator"
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in allowed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        //There is no Android in the single test ICU:The step of supplementing the text system is replaced with a fake one.,Only recognize one private tag is Traditional Chinese
        val likely = { tag: String -> if (tag == "zh-x-hant") "Hant" else "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        assertEquals(AppLanguage.System, of(null))
        assertEquals(AppLanguage.System, of(""))
        assertEquals(AppLanguage.English, of("en"))
        assertEquals(AppLanguage.English, of("fr-FR"))
        //For other languages, only look at the language code,No matter the region
        assertEquals(AppLanguage.Japanese, of("ja-JP"))
        assertEquals(AppLanguage.Portuguese, of("pt-PT"))
        assertEquals(AppLanguage.Portuguese, of("pt-BR"))
        assertEquals(AppLanguage.Spanish, of("es-419"))
        //Indonesian:Both the current code and the old code still used by Android Locale are recognized
        assertEquals(AppLanguage.Indonesian, of("id-ID"))
        assertEquals(AppLanguage.Indonesian, of("in-ID"))
    }

    @Test
    fun eachLanguageHasItsOwnTag() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
