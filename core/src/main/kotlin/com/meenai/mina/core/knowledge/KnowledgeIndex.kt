package com.meenai.mina.core.knowledge

import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.tool.LinkPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/** 索引里的一条:App(APP.md)、站点(SITE.md)或技能(SKILL.md)的头部;[files] 是同目录下的附件(相对路径)。 */
@Serializable
data class IndexEntry(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val packages: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
    val open: String? = null,
    val mode: String? = null,
    /** App 或站点对浏览快慢的要求(`careful`:限制浏览快慢);不写为 null。 */
    val pace: String? = null,
    val links: List<String> = emptyList(),
    val packageAliases: Map<String, String> = emptyMap(),
    val files: List<String> = emptyList(),
    /** 站点命令(`scripts/<name>.js` 的头部),只有站点有。 */
    val commands: List<SiteCommandDecl> = emptyList(),
)

/**
 * 内置知识的目录约定与构建期索引:`knowledge/apps/<id>/APP.md`、`knowledge/sites/<域名>/SITE.md`、`skills/<id>/SKILL.md`。
 * 运行时列不了资源目录,所以构建时扫一遍、校验头部,写成 [RESOURCE];校验不过构建失败。
 */
object KnowledgeIndex {
    const val RESOURCE = "knowledge/index.json"
    const val BODY_LIMIT = 2500
    const val DESCRIPTION_LIMIT = 60
    const val APP = "app"
    const val SITE = "site"
    const val SKILL = "skill"
    val KINDS = listOf(APP, SITE, SKILL)

    /** 资源里的目录。 */
    fun dirOf(kind: String) = when (kind) { APP -> "knowledge/apps"; SITE -> "knowledge/sites"; else -> "skills" }
    fun fileOf(kind: String) = when (kind) { APP -> "APP.md"; SITE -> "SITE.md"; else -> "SKILL.md" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true; prettyPrintIndent = " " }
    private val DOMAIN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+")
    private val REF = Regex("references/[A-Za-z0-9_./-]*[A-Za-z0-9_/-]")
    /** 头部认得的键;写错的键(如把 assistant 拼错)构建失败,不悄悄丢掉。 */
    val KEYS = setOf(
        "name", "display", "description", "packages", "domains", "aliases", "terms", "apps", "sites", "open", "mode", "pace",
        "links", "package_aliases", "assistant", "assistant_entries", "assistant_wait_ms",
    )
    /** App、站点头部 pace 能写的值。 */
    val PACES = setOf("careful")
    private val ENTRY_REF = Regex("\\b(app|site|skill):([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)")

    fun encode(entries: List<IndexEntry>): String = json.encodeToString(entries) + "\n"
    fun decode(text: String): List<IndexEntry> = json.decodeFromString(text)

    class Result(val entries: List<IndexEntry>, val errors: List<String>)

    /** 扫 [root](资源根目录)下三棵树;许可证文件 LICENSE.md 不查本机路径。 */
    fun build(root: File): Result {
        val errors = mutableListOf<String>()
        val entries = mutableListOf<IndexEntry>()
        val bodies = mutableMapOf<IndexEntry, String>()
        for (kind in KINDS) {
            val dir = File(root, dirOf(kind))
            for (sub in dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
                val where = "${dirOf(kind)}/${sub.name}"
                val f = File(sub, fileOf(kind))
                if (!f.isFile) { errors += "$where: missing ${fileOf(kind)}"; continue }
                val (meta, body) = FrontMatter.parse(f.readText())
                val e = parse(kind, sub, meta, body) { errors += "$where/${fileOf(kind)}: $it" } ?: continue
                entries += e
                bodies[e] = body
            }
        }
        crossCheck(root, entries, bodies) { errors += it }
        for (kind in KINDS) {
            File(root, dirOf(kind)).listFiles { f -> f.isDirectory }.orEmpty().forEach { sub ->
                sub.walkTopDown().filter { it.isFile && it.name != "LICENSE.md" }.forEach { file ->
                    val text = file.readText()
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (text.contains("/Users/")) errors += "$rel: contains a local machine path"
                }
            }
        }
        return Result(entries.sortedWith(compareBy({ KINDS.indexOf(it.kind) }, { it.name })), errors)
    }

