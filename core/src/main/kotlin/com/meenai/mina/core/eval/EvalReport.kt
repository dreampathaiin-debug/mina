package com.meenai.mina.core.eval

import com.meenai.mina.core.record.RunMetrics
import com.meenai.mina.core.record.RunStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.ceil

/** 每百万 token 单价;运行时传入,不写进仓库。 */
data class Prices(val inputPerM: Double, val outputPerM: Double, val cachedPerM: Double)

/** 运行汇总 markdown。只放指标和每题结论,不放回答全文和截图(它会提交进仓库)。 */
object EvalReport {
    fun markdown(
        run: String,
        model: String,
        scores: List<Pair<TaskScore, Observed>>,
        skipped: List<Pair<String, String>>,
        prices: Prices?,
        /** 本次运行的开关组合([com.meenai.mina.core.agent.SpeedFlags.describe])。 */
        flags: String = "none",
        /** 并行评测时整轮从第一题开始到最后一题结束的时间;串行时为空,不出这一行。 */
        totalWallMs: Long? = null,
        /** 同时跑几题(evalParallel);1 为串行。 */
        parallel: Int = 1,
        /** 回放评测(evalVariants)里先跑、用来记下路线的原题;单列一节,不计入指标。 */
        originals: List<Pair<TaskScore, Observed>> = emptyList(),
    ): String {
        val n = scores.size
        val passed = scores.count { it.first.passed }
        // 回答如实说没办成的(判过结局)不算报成功
        val reportedSuccess = scores.count { Scoring.recorded(it.second).outcome == "succeeded" }
        val falseSuccess = scores.count { it.first.falseSuccess }
        val unverified = scores.count { it.first.unverified }
        val steps = scores.map { it.second.steps.toDouble() }
        val secs = scores.map { it.second.wallMs / 1000.0 }
        val inT = scores.sumOf { it.second.inputTokens }
        val outT = scores.sumOf { it.second.outputTokens }
        val cachedT = scores.sumOf { it.second.cachedTokens }
        val calls = scores.map { it.second.modelCalls.toDouble() }
        val wallT = scores.sumOf { it.second.wallMs }

        val sb = StringBuilder()
        sb.appendLine("# Eval summary $run")
        sb.appendLine()
        sb.appendLine("Model: $model; flags: $flags; ran $n tasks, skipped ${skipped.size}" + (if (parallel > 1) "; ran $parallel in parallel." else "."))
        sb.appendLine()
        sb.appendLine("## Metrics")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Pass rate | ${ratio(passed, n)} |")
        if (totalWallMs != null) sb.appendLine("| Total wall time (s) | ${num(totalWallMs / 1000.0)} |")
        sb.appendLine("| False success rate (of reported success) | ${ratio(falseSuccess, reportedSuccess)} |")
        sb.appendLine("| False success rate (of all) | ${ratio(falseSuccess, n)} |")
        sb.appendLine("| Unverified success | $unverified |")
        sb.appendLine("| Unverified and failed | ${scores.count { it.first.failureClass == FailureClass.unverified_wrong }} |")
        // 撞上屏上看得出的登录墙等交给了用户:单列,不算办成(算不算由人定)
        scores.mapNotNull { it.first.handedOver }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (kind, c) -> sb.appendLine("| Handed to user: $kind (not counted as done) | $c |") }
        sb.appendLine("| Steps median | ${num(median(steps))} |")
        sb.appendLine("| Steps P90 | ${num(p90(steps))} |")
        sb.appendLine("| Time median (s) | ${num(median(secs))} |")
        sb.appendLine("| Time P90 (s) | ${num(p90(secs))} |")
        sb.appendLine("| Model calls median | ${num(median(calls))} |")
        sb.appendLine("| Model calls P90 | ${num(p90(calls))} |")
        sb.appendLine("| Model wait share | ${percent(scores.sumOf { it.second.modelMs }, wallT)} |")
        sb.appendLine("| Tool execution share | ${percent(scores.sumOf { it.second.toolMs }, wallT)} |")
        val waitT = scores.sumOf { it.second.waitMs }
        // 等界面的时间要工具上报;一条都没有就是没量到,不写 0%
        sb.appendLine("| Of which waiting on UI | ${if (waitT == 0L) "(no data yet)" else percent(waitT, wallT)} |")
        // 模型、工具之外:等用户回答或同意、重试退避、界面开销;三项加起来是 100%
        val otherT = (wallT - scores.sumOf { it.second.modelMs } - scores.sumOf { it.second.toolMs }).coerceAtLeast(0)
        sb.appendLine("| Other / waiting on user share | ${percent(otherT, wallT)} |")
        sb.appendLine("| Replay hits | ${scores.sumOf { it.second.replayHits }} |")
        sb.appendLine("| Replay hand-backs | ${scores.sumOf { it.second.replayHandBacks }} |")
        if (scores.any { it.second.replayHits + it.second.replayHandBacks > 0 }) {
            val handedBack = scores.filter { it.second.replayHandBacks > 0 }
            sb.appendLine("| Replay hit rate (tasks that followed the route) | ${ratio(scores.count { it.second.replayHits > 0 }, n)} |")
            sb.appendLine("| Replay hand-back rate (tasks handed back mid-way) | ${ratio(handedBack.size, n)} |")
            sb.appendLine("| Pass rate after hand-back | ${ratio(handedBack.count { it.first.passed }, handedBack.size)} |")
        }
        sb.appendLine("| Input tokens | $inT |")
        sb.appendLine("| Of which cached tokens | $cachedT |")
        sb.appendLine("| Output tokens | $outT |")
        sb.appendLine("| Cache hit rate (cached/input) | ${percent(cachedT, inT)} |")
        val perTask = scores.filter { it.second.inputTokens > 0 }.map { it.second.cachedTokens * 100.0 / it.second.inputTokens }
        sb.appendLine("| Median per-task cache hit rate | ${if (perTask.isEmpty()) "-" else num(median(perTask)) + "%"} |")
        if (prices != null) {
            // 输入 token 含缓存部分,缓存部分按缓存单价计
            val cost = ((inT - cachedT) * prices.inputPerM + cachedT * prices.cachedPerM + outT * prices.outputPerM) / 1_000_000
            sb.appendLine("| Cost | ${num(cost)} |")
        }
        sb.appendLine()

