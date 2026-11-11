package com.meenai.mina.ui

/*
  MinaFace add-on file.
  Use in the chat screen:
    Typing/working item : MinaRow(mood = MinaMood.Working) { WorkingLabel("Mina is working") }
    Thinking (before acting): MinaRow(mood = MinaMood.Thinking) { WorkingLabel("Thinking") }
    Normal reply        : MinaRow(mood = if (isNew) MinaMood.Success else MinaMood.Idle) { MinaText(...) }
    Failed reply        : MinaRow(mood = MinaMood.Failed) { MinaText("I couldn't finish that...", isNew) }
    Unsure / need info  : MinaRow(mood = MinaMood.Confused) { MinaText(...) }
    Add a UI field to MinaMsg if you want:  val mood: MinaMood = MinaMood.Idle
  UI only. Respects system "Remove animations".
*/

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class MinaMood { Idle, Thinking, Working, Success, Failed, Confused, Sleepy }

private val FaceInk = Color(0xFF111114)
private val Drop = Color(0xFF7FC8F8)

private data class Expr(
    val lookX: Float = 0f, val lookY: Float = 0f,     // -1..1
    val openL: Float = 1f, val openR: Float = 1f,     // 0..1
    val tilt: Float = 0f,                             // face rotation deg
    val squash: Float = 1f,                           // eye height; < 0.4 draws happy ^ ^ arcs
    val slant: Float = 0f,                            // + = sad (inner corners up), - = angry
    val mouth: Float = 0f,                            // -1 frown .. 0 none .. 1 big smile
    val sweat: Float = 0f,                            // 0..1 sweat drop
)

private val Cycles: Map<MinaMood, List<Expr>> = mapOf(
    MinaMood.Idle to listOf(Expr(mouth = 0.6f), Expr(lookX = 0.3f, mouth = 0.6f), Expr(lookX = -0.3f, mouth = 0.6f)),
    MinaMood.Thinking to listOf(
        Expr(lookX = 0.7f, lookY = -0.8f, openL = 0.7f, openR = 0.7f, tilt = 7f),   // looking up, pondering
        Expr(lookX = -0.6f, lookY = -0.7f, openL = 0.5f, tilt = -5f),               // one eye squint, "hmm"
        Expr(lookY = -0.9f, squash = 0.85f),                                        // eyes up
    ),
    MinaMood.Working to listOf(
        Expr(lookX = -0.9f), Expr(lookX = 0.9f),                                    // scanning
        Expr(squash = 0.55f, lookY = 0.4f),                                         // focused, reading
        Expr(lookX = 0.5f, lookY = -0.5f, tilt = 5f),
        Expr(squash = 1.15f),                                                       // got it, wide eyes
    ),
    MinaMood.Success to listOf(Expr(squash = 0.3f, mouth = 1f, tilt = -4f), Expr(squash = 0.3f, mouth = 1f, tilt = 4f)),
    MinaMood.Failed to listOf(
        Expr(lookY = 0.7f, squash = 0.8f, slant = 1f, mouth = -0.8f, sweat = 1f, tilt = -6f),
        Expr(lookY = 0.8f, lookX = -0.4f, squash = 0.75f, slant = 1f, mouth = -0.9f, sweat = 1f, openL = 0.8f, openR = 0.8f),
    ),
    MinaMood.Confused to listOf(
        Expr(openL = 1f, openR = 0.55f, tilt = 10f, lookX = 0.4f, mouth = -0.2f),
        Expr(openL = 0.55f, openR = 1f, tilt = -10f, lookX = -0.4f, mouth = -0.2f),
    ),
    MinaMood.Sleepy to listOf(Expr(openL = 0.2f, openR = 0.2f, lookY = 0.5f, tilt = 8f), Expr(openL = 0.1f, openR = 0.1f, lookY = 0.6f, tilt = 10f)),
)

