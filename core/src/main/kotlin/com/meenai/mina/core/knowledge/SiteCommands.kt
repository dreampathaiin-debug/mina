package com.meenai.mina.core.knowledge

import com.meenai.mina.core.script.JsSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 站点命令的一个参数;[type] 是 string / integer / number / boolean。 */
@Serializable
data class CommandArg(val name: String, val type: String, val required: Boolean = false, val description: String = "")

/** 站点命令的声明(脚本头部);[file] 是站点目录下的相对路径 `scripts/<name>.js`。 */
@Serializable
data class SiteCommandDecl(val name: String, val description: String, val args: List<CommandArg> = emptyList(), val returns: String, val file: String)

/** 一条可运行的站点命令:所属站点、声明、脚本正文(头部之后)。 */
data class SiteCommand(val site: String, val decl: SiteCommandDecl, val body: String)

/**
 * 站点命令:站点知识目录里的 `scripts/<name>.js`(不在 scripts/manifest.json 里的那些),在当前页面的读取世界里只读地跑。
 * 文件以一段块注释开头:第一行是 [MARK],之后一行一项,到注释结束为止,例如
 * - `name: list_results`
 * - `description: 列出当前结果页上的条目`
 * - `arg: limit integer - 最多几条`
 * - `arg: query string required - 只要标题里有这段字的`
 * - `returns: [{title, url}]`
 *
 * name 与文件名相同;arg 可以有多行,写作「名字 类型 [required] [- 说明]」;头部之后是 async 函数体,参数在 `args` 里。
 */
object SiteCommandFile {
    const val MARK = "/* mina-command"
    const val DESCRIPTION_LIMIT = 120
    const val BODY_LIMIT = 8_000
    val NAME = Regex("[a-z][a-z0-9_]{0,39}")
    val TYPES = listOf("string", "integer", "number", "boolean")
    private val ARG = Regex("""^([a-z][A-Za-z0-9_]{0,39})\s+(string|integer|number|boolean)(\s+required)?(?:\s+-\s+(.+))?$""")

    fun isCommand(text: String): Boolean = clean(text).startsWith(MARK)

    /** [decl] 在没有错误时才有;[body] 是头部之后的脚本。 */
    class Parsed(val decl: SiteCommandDecl?, val body: String, val errors: List<String>)

    private fun clean(text: String) = text.removePrefix("﻿").replace("\r\n", "\n").trimStart()

    /** [file] 是站点目录下的相对路径(`scripts/<name>.js`)。 */
    fun parse(file: String, text: String): Parsed {
        val t = clean(text)
        if (!t.startsWith(MARK)) return Parsed(null, "", listOf("the header must start with `$MARK`"))
        val end = t.indexOf("*/")
        if (end < 0) return Parsed(null, "", listOf("the header is not closed with */"))
        val body = t.substring(end + 2).trim()
        val errors = mutableListOf<String>()
        var name: String? = null
        var description: String? = null
        var returns: String? = null
        val args = mutableListOf<CommandArg>()
        for (raw in t.substring(MARK.length, end).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i <= 0) { errors += "cannot parse this header line: $line"; continue }
            val value = line.substring(i + 1).trim()
            when (val key = line.substring(0, i).trim()) {
                "name" -> name = value
                "description" -> description = value
                "returns" -> returns = value
                "arg" -> ARG.matchEntire(value)?.let { m ->
                    args += CommandArg(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty(), m.groupValues[4].trim())
                } ?: run { errors += "arg is written as name type [required] [- description], with type one of ${TYPES.joinToString("/")}: $value" }
                else -> errors += "the header has an unknown key $key"
            }
        }
        val base = file.substringAfterLast('/').removeSuffix(".js")
        when {
            name == null -> errors += "missing name"
            !NAME.matches(name) -> errors += "name may only use lowercase letters, digits and underscores, must start with a letter, at most 40 characters"
            name != base -> errors += "name ($name) must match the file name ($base.js)"
        }
        if (description.isNullOrEmpty()) errors += "missing description"
        else if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) errors += "description exceeds $DESCRIPTION_LIMIT characters"
        if (returns.isNullOrEmpty()) errors += "missing returns"
        args.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { errors += "duplicate arg $it" }
        if (body.isEmpty()) errors += "script body is empty"
        if (body.length > BODY_LIMIT) errors += "script body is ${body.length} characters, over the $BODY_LIMIT limit"
        // 动态 import 会在读取世界的全局作用域里跑模块,绕开只读包装
        if (JsSource.usesImport(body)) errors += "the script body cannot use import (the word may not appear outside strings and comments)"
        val decl = if (errors.isEmpty()) SiteCommandDecl(name!!, description!!, args, returns!!, file) else null
        return Parsed(decl, body, errors)
    }

    /** 模型给的参数按声明查:没声明的、缺了必填的、类型不对的,返回问题说明;没问题为 null。 */
    fun checkArgs(decl: SiteCommandDecl, args: JsonObject): String? {
        val declared = decl.args.associateBy { it.name }
        args.keys.firstOrNull { it !in declared }?.let {
            return "'$it' is not an argument of ${decl.name}; it takes " + (decl.args.joinToString { a -> a.name }.ifEmpty { "no arguments" })
        }
        for (a in decl.args) {
            val v = args[a.name]
            if (v == null || v is JsonNull) {
                if (a.required) return "${decl.name} needs '${a.name}' (${a.type})"
                continue
            }
            val p = v as? JsonPrimitive ?: return "'${a.name}' must be a ${a.type}"
            val fits = when (a.type) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                "integer" -> !p.isString && p.longOrNull != null
                else -> !p.isString && p.doubleOrNull != null
            }
            if (!fits) return "'${a.name}' must be a ${a.type}"
        }
        return null
    }

    /** 站点知识块里列出的一行。 */
    fun line(d: SiteCommandDecl): String {
        val sig = d.args.joinToString(", ") { it.name + (if (it.required) "" else "?") + ": " + it.type }
        val notes = d.args.filter { it.description.isNotEmpty() }.joinToString("; ") { "${it.name} ${it.description}" }
        return "- ${d.name}($sig): ${d.description}. Returns ${d.returns}" + (if (notes.isEmpty()) "" else ". Arguments: $notes")
    }
}
