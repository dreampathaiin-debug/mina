package com.meenai.mina.eval

import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.eval.AppCapabilities
import com.meenai.mina.core.eval.Check
import com.meenai.mina.core.eval.EvalCategory
import com.meenai.mina.core.eval.EvalPairing
import com.meenai.mina.core.eval.Checks
import com.meenai.mina.core.eval.EvalReport
import com.meenai.mina.core.record.RunMetrics
import com.meenai.mina.core.record.RunMetricsStore
import com.meenai.mina.core.eval.EvalTask
import com.meenai.mina.core.eval.EvalTasks
import com.meenai.mina.core.eval.Observed
import com.meenai.mina.core.eval.Prices
import com.meenai.mina.core.eval.RunFacts
import com.meenai.mina.core.eval.Scoring
import com.meenai.mina.core.eval.TaskScore
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.record.RunStore
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.task.TaskRun
import com.meenai.mina.task.TaskService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.meenai.mina.core.assembly.recordedTools
import com.meenai.mina.task.testTasks
import com.meenai.mina.core.cap.phone.OperatingPace

/**
 *Real machine evaluation:through [TaskService]The real path is to run the task set one question at a time.,Score and write a summary. Only runs when parameters are passed explicitly.
 *
 * `-e evalRun <name> [-e evalSet <>] [-e evalPriceIn <Input token unit price per million> -e evalPriceOut <Output unit price>
 *  [-e evalPriceCached <Cache unit price>]] [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true] [-e evalFlags none|<key=on,…>]
 *  [-e evalChannel web|app] [-e evalSplit tune|holdout]`
 *
 *The question comes from the category directory of the test package assets(storehouse eval/tasks/<category>/), web page title directory web and smoke title directory eval-smoke;If evalSet is not passed, all problems except smoke problems will be run.
 * evalSplitI was given the question of running only this pool.(by topic partition;Don’t pass and run all). The results of the holdout pool are only used for release decisions,Do not use it to adjust prompt words and knowledge.
 * evalChannelIf given, force the channel of each question to a web page or App(Add "use web page" before the goal/"Use App"),Compare the two routes.
 *The main model has been configured in the settings.(Use first LiveRunTest#configureProvidermatch).
 *The results are written to the App external file directory eval/<evalRun>/:summary.md、scores.jsonl,<question id>/run/(The complete running record of this question).
 *
 * evalFlags(default none)through [FeatureFlags.setSpeed]Take effect,Restore original value after running;Switch combinations are written into the summary.
 * evalPace(fast / standard / human,default fast)This is the rhythm of the operation in the App for this review.,Don’t change the settings;By default, the evaluation still runs fast.
 *
 * `-e evalVariants on`(Replay review,serial):Only run questions with variations;Run the original question first for each question(Once you're done, write down the route.),Then run each variant of it in sequence.
 *,Original title as a separate section;Variant runs are recorded in <Original title id>_v<n>/Down.
 *
 * `-e evalParallel <n>`(n ≥ 2,to open at the same time parallelTasks):Greedy grouping by "Apps used are disjoint"([EvalPairing]),Questions from the same group are run at the same time,
 *Sum up and add "total wall clock time". Questions in the same group share one automatic answer(Search for each question based on the keywords in the question answers)and upper limit of steps(Take the maximum in the group).
 *When a question cannot be completed because another question in the same group occupies the App,The reason for failure is recorded as [Scoring.INTERFERENCE_PREFIX](single category,Not a reviewer issue).
 *
 *Use new dialogue for each question in the assessment,in query mode(Turn off autonomous mode in developer options)Confirm that the level is effective,To confirm by [testTasks.evalHooks.autoDecideConfirmation]automatic answer
 * (When not connected, wait until timeout and press Disagree). Writing and irreversible questions will only be run when automatically rejected and all requirements are met.;Irreversible questions must be passed explicitly
 * `-e evalAllowIrreversible true`,Does not run by default.
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    private var savedFlags: SpeedFlags? = null

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        savedFlags?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        testTasks.evalHooks.onTakeover = onTakeover
        //Reviews will never be sent, deleted, or placed.:All checkpoints will be rejected
        testTasks.evalHooks.autoDecideConfirmation = { false }
        //By default, the evaluation does not learn skills or automatically memorize them.:The score you run is not affected by what you learned in the previous round.;Open it explicitly when testing
        testTasks.evalHooks.learnSkills = args.getString("learnSkills") == "on"
        testTasks.evalHooks.rememberMemory = args.getString("memory") == "on"
        testTasks.evalHooks.autoApproveSkillDrafts = args.getString("autoApproveSkills") == "true"
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("evalPace")) ?: OperatingPace.FAST
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val prices = prices()
        val flags = SpeedFlags.parse(args.getString("evalFlags") ?: "none")
        val featureFlags = FeatureFlags.get(context)
        savedFlags = featureFlags.speedFlags()
        featureFlags.setSpeed(flags)

        //Learning skills and memory status:Cleared by default,Round ③(Run with the skills learned in ②)pass keepState=true reserved
        if (args.getString("keepState") != "true") MemoryRuntime.of(context).wipeAll()
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        // evalChannel web|app:For the same batch of questions, it is mandatory to go to the web page or App(Comparison of two routes)
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        //Questions in the same session are run in the same conversation in order by ID.,So press (session, id)lined up
        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        DeviceSupport.shell()
        DeviceSupport.service()
        val model = ProviderStore.get(context).configFor(ModelRole.Main)?.model ?: error("no main model configured")
        val probes = EvalProbes(DeviceSupport::sh, File(ext, "eval/logged-in.txt"))
        val runs = RunStore(context)

        val parallel = args.getString("evalParallel")?.toInt() ?: 1
        require(parallel == 1 || flags.parallelTasks) { "evalParallel needs evalFlags parallelTasks=on" }
        val variants = args.getString("evalVariants") == "on"
        //The variation should use the route just noted in the original question,I can only run through one question after another
        require(!variants || parallel == 1) { "evalVariants runs serially; drop evalParallel" }
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val runnable = mutableListOf<EvalTask>()
        for (t in tasks) {
            //Will modify the equipment or encounter problems with irreversible operations:If the level is not connected, it will be automatically rejected and will not run.;Irreversible classes must be explicitly released
            if (t.category in GATED && testTasks.evalHooks.autoDecideConfirmation == null) {
                skipped += t.id to "The reviewer does not run ${t.category.wire()} type questions(Confirm that the level is automatically rejected and not connected.)"
                continue
            }
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "Irreversible questions are not run by default(To run and pass evalAllowIrreversible=true)"
                continue
            }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) {
                skipped += t.id to unmet.joinToString(";")
                continue
            }
            if (variants && t.variants.isEmpty()) {
                skipped += t.id to "Replay evaluation only runs questions with variations"
                continue
            }
            runnable += t
        }
        if (variants) {
            val originals = mutableListOf<Pair<TaskScore, Observed>>()
            for (t in runnable) {
                val (task, observed) = runOne(t, run, probes, runs, File(out, t.id))
                originals += Scoring.score(task, observed) to observed
                scoresFile.appendText(scoreLine(originals.last().first, observed) + "\n")
                for (v in EvalTasks.variantsOf(t)) {
                    val (vt, vo) = runOne(v, run, probes, runs, File(out, v.id.replace('~', '_')))
                    val score = Scoring.score(vt, vo)
                    scores += score to vo
                    scoresFile.appendText(scoreLine(score, vo) + "\n")
                }
            }
            File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), originals = originals))
            File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
            return
        }
        val t0 = System.currentTimeMillis()
        for (group in EvalPairing.groups(runnable, parallel)) {
            val results = if (parallel == 1) listOf(runOne(group.single(), run, probes, runs, File(out, group.single().id), sessionConversation(group.single())))
                else runGroup(group, run, probes, runs, out)
            for ((task, observed) in results) {
                val score = Scoring.score(task, observed)
                scores += score to observed
                scoresFile.appendText(scoreLine(score, observed) + "\n")
            }
        }
        val total = if (parallel > 1) System.currentTimeMillis() - t0 else null
        File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), total, parallel))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        //How many times have the learned skills been used?(For comparison report)
        val skillUse = scores.flatMap { it.second.skillsUsed }.groupingBy { it }.eachCount()
        File(out, "skill-use.json").writeText(JsonObject(skillUse.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /**Run a set of questions at the same time(Parallel review);Open a dialogue for each question,Keep an eye on the respective task objects until the end. */
    private fun runGroup(group: List<EvalTask>, run: String, probes: EvalProbes, runs: RunStore, out: File): List<Pair<EvalTask, Observed>> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val prepared = group.map { t ->
            val before = mutableMapOf<String, String>()
            var bad: String? = null
            for ((name, probe) in t.probesBefore) {
                val v = runCatching { probes.run(probe) }.getOrNull()
                if (v.isNullOrBlank() || v == "null") { bad = "probe $probe cannot read"; break }
                before[name] = v
            }
            if (bad != null) t to bad else EvalTasks.substitute(t, run, before) to null
        }
        val tasks = prepared.filter { it.second == null }.map { it.first }
        testTasks.evalHooks.autoAnswer = { prompt, _ -> tasks.firstNotNullOfOrNull { t -> t.answers.entries.firstOrNull { it.key in prompt }?.value } }
        testTasks.evalHooks.maxSteps = tasks.mapNotNull { it.maxSteps }.maxOrNull()
        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return group.map { it to harness("The previous set of tasks has never been completed") }

        class Live(val task: EvalTask, val conversation: String, var run: TaskRun? = null, var t0: Long = 0, var tookOver: Boolean = false,
                   var timedOut: Boolean = false, var end: TaskState? = null, var wallMs: Long = 0, var failed: String? = null)
        val live = tasks.map { Live(it, UUID.randomUUID().toString()) }
        for (l in live) {
            l.t0 = System.currentTimeMillis()
            TaskService.start(context, l.task.goal, l.conversation)
            if (!waitFor(START_WAIT_MS) { DeviceTaskState.run(l.conversation) != null }) l.failed = "The task did not start" else l.run = DeviceTaskState.run(l.conversation)
        }
        while (live.any { it.run != null && it.end == null && it.failed == null }) {
            for (l in live) {
                val r = l.run ?: continue
                if (l.end != null || l.failed != null) continue
                val s = r.state.value
                if (s is TaskState.WaitingTakeover && !l.tookOver) {
                    l.tookOver = true
                    if (testTasks.evalHooks.onTakeover == "cancel") r.cancel()
                }
                val timeoutMs = (l.task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
                val elapsed = System.currentTimeMillis() - l.t0
                if (!l.timedOut && elapsed > timeoutMs) { l.timedOut = true; r.cancel() }
                if (l.timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) l.failed = "The task has not been completed after timeout and cancellation."
                //Finishing done(Turn off running records)Just removed from the registry
                if (DeviceTaskState.run(l.conversation) == null) {
                    l.end = r.state.value
                    l.wallMs = System.currentTimeMillis() - l.t0
                }
            }
            Thread.sleep(POLL_MS)
        }
        val results = prepared.filter { it.second != null }.map { (t, why) -> t to harness(why!!) }.toMutableList()
        for (l in live) {
            if (l.failed != null) { results += l.task to harness(l.failed!!); continue }
            val record = l.run?.runId?.value?.let { id -> runs.list().firstOrNull { it.id == id } }
            val events = record?.let { runs.events(it.id) }.orEmpty()
            record?.let { runs.copyTo(it.id, File(File(out, l.task.id), "run")) }
            val task = resolveApps(l.task, events, probes)
            val observed = observe(task, l.end!!, events, record != null, l.timedOut, l.tookOver, l.wallMs, probes, record?.id.orEmpty())
            //Didn't succeed, and bumped into another question on the way App:Single rows interfere with each other
            val interfered = observed.outcome != "succeeded" && events.any { it is RunEvent.ToolResult && "app_in_use_by_other_task" in it.resultJson }
            results += task to if (interfered) observed.copy(failReason = Scoring.INTERFERENCE_PREFIX + "Another question in the same group occupies the space to be used App") else observed
        }
        return results
    }

    /**Questions in the same session share a dialogue(Memory questions:The second question is continued in the dialogue of the first question.). */
    private val sessions = mutableMapOf<String, String>()
    private fun sessionConversation(t: EvalTask): String? = t.session?.let { sessions.getOrPut(it) { UUID.randomUUID().toString() } }

    /**
     *Comparison and threshold of two rounds of evaluation:`-e evalCompareBase <Baseline run name> -e evalCompareTreated <Run name with skill>`,
     *read twice run scores.jsonl and the one with skills skill-use.json,Write compare.md to the directory with the skill.
     */
    @Test
    fun compareRuns() {
        val base = args.getString("evalCompareBase")
        val treated = args.getString("evalCompareTreated")
        assumeTrue(base != null && treated != null)
        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        fun read(run: String): List<Pair<TaskScore, Observed>> = File(ext, "eval/$run/scores.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TaskScore((o["id"] as JsonPrimitive).content, b("passed"), b("falseSuccess"), b("unverified"), emptyList(), null) to
                Observed((o["outcome"] as? JsonPrimitive)?.content.orEmpty(), "", emptyList(), null, emptyMap(), emptyMap(), l("steps").toInt(), l("wallMs"), 0, 0, 0, null)
        }
        val b = read(base!!)
        val t = read(treated!!)
        val use = runCatching { (Json.parseToJsonElement(File(ext, "eval/$treated/skill-use.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() } }
            .getOrDefault(emptyMap())
        File(ext, "eval/$treated/compare.md").writeText(
            "#contrast $base → $treated\n\n" + EvalReport.compare(b.map { it.first }, t.map { it.first }, use, b.map { it.second }, t.map { it.second }),
        )
    }

    /**Random question,Returns the question and observed facts with the placeholders replaced.[conversation]Continue running in this conversation when it is not empty. */
    private fun runOne(t: EvalTask, run: String, probes: EvalProbes, runs: RunStore, dir: File, conversation: String? = null): Pair<EvalTask, Observed> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("probe $probe cannot read")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        testTasks.evalHooks.autoAnswer = { prompt, _ -> task.answers.entries.firstOrNull { it.key in prompt }?.value }
        testTasks.evalHooks.maxSteps = task.maxSteps

        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return task to harness("The last mission has never been completed")
        val prevConversation = DeviceTaskState.conversationId.value
        val t0 = System.currentTimeMillis()
        TaskService.start(context, task.goal, conversation)
        //When a new conversation is opened, the number changes, indicating that the request has been accepted.;Then when the same conversation is running, the service becomes busy.
        val started = if (conversation == null) waitFor(START_WAIT_MS) { DeviceTaskState.conversationId.value != prevConversation }
            else waitFor(START_WAIT_MS) { DeviceTaskState.busy.value && DeviceTaskState.conversationId.value == conversation }
        if (!started) return task to harness("The task did not start")
        val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
        var tookOver = false
        var timedOut = false
        //Busy only changes back after the status changes to the end and the running record is turned off. false
        while (DeviceTaskState.busy.value) {
            val s = DeviceTaskState.state.value
            if (s is TaskState.WaitingTakeover && !tookOver) {
                tookOver = true
                if (testTasks.evalHooks.onTakeover == "cancel") TaskService.cancel(context)
            }
            if (!timedOut && System.currentTimeMillis() - t0 > timeoutMs) {
                timedOut = true
                TaskService.cancel(context)
            }
            if (timedOut && System.currentTimeMillis() - t0 > timeoutMs + BUSY_WAIT_MS) {
                return task to harness("The task has not been completed after timeout and cancellation.")
            }
            Thread.sleep(POLL_MS)
        }
        val wallMs = System.currentTimeMillis() - t0
        val end = DeviceTaskState.state.value

        //After handing over the answer, the ending will be determined.(Backstage):Wait until it is recorded in the running record before taking it out,There is one way to judge whether it is successful or not unknown;If you can’t wait, you won’t be judged.
        if (end is TaskState.Succeeded) waitFor(JUDGE_WAIT_MS) {
            runs.list().firstOrNull { it.startedAtMs >= t0 }?.judged != null
        }
        val record = runs.list().firstOrNull { it.startedAtMs >= t0 }
        val events = record?.let { runs.events(it.id) }.orEmpty()
        record?.let { runs.copyTo(it.id, File(dir, "run")) }
        val resolved = resolveApps(task, events, probes)
        return resolved to observe(resolved, end, events, record != null, timedOut, tookOver, wallMs, probes, record?.id.orEmpty())
    }

    /**After running `{app:<ability>}`Replace it with the package name on this phone:Candidates opened by Agent are given priority(See [AppCapabilities.choose]). */
    private fun resolveApps(task: EvalTask, events: List<RunEvent>, probes: EvalProbes): EvalTask {
        val launched = RunFacts.appsLaunched(events, recordedTools())
        val written = RunFacts.appsWrittenIn(events, recordedTools())
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), launched, context.packageName, written)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /**After a question is over:Organize observed facts by ending, running records, and post-end probes. */
    private fun observe(
        task: EvalTask, end: TaskState, events: List<RunEvent>, hasRecord: Boolean, timedOut: Boolean, tookOver: Boolean,
        wallMs: Long, probes: EvalProbes, runId: String,
    ): Observed {
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>()
        //stop at level:The last declaration was(Automatically in the evaluation)reject,Then the Agent report ends
        val stoppedAtGate = RunFacts.stoppedAtGate(events)
        val outcome = when {
            timedOut -> "timeout"
            stoppedAtGate -> "confirmation"
            //Reply without call is answer:Anything that has not been checked will still be counted. succeeded(Please refer to the answers and ledger for verification items.);Checked but failed to verify unverified
            end is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
            end is TaskState.Failed -> "failed"
            end == TaskState.Cancelled -> if (tookOver) "takeover" else "cancelled"
            else -> "timeout"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] =
                runCatching { runBlocking { probes.screenHas(c.packageName, c.text, c.deepLink) } }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] =
                runCatching { runBlocking { probes.webHas(c.url, c.text) } }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        val sources = RunFacts.evidence(events, recordedTools(), task.goal, answer)
        return Observed(
            outcome = outcome,
            answer = answer,
            //The same evidence ledger and the same challenge as the runtime verifier
            evidence = sources,
            today = java.time.LocalDate.now(),
            //Check the words shown on the card:Use the Chinese description in the card title for the action
            confirmation = confirmations.lastOrNull { it.decision != "mismatch" && it.decision != "required" }?.let { c ->
                val action = IrrevAction.entries.firstOrNull { it.name == c.action }?.let(ConfirmationCopy::actionLabel) ?: c.action
                listOfNotNull("action" to action, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap()
            },
            screenHas = screenHas,
            webHas = webHas,
            probes = after,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = wallMs,
            inputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.inputTokens ?: 0).toLong() },
            outputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.outputTokens ?: 0).toLong() },
            cachedTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.cachedTokens ?: 0).toLong() },
            //The failure reason is generated based on the failure source.,Original words without Agent
            leftScope = if (task.apps.isEmpty()) emptyList() else RunFacts.appsSeen(events, recordedTools()) - task.apps.toSet(),
            goal = task.goal,
            verdict = events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict,
            modelCalls = events.count { it is RunEvent.ModelReply },
            modelMs = events.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs },
            toolMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.durationMs },
            waitMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.waitMs },
            //A route is completed if the last step matches it;If there is no match at any step, it will be returned.
            replayHits = events.filterIsInstance<RunEvent.Replay>().count { it.matched && it.step == it.total },
            replayHandBacks = events.filterIsInstance<RunEvent.Replay>().count { !it.matched },
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            skillsUsed = events.filterIsInstance<RunEvent.SkillUsed>().map { it.name }.distinct(),
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, recordedTools()),
            judged = events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
            metrics = RunMetrics.of(events, runId),
            failReason = when {
                timedOut -> null
                end is TaskState.Failed -> Scoring.failReason(end)
                !hasRecord -> Scoring.HARNESS_PREFIX + "No record found for this run"
                else -> null
            },
        )
    }

    private fun harness(why: String) = Observed(
        "failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why,
    )

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /**All under test package assets .json title;[set]is a directory(like eval-smoke,read)or id prefix. */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        //Questions are only placed in the category directory(eval/tasks/<category>/), special question collection, web page question directory and smoke question directory;There are other things brought in by dependent libraries under the assets root. json
        val dirs = EvalCategory.entries.map { it.wire() } + SUITE_DIRS + WEB_DIR + SMOKE_DIR
        return dirs.flatMap(::walk)
            .filter { path -> set != null || (SUITE_DIRS + SMOKE_DIR).none { path.startsWith("$it/") } }
            .map { path ->
                val task = runCatching { EvalTasks.parse(assets.open(path).bufferedReader().use { it.readText() }) }
                    .getOrElse { throw IllegalStateException("cannot parse eval task $path", it) }
                path to task
            }
            .filter { (path, task) -> set == null || path.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    /**Category names used in task files and directories. */
    private fun EvalCategory.wire(): String = when (this) {
        EvalCategory.Read -> "read"
        EvalCategory.Write -> "write"
        EvalCategory.Irreversible -> "irreversible"
        EvalCategory.Impossible -> "impossible"
        EvalCategory.Barrier -> "barrier"
    }

    private fun scoreLine(s: TaskScore, o: Observed): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", s.id)
        put("passed", s.passed)
        put("falseSuccess", s.falseSuccess)
        put("unverified", s.unverified)
        put("failedChecks", JsonArray(s.failedChecks.map(::JsonPrimitive)))
        put("failureClass", s.failureClass?.name)
        put("outcome", o.outcome)
        put("judged", o.judged)
        put("answer", o.answer.take(2000))
        put("failReason", o.failReason)
        put("verdict", o.verdict)
        put("leftScope", JsonArray(s.leftScope.map(::JsonPrimitive)))
        put("steps", o.steps)
        put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens)
        put("outputTokens", o.outputTokens)
        put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls)
        put("modelMs", o.modelMs)
        put("toolMs", o.toolMs)
        put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", RunMetricsStore.JSON.encodeToJsonElement(RunMetrics.serializer(), it)) }
    })

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val SMOKE_DIR = "eval-smoke"
        /**Special question set:Matching questions(three wheel running), memory questions. Don’t run when evalSet is not passed,Select explicitly. */
        private val SUITE_DIRS = listOf("skills", "memory")
        /**Web page title directory(eval/tasks/web/),Categories vary. */
        private const val WEB_DIR = "web"
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 15_000L
        private const val BUSY_WAIT_MS = 60_000L
        /**There is a 30 second limit for the model that determines the outcome to call itself.,Once for each of the two candidate models. */
        private const val JUDGE_WAIT_MS = 65_000L
        private val GATED = setOf(EvalCategory.Write, EvalCategory.Irreversible)
    }
}