@Composable
fun MinaFace(mood: MinaMood, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val noMotion = MinaTheme.reduceMotion
    val soft = spring<Float>(dampingRatio = 0.7f, stiffness = 140f)

    val lookX = remember { Animatable(0f) }; val lookY = remember { Animatable(0f) }
    val openL = remember { Animatable(1f) }; val openR = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }; val squash = remember { Animatable(1f) }
    val slant = remember { Animatable(0f) }; val mouth = remember { Animatable(0f) }
    val sweat = remember { Animatable(0f) }; val pop = remember { Animatable(1f) }
    val shakeX = remember { Animatable(0f) }

    suspend fun go(e: Expr) = coroutineScope {
        val pairs = listOf(lookX to e.lookX, lookY to e.lookY, openL to e.openL, openR to e.openR,
            tilt to e.tilt, squash to e.squash, slant to e.slant, mouth to e.mouth, sweat to e.sweat)
        pairs.forEach { (a, v) -> launch { if (noMotion) a.snapTo(v) else a.animateTo(v, soft) } }
    }
    suspend fun blink() {
        if (noMotion) return
        val l = openL.value; val r = openR.value
        coroutineScope {
            launch { openL.animateTo(0.06f, tween(70)); openL.animateTo(l, tween(110)) }
            launch { openR.animateTo(0.06f, tween(70)); openR.animateTo(r, tween(110)) }
        }
    }

    LaunchedEffect(mood) {
        val cycle = Cycles.getValue(mood)
        // one-time entry gesture per mood
        if (!noMotion) when (mood) {
            MinaMood.Success -> { go(cycle[0]); pop.animateTo(1.14f, spring(0.45f, 500f)); pop.animateTo(1f, spring(0.6f, 300f)) }
            MinaMood.Failed -> {                           // little "no" head shake, then droop
                go(cycle[0])
                repeat(3) { shakeX.animateTo(3f, tween(60)); shakeX.animateTo(-3f, tween(60)) }
                shakeX.animateTo(0f, spring(0.5f, 400f))
            }
            MinaMood.Confused -> { tilt.animateTo(14f, spring(0.4f, 300f)) }
            else -> {}
        }
        var i = 0
        while (true) {
            go(cycle[i % cycle.size])
            val wait = when (mood) {
                MinaMood.Working -> Random.nextLong(800, 1400)
                MinaMood.Thinking -> Random.nextLong(1400, 2200)   // slower = thoughtful
                MinaMood.Sleepy -> 2600L
                else -> Random.nextLong(2500, 4500)
            }
            delay(wait)
            if (mood != MinaMood.Sleepy && Random.nextFloat() < 0.5f) blink()
            i++
        }
    }

    // gentle float; faster while working, slow sway when sad/sleepy
    val floatAmp = when (mood) { MinaMood.Working, MinaMood.Thinking -> 1.6f; MinaMood.Failed, MinaMood.Sleepy -> 0.8f; else -> 0f }
    val floatY = if (!noMotion && floatAmp > 0f) {
        val t = rememberInfiniteTransition(label = "float")
        t.animateFloat(-floatAmp, floatAmp, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y").value
    } else 0f

    // only eyes while thinking/working; white face otherwise
    val eyesOnly = mood == MinaMood.Working || mood == MinaMood.Thinking
    val bgAlpha by animateFloatAsState(if (eyesOnly) 0f else 1f, tween(320), label = "bg")
    val shape = RoundedCornerShape(size * 0.3f)

    Box(
        modifier.size(size)
            .graphicsLayer {
                rotationZ = tilt.value
                translationY = floatY.dp.toPx(); translationX = shakeX.value.dp.toPx()
                scaleX = pop.value; scaleY = pop.value
            }
            .shadow((4 * bgAlpha).dp, shape)
            .clip(shape)
            .background(Color.White.copy(alpha = bgAlpha))
            .border(1.dp, Color.White.copy(alpha = 0.9f * bgAlpha), shape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawFace(lookX.value, lookY.value, openL.value, openR.value, squash.value, slant.value, mouth.value, sweat.value)
        }
    }
}

private fun DrawScope.drawFace(
    lx: Float, ly: Float, oL: Float, oR: Float, sq: Float, slant: Float, mouth: Float, sweat: Float,
) {
    val w = size.width; val h = size.height
    val eyeW = w * 0.15f
    val eyeH = h * 0.30f * sq.coerceAtLeast(0.3f)
    val gap = w * 0.13f
    val cx = w / 2 + lx * w * 0.08f
    val cy = h * 0.44f + ly * h * 0.07f
    val stroke = w * 0.055f

    fun eye(x: Float, open: Float, left: Boolean) {
        if (sq < 0.4f) { // happy ^ ^ eyes
            val p = Path().apply {
                moveTo(x - eyeW * 0.8f, cy + eyeW * 0.3f)
                quadraticBezierTo(x, cy - eyeW * 1.1f, x + eyeW * 0.8f, cy + eyeW * 0.3f)
            }
            drawPath(p, FaceInk, style = Stroke(stroke, cap = StrokeCap.Round))
            return
        }
        val eh = (eyeH * open).coerceAtLeast(h * 0.03f)
        val angle = (if (left) -1 else 1) * slant * 18f   // sad droop / angry
        rotate(angle, Offset(x, cy)) {
            drawRoundRect(FaceInk, Offset(x - eyeW / 2, cy - eh / 2), Size(eyeW, eh), CornerRadius(eyeW / 2, eyeW / 2))
        }
    }
    eye(cx - gap - eyeW / 2, oL, true)
    eye(cx + gap + eyeW / 2, oR, false)

    // mouth: smile (mouth > 0) or frown (mouth < 0)
    if (kotlin.math.abs(mouth) > 0.05f) {
        val m = kotlin.math.abs(mouth)
        val mw = w * (0.12f + 0.10f * m)
        val my = cy + h * 0.15f + h * 0.08f
        val curve = h * 0.08f * m * (if (mouth > 0) 1 else -1)
        val p = Path().apply {
            moveTo(cx - mw / 2, my)
            quadraticBezierTo(cx, my + curve * 2, cx + mw / 2, my)
        }
        drawPath(p, FaceInk.copy(alpha = m.coerceIn(0f, 1f)), style = Stroke(stroke * 0.9f, cap = StrokeCap.Round))
    }

    // sweat drop (failed)
    if (sweat > 0.05f) {
        val dx = w * 0.80f; val dy = h * 0.22f + (1f - sweat) * h * 0.08f
        val r = w * 0.06f
        val p = Path().apply {
            moveTo(dx, dy - r * 1.8f)
            quadraticBezierTo(dx + r * 1.2f, dy, dx, dy + r)
            quadraticBezierTo(dx - r * 1.2f, dy, dx, dy - r * 1.8f)
        }
        drawPath(p, Drop.copy(alpha = sweat))
    }
}

@Composable
fun MinaRow(mood: MinaMood, showFace: Boolean = true, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(42.dp).padding(top = 2.dp)) { if (showFace) MinaFace(mood) }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun WorkingLabel(text: String = "Mina is working") {
    val t = rememberInfiniteTransition(label = "label")
    val a by t.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Text(text, color = FaceInk.copy(alpha = a), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
}