        metricsSection(sb, scores)

        sb.appendLine("## Counts by class")
        sb.appendLine()
        val byClass = scores.mapNotNull { it.first.failureClass }.groupingBy { it }.eachCount()
        if (byClass.isEmpty()) sb.appendLine("No failures.")
        else {
            sb.appendLine("| Class | Count |")
            sb.appendLine("|---|---|")
            FailureClass.entries.filter { it in byClass }.forEach { sb.appendLine("| ${it.name} | ${byClass.getValue(it)} |") }
        }
        sb.appendLine()

        sb.appendLine("## Per task")
        sb.appendLine()
        sb.appendLine("| id | Outcome | Pass | Class | Check | Steps | Seconds | Input tokens | Cache hit rate | Output tokens |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        scores.forEach { (s, o) ->
            // 交出了回答、判下来没办成的:写判的结局
            val judged = o.judged?.takeIf { Scoring.recorded(o) !== o }?.let { "(judged: $it)" }.orEmpty()
            sb.appendLine("| ${s.id} | ${o.outcome}$judged${s.handedOver?.let { "(handed to user: $it)" }.orEmpty()} | ${if (s.passed) "yes" else "no"} | ${s.failureClass?.name ?: ""} | ${o.verdict ?: ""} | ${o.steps} | " +
                "${num(o.wallMs / 1000.0)} | ${o.inputTokens} | ${percent(o.cachedTokens, o.inputTokens)} | ${o.outputTokens} |")
        }
        sb.appendLine()

        if (originals.isNotEmpty()) {
            sb.appendLine("## Original tasks (for route recording; not counted in the metrics above)")
            sb.appendLine()
            sb.appendLine("| id | outcome | passed | steps | seconds |")
            sb.appendLine("|---|---|---|---|---|")
            originals.forEach { (s, o) ->
                sb.appendLine("| ${s.id} | ${o.outcome}${s.handedOver?.let { "(handed to user: $it)" }.orEmpty()} | ${if (s.passed) "yes" else "no"} | ${o.steps} | ${num(o.wallMs / 1000.0)} |")
            }
            sb.appendLine()
        }

        sb.appendLine("## Skipped")
        sb.appendLine()
        if (skipped.isEmpty()) sb.appendLine("None.") else skipped.forEach { (id, why) -> sb.appendLine("- $id:$why") }
        sb.appendLine()