    private fun parse(kind: String, dir: File, m: Map<String, Any>, body: String, err: (String) -> Unit): IndexEntry? {
        if (m.isEmpty()) { err("missing front matter (metadata wrapped in ---)"); return null }
        var bad = 0
        fun e(msg: String) { bad++; err(msg) }
        m.keys.filter { it !in KEYS }.forEach { e("front matter has an unknown key $it") }
        if (kind != SKILL && "terms" in m) e("terms is only allowed for skills; apps and sites use aliases")
        fun one(k: String): String? = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun many(k: String): List<String> = when (val v = m[k]) {
            is List<*> -> v.filterIsInstance<String>().map(String::trim).filter(String::isNotEmpty)
            is String -> listOf(v.trim()).filter(String::isNotEmpty)
            else -> emptyList()
        }
        val name = one("name") ?: run { e("missing name"); "" }
        if (name != dir.name) e("name ($name) must match the directory name (${dir.name})")
        if (kind == SITE) {
            if (!DOMAIN.matches(name)) e("a site directory name must be its main domain (lowercase)")
        } else if (!PersonalSkills.NAME.matches(name)) e("the name may only use lowercase letters, digits and hyphens")
        val description = one("description") ?: run { e("missing description"); "" }
        if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) e("description exceeds $DESCRIPTION_LIMIT characters")
        if (one("display") == null) e("missing display")
        if (body.isBlank()) e("body is empty")
        if (kind != SKILL && body.length > BODY_LIMIT) e("body is ${body.length} characters, over the $BODY_LIMIT limit; put large material in attachments")
        val packages = many("packages")
        val domains = many("domains").map { it.lowercase().removePrefix("www.") }
        when (kind) {
            APP -> {
                if (packages.isEmpty()) e("missing packages")
                if (one("open") == null) e("missing open")
            }
            SITE -> {
                if (domains.isEmpty()) e("missing domains")
                if (name !in domains) e("directory name ($name) must be in domains")
                if (one("mode") !in setOf("desktop", "mobile")) e("mode must be desktop or mobile")
            }
        }
        if ("pace" in m) {
            if (kind == SKILL) e("pace is only allowed for apps and sites")
            else if (one("pace") !in PACES) e("pace must be ${PACES.joinToString("/")}")
        }
        val links = many("links")
        links.forEach { raw ->
            val p = raw.split('|').map(String::trim)
            if (p.size !in 2..3 || p.any(String::isEmpty)) e("each links entry is written as name | uri or name | uri | package: $raw")
            else LinkPolicy.check(null, p[1], p.getOrNull(2))?.let { e("${p[1]} in links is not allowed: $it") }
        }
        val packageAliases = many("package_aliases").mapNotNull { raw ->
            val p = raw.split('|').map(String::trim)
            if (p.size != 2 || p[1] !in packages) { e("each package_aliases entry is written as alias | package, and the package must be in packages: $raw"); null } else p[0] to p[1]
        }.toMap()
        val commands = commands(kind, dir, ::e)
        if (bad > 0) return null
        val files = dir.walkTopDown().filter { it.isFile && it.name != fileOf(kind) }
            .map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
        return IndexEntry(
            kind = kind, name = name, display = one("display")!!, description = description,
            packages = packages, domains = domains, aliases = many("aliases"), terms = many("terms"),
            apps = many("apps"), sites = many("sites"), open = one("open"), mode = one("mode"), pace = one("pace"),
            links = links, packageAliases = packageAliases, files = files,
            commands = commands,
        )
    }

    /**
     * `scripts/` 下的 .js:列在 scripts/manifest.json 里的是沙箱脚本;其余在站点知识里必须是站点命令(带 [SiteCommandFile.MARK] 头部),
     * 头部校验不过构建失败。站点命令只能写在站点知识里。
     */
    private fun commands(kind: String, dir: File, err: (String) -> Unit): List<SiteCommandDecl> {
        val scripts = File(dir, "scripts")
        val sandbox = File(scripts, "manifest.json").takeIf { it.isFile }?.let { f -> ScriptManifest.parse(f.readText()).map { it.file }.toSet() }.orEmpty()
        return scripts.listFiles { f -> f.isFile && f.name.endsWith(".js") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val rel = "scripts/${f.name}"
            val text = f.readText()
            val command = SiteCommandFile.isCommand(text)
            when {
                rel in sandbox -> { if (command) err("$rel is both listed in scripts/manifest.json and has a site-command header"); null }
                !command -> { if (kind == SITE) err("$rel is neither in scripts/manifest.json nor has a site-command header (${SiteCommandFile.MARK})"); null }
                kind != SITE -> { err("$rel: site commands may only be written in site knowledge"); null }
                else -> SiteCommandFile.parse(rel, text).let { p -> p.errors.forEach { err("$rel: $it") }; p.decl }
            }
        }.also { list -> list.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { err("duplicate site command $it") } }
    }

    private fun crossCheck(root: File, entries: List<IndexEntry>, bodies: Map<IndexEntry, String>, err: (String) -> Unit) {
        fun where(e: IndexEntry) = "${dirOf(e.kind)}/${e.name}"
        entries.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (n, same) -> err("duplicate name $n: ${same.joinToString { where(it) }}") }
        val apps = entries.filter { it.kind == APP }.associateBy { it.name }
        val sites = entries.filter { it.kind == SITE }.associateBy { it.name }
        apps.values.flatMap { a -> a.packages.map { it to a } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }.forEach { (p, owners) -> err("package $p is declared by multiple apps: ${owners.joinToString { where(it) }}") }
        sites.values.flatMap { s -> s.domains.map { it to s } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }.forEach { (d, owners) -> err("domain $d is claimed by multiple sites: ${owners.joinToString { where(it) }}") }
        for (e in entries) {
            e.sites.filter { it !in sites }.forEach { err("${where(e)}: $it in sites does not exist") }
            e.apps.filter { it !in apps }.forEach { err("${where(e)}: $it in apps does not exist") }
            // App 与站点互相关联:一边写了,另一边也要写
            if (e.kind == APP) e.sites.mapNotNull(sites::get).filter { e.name !in it.apps }.forEach { err("${where(it)}: apps is missing ${e.name} (${where(e)} links to it)") }
            if (e.kind == SITE) e.apps.mapNotNull(apps::get).filter { e.name !in it.sites }.forEach { err("${where(it)}: sites is missing ${e.name} (${where(e)} links to it)") }
        }
        val byKey = entries.associateBy { it.kind to it.name }
        for (e in entries) {
            val dir = File(root, where(e))
            val texts = listOf(fileOf(e.kind) to bodies.getValue(e)) +
                e.files.filter { it.endsWith(".md") }.map { it to File(dir, it).readText() }
            for ((file, text) in texts) {
                for (line in text.lines()) {
                    val named = ENTRY_REF.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    named.filter { it !in byKey }.forEach { (k, n) -> err("${where(e)}/$file: the referenced $k:$n does not exist") }
                    // 一行里点名了另一个条目时,附件路径按那个条目找
                    val target = named.singleOrNull()?.let(byKey::get) ?: e
                    REF.findAll(line).map { it.value }.filter { !File(File(root, where(target)), it).exists() }
                        .forEach { err("${where(e)}/$file: attachment $it does not exist in ${where(target)}") }
                }
            }
            File(dir, "scripts/manifest.json").takeIf { it.isFile }?.let { mf ->
                ScriptManifest.parse(mf.readText()).filter { it.runnable && !File(dir, it.file).isFile }
                    .forEach { err("${where(e)}: ${it.file} marked runnable in the manifest does not exist") }
            }
        }
    }

    /** 构建任务入口:`<资源根目录> <输出的 index.json>`。校验不过打印原因并以非零退出。 */
    @JvmStatic
    fun main(args: Array<String>) {
        val r = build(File(args[0]))
        if (r.errors.isNotEmpty()) {
            System.err.println("Built-in knowledge validation failed (${r.errors.size} issues):")
            r.errors.forEach { System.err.println("  $it") }
            exitProcess(1)
        }
        File(args[1]).apply { parentFile.mkdirs() }.writeText(encode(r.entries))
        println("knowledge index: ${r.entries.groupingBy { it.kind }.eachCount()}")
    }
}
