package com.meenai.mina.ui

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.SubPage
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot (GPL-2.0-or-later) and talloc (LGPL-3.0-or-later) ship with the Mina package as separate programs; Mina does not link them." +
            "Their full sources, Mina's patches and build scripts ship with every release on Mina's releases page as mina-<version>-gpl-sources.tar.gz;" +
            "the tools/sandbox/ folder in the source repo also has the source URLs, checksums, patches and build scripts, so you can rebuild them yourself."

    const val RUNTIME_NOTE =
        "The system that runs Python, Node and similar programs ships with the Mina package, built from Alpine Linux 3.22; its packages and licenses are listed below." +
            "The sources of the GPL, LGPL and MPL packages (Alpine's build recipes and upstream sources) are in the same mina-<version>-gpl-sources.tar.gz."

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "Version ${f("version")}. ${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mina", "Apache-2.0", "Mina itself, including the runtime launcher, the mina command and the skill scripts. Full license text at the top of this page."),
        Entry("PRoot", "GPL-2.0-or-later", "The environment that runs Python, Node and similar programs, version 5.4.0, with one patch (PRoot kills all child processes on exit).$GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "Version 2.4.3, unmodified, statically linked into the PRoot binary. Relink it with the sources and scripts in tools/sandbox/."),
        Entry("Apache Commons Compress", "Apache-2.0", "Unpacks the bundled runtime on first launch.Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "A dependency of Apache Commons Compress.Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "A dependency of Apache Commons Compress.Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "A dependency of Apache Commons Compress.Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin standard library", "Apache-2.0", "kotlin-stdlib 2.1.20 and newer, plus JetBrains annotations 23.0.0.https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "Version 1.9.0. https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "Version 1.7.3. https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "OTLP message definitions for detailed traces. https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "Protobuf encoding for detailed traces. https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "Version 4.12.0, used to call model APIs.https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "Version 3.6.0, a dependency of OkHttp.https://github.com/square/okio/"),
        Entry("AndroidX (including Jetpack Compose)", "Apache-2.0", "Compose 1.7.6, Material3 1.3.1, Activity 1.9.3, Lifecycle 2.8.3, Core 1.13.1, WebKit 1.12.1 and other UI and system component libraries.https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture (pulled in by AndroidX)", "Apache-2.0", "listenablefuture 1.0。https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", "The shell-context approach follows scrcpy; the affected source files keep the attribution in their headers."),
        Entry("Alpine Linux", "each package's own license", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "Version 1.10, unpacks the bundled runtime on first launch.https://tukaani.org/xz/java.html"),
        Entry("commonmark-java", "BSD-2-Clause", "Version 0.30.1 (with table and strikethrough extensions), parses the Markdown in replies before display.Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
    )
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Mina 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mina" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MinaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MinaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MinaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MinaType.Hint, color = MinaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