        sb.appendLine("## Apps that left the task scope (recorded only, not a failure)")
        sb.appendLine()
        val drift = scores.filter { it.first.leftScope.isNotEmpty() }
        if (drift.isEmpty()) sb.appendLine("None.") else drift.forEach { (s, _) -> sb.appendLine("- ${s.id}:${s.leftScope.joinToString("、")}") }
        sb.appendLine()

        sb.appendLine("## Test-data markers left behind (need manual cleanup)")
        sb.appendLine()
        val markers = (originals + scores).mapNotNull { (s, _) -> s.marker?.let { s.id to it } }
        if (markers.isEmpty()) sb.appendLine("None.") else markers.forEach { (id, m) -> sb.appendLine("- $id:$m") }
        return sb.toString()
    }

    /**
     * 「运行小结」一节:有运行记录的题按 [RunMetrics] 汇总(第一次反馈、来回次数、时间去向;批里的子步不重复计),
     * 再逐题一行。没有一题有记录时不出这一节。
     */
    private fun metricsSection(sb: StringBuilder, scores: List<Pair<TaskScore, Observed>>) {
        val rows = scores.mapNotNull { (s, o) -> o.metrics?.let { s.id to it } }
        if (rows.isEmpty()) return
        val st = RunStats.of(rows.map { it.second })
        sb.appendLine("## Run summary")
        sb.appendLine()
        sb.appendLine("Computed from run records (${rows.size} tasks): time counts from when Mina starts handling the task; reads and actions are split by the kind given at the entry point; sub-steps inside a batch are not counted twice.")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Time median (s) | ${num(st.medianTotalMs / 1000.0)} |")
        sb.appendLine("| First feedback median (s) | ${st.medianFirstFeedbackMs?.let { num(it / 1000.0) } ?: "-"} |")
        sb.appendLine("| Avg model round-trips | ${num(Math.round(st.avgModelCalls * 10) / 10.0)} |")
        sb.appendLine("| Model thinking share | ${percent(st.modelMs, st.totalMs)} |")
        sb.appendLine("| Screen reading share | ${percent(st.observeMs, st.totalMs)} |")
        sb.appendLine("| Action share | ${percent(st.actMs, st.totalMs)} |")
        sb.appendLine("| Other (waiting, retries) share | ${percent(st.otherMs, st.totalMs)} |")
        sb.appendLine()
        sb.appendLine("| id | Seconds | First feedback (s) | Round-trips | Total requests | Model (s) | Reading (s) | Acting (s) | Other (s) | Read / action calls | Tokens |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEach { (id, m) ->
            sb.appendLine("| $id | ${secs(m.totalMs)} | ${m.firstFeedbackMs?.let(::secs) ?: "—"} | ${m.modelCalls} | ${m.allCalls ?: "—"} | " +
                "${secs(m.modelMs)} | ${secs(m.observeMs)} | ${secs(m.actMs)} | ${secs(m.otherMs)} | ${m.observeCalls} / ${m.actCalls} | ${m.tokens} |")
        }
        sb.appendLine()
    }

    /** 每题的运行小结,一个 JSON 数组(评测结果目录下的 metrics.json),每项是 `id` 和 `metrics`;没有运行记录的题不列。 */
    fun metricsJson(scores: List<Pair<TaskScore, Observed>>): String = JsonArray(scores.mapNotNull { (s, o) ->
        o.metrics?.let { m -> JsonObject(mapOf("id" to JsonPrimitive(s.id), "metrics" to METRICS_JSON.encodeToJsonElement(RunMetrics.serializer(), m))) }
    }).toString()

    private val METRICS_JSON = Json { encodeDefaults = true }

    private fun secs(ms: Long) = num(Math.round(ms / 100.0) / 10.0)

    /** 门槛结论:[passed] 为 false 时 [reasons] 列出没达标的地方。 */
    data class Gate(val passed: Boolean, val reasons: List<String>)

    /**
     * 配对题的门槛:带着学到的技能跑的一轮([treated])假成功必须为 0,
     * 且办成数不低于基线([baseline])减 1。
     */
    fun gate(baseline: List<TaskScore>, treated: List<TaskScore>): Gate {
        val reasons = mutableListOf<String>()
        val fs = treated.count { it.falseSuccess }
        if (fs > 0) reasons += "$fs tasks in the skill round were false successes (${treated.filter { it.falseSuccess }.joinToString(", ") { it.id }})"
        val b = baseline.count { it.passed }
        val t = treated.count { it.passed }
        if (t < b - 1) reasons += "Pass count $t is ${b - t} below the baseline $b (at most 1 fewer is allowed)"
        return Gate(reasons.isEmpty(), reasons)
    }

    /**
     * 基线与带技能两轮的对比报告:办成率、假成功率、未核实成功,有观察数据时再加步数与耗时中位数;
     * 每份学到的技能被用了几次([skillUse]);最后是门槛结论。
     */
    fun compare(
        baseline: List<TaskScore>,
        treated: List<TaskScore>,
        skillUse: Map<String, Int>,
        baselineObserved: List<Observed> = emptyList(),
        treatedObserved: List<Observed> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## Baseline vs skill")
        sb.appendLine()
        sb.appendLine("| Metric | Baseline | Skill | Change |")
        sb.appendLine("|---|---|---|---|")
        fun row(name: String, f: (List<TaskScore>) -> Int) {
            val b = f(baseline); val t = f(treated)
            sb.appendLine("| $name | $b/${baseline.size} | $t/${treated.size} | ${signed(t - b)} |")
        }
        row("Pass") { s -> s.count { it.passed } }
        row("False success") { s -> s.count { it.falseSuccess } }
        row("Unverified success") { s -> s.count { it.unverified } }
        if (baselineObserved.isNotEmpty() && treatedObserved.isNotEmpty()) {
            fun med(o: List<Observed>, f: (Observed) -> Double) = median(o.map(f))
            val bs = med(baselineObserved) { it.steps.toDouble() }; val ts = med(treatedObserved) { it.steps.toDouble() }
            sb.appendLine("| Steps median | ${num(bs)} | ${num(ts)} | ${signedNum(ts - bs)} |")
            val bt = med(baselineObserved) { it.wallMs / 1000.0 }; val tt = med(treatedObserved) { it.wallMs / 1000.0 }
            sb.appendLine("| Time median (s) | ${num(bt)} | ${num(tt)} | ${signedNum(tt - bt)} |")
            // 用量按题号配对:两轮都跑了的题才比,各取每题的中位数(两轮题数不同时合计没法比)
            val bById = baseline.map { it.id }.zip(baselineObserved).toMap()
            val pairs = treated.map { it.id }.zip(treatedObserved).mapNotNull { (id, t) -> bById[id]?.let { it to t } }
            if (pairs.isNotEmpty()) {
                fun row(name: String, f: (Observed) -> Double) {
                    val b = median(pairs.map { f(it.first) }); val t = median(pairs.map { f(it.second) })
                    sb.appendLine("| $name (median over ${pairs.size} paired tasks) | ${num(b)} | ${num(t)} | ${signedNum(t - b)} |")
                }
                row("Input tokens") { it.inputTokens.toDouble() }
                row("Output tokens") { it.outputTokens.toDouble() }
                row("Cache hit rate %") { if (it.inputTokens == 0L) 0.0 else Math.round(it.cachedTokens * 1000.0 / it.inputTokens) / 10.0 }
            }
        }
        sb.appendLine()
        sb.appendLine("## Learned skill usage")
        sb.appendLine()
        if (skillUse.isEmpty()) sb.appendLine("No learned skills were used.")
        else skillUse.entries.sortedByDescending { it.value }.forEach { (name, n) -> sb.appendLine("- $name: $n times") }
        sb.appendLine()
        val g = gate(baseline, treated)
        sb.appendLine("## Gate")
        sb.appendLine()
        sb.appendLine(if (g.passed) "Passed: the skill round has no false successes and passes are at least baseline minus 1." else "Failed:")
        g.reasons.forEach { sb.appendLine("- $it") }
        return sb.toString()
    }

    private fun signed(d: Int) = if (d > 0) "+$d" else "$d"
    private fun signedNum(d: Double) = if (d > 0) "+${num(d)}" else num(d)

    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** 最近秩法:排序后第 ceil(0.9n) 个。 */
    internal fun p90(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[ceil(0.9 * s.size).toInt() - 1]
    }

    private fun ratio(a: Int, b: Int): String =
        if (b == 0) "$a/0(—)" else "$a/$b(${String.format(Locale.ROOT, "%.1f", a * 100.0 / b)}%)"

    private fun percent(a: Long, b: Long): String =
        if (b == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", a * 100.0 / b)

    private fun num(x: Double): String =
        if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
}
