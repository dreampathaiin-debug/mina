package com.meenai.mina.i18n

import com.meenai.mina.R
import org.junit.rules.ExternalResource
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 *Interface text in JVM single test:Read directly `src/main/res/<folder>/`resource files under,Remove escaping according to Android rules,
 *Avoid setting up an Android environment just to get text.[folder]yes `values`(English)or `values-zh`(Simplified Chinese)or `values-b+zh+Hant`(Traditional Chinese).
 */
class ResFileTexts(folder: String, private val locale: Locale) : UiTexts {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>

    init {
        val s = mutableMapOf<String, String>()
        val p = mutableMapOf<String, Map<String, String>>()
        resFiles(folder).forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> s[name] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        p[name] = (0 until items.length).associate { j ->
                            val item = items.item(j) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
        }
        strings = s
        plurals = p
    }

    override fun string(id: Int, args: Array<out Any?>): String {
        val name = stringNames[id] ?: error("no R.string with id $id")
        val raw = strings[name] ?: fallback?.strings?.get(name) ?: error("no string $name")
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val name = pluralNames[id] ?: error("no R.plurals with id $id")
        val forms = plurals[name] ?: fallback?.plurals?.get(name) ?: error("no plurals $name")
        //English:1 use one;Chinese only other
        val raw = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
        return String.format(locale, raw, *(if (args.isEmpty()) arrayOf(count) else args))
    }

    /**untranslated words(translatable="false")Only in the default file. */
    private val fallback: ResFileTexts? = if (folder == "values") null else english

    companion object {
        val chinese by lazy { ResFileTexts("values-zh", Locale.SIMPLIFIED_CHINESE) }
        val hindi by lazy { ResFileTexts("values-hi", Locale.forLanguageTag("hi")) }
        val traditional by lazy { ResFileTexts("values-b+zh+Hant", Locale.TRADITIONAL_CHINESE) }
        val english: ResFileTexts by lazy { ResFileTexts("values", Locale.ENGLISH) }

        fun resFiles(folder: String): List<File> =
            (File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray())
                .sortedBy { it.name }

        private fun names(cls: Class<*>): Map<Int, String> = cls.fields.associate { it.getInt(null) to it.name }
        private val stringNames by lazy { names(R.string::class.java) }
        private val pluralNames by lazy { names(R.plurals::class.java) }

        /**Escape of Android resource text:Leave the quotation marks blank.,Continuous white spaces outside quotation marks are merged into one;Backslash escaping. */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            var space = false
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val n = raw[i + 1]
                        out.append(
                            when (n) {
                                'n' -> '\n'
                                't' -> '\t'
                                'u' -> raw.substring(i + 2, i + 6).toInt(16).toChar().also { i += 4 }
                                else -> n
                            },
                        )
                        i += 2
                        space = false
                        continue
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> {
                        if (!space) out.append(' ')
                        space = true
                        i++
                        continue
                    }
                    else -> out.append(c)
                }
                space = false
                i++
            }
            return if (raw.trim().startsWith('"')) out.toString() else out.toString().trim(' ')
        }
    }
}

/**During the test, fix the interface text to Chinese(Assert that it is written in Chinese). */
class ChineseTexts : ExternalResource() {
    private var previous: UiTexts? = null
    override fun before() {
        previous = UiText.source
        UiText.source = ResFileTexts.chinese
    }

    override fun after() {
        UiText.source = previous
    }
}

/**During the test, the interface text was changed to English. */
class EnglishTexts : ExternalResource() {
    private var previous: UiTexts? = null
    override fun before() {
        previous = UiText.source
        UiText.source = ResFileTexts.english
    }

    override fun after() {
        UiText.source = previous
    }
}
