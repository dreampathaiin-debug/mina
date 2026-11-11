package com.meenai.mina.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.CircleIconButton
import com.meenai.mina.ui.components.GlassBaseDark
import com.meenai.mina.ui.components.GlassBaseLight
import com.meenai.mina.ui.components.LocalHazeState
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.ProgressDots
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/** 上手第二步:选 Mina Cloud 还是自带 API key。纯界面选择,不登录、不写任何配置。 */
enum class ModelChoice { MinaCloud, BringYourOwnKey }

/** 展示的服务商:名字 + 官方 logo。monochrome 的用主题墨色重着色,彩色的保留原色。 */
private data class Provider(val name: String, val res: Int, val mono: Boolean)

private val PROVIDERS = listOf(
    Provider("OpenAI", R.drawable.logo_openai, true),
    Provider("Google Gemini", R.drawable.logo_gemini, false),
    Provider("Anthropic Claude", R.drawable.logo_claude, false),
    Provider("DeepSeek", R.drawable.logo_deepseek, false),
    Provider("xAI Grok", R.drawable.logo_grok, true),
    Provider("Mistral", R.drawable.logo_mistral, false),
    Provider("Kimi", R.drawable.logo_kimi, true),
    Provider("MiniMax", R.drawable.logo_minimax, true),
    Provider("OpenRouter", R.drawable.logo_openrouter, true),
    Provider("Groq", R.drawable.logo_groq, true),
)

private val MintPillBg = Color(0xFFD6F5E3)
private val MintPillInk = Color(0xFF1E7A4C)
private val SelectInk = Color(0xFF1C1C1E)

/** 屏幕在前台时才为真;所有循环动画都看它,退到后台就停。 */
@Composable
private fun rememberScreenActive(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val active = remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active.value = true
                Lifecycle.Event.ON_PAUSE -> active.value = false
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return active
}

/** 入场:淡入 + 上移 20dp 回落,按 [index] 60ms 依次错开;系统关动画时直接出现。 */
@Composable
private fun Entrance(index: Int, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val t = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            delay(index * 60L)
            t.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 300f))
        } else t.snapTo(1f)
    }
    val dp20 = 20.dp
    Box(
        Modifier.graphicsLayer {
            alpha = t.value.coerceIn(0f, 1f)
            translationY = (1f - t.value) * dp20.toPx()
        },
    ) { content() }
}

@Composable
fun ChooseModelScreen(onBack: () -> Unit, onContinue: (ModelChoice) -> Unit) {
    val c = MinaTheme.colors
    val reduceMotion = MinaTheme.reduceMotion
    var choice by rememberSaveable { mutableStateOf(ModelChoice.MinaCloud) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val active = rememberScreenActive()
    val haptic = LocalHapticFeedback.current

    fun select(v: ModelChoice) {
        if (v != choice) {
            choice = v
            touched = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    PageBackground {
        Box(Modifier.fillMaxSize()) {
            AmbientDecor(Modifier.matchParentSize(), animate = active.value && !reduceMotion)
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp)) {
                    CircleIconButton(MinaIcons.Back, stringResource(R.string.back), onBack)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(6.dp))
                    Entrance(0, reduceMotion) { MinaMascot(choice, touched, active.value, reduceMotion) }
                    Spacer(Modifier.height(14.dp))
                    Entrance(1, reduceMotion) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                tr(R.string.choose_title),
                                style = MinaType.LargeTitle, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                                color = c.ink, textAlign = TextAlign.Center,
                            )
                            Text(
                                tr(R.string.choose_subtitle),
                                Modifier.padding(top = 8.dp),
                                fontSize = 14.5.sp, lineHeight = 21.sp, color = c.ink2, textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Entrance(2, reduceMotion) {
                        MinaCloudCard(selected = choice == ModelChoice.MinaCloud, onSelect = { select(ModelChoice.MinaCloud) })
                    }
                    Spacer(Modifier.height(16.dp))
                    Entrance(3, reduceMotion) {
                        ByokCard(selected = choice == ModelChoice.BringYourOwnKey, onSelect = { select(ModelChoice.BringYourOwnKey) })
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Choose), Modifier.padding(bottom = 10.dp))
                    Entrance(4, reduceMotion) {
                        Column(Modifier.fillMaxWidth()) {
                            ContinueButton(choice) { onContinue(choice) }
                            TermsLine(Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------- 背景装饰:两团柔光 + 三颗星星 ----------

@Composable
private fun AmbientDecor(modifier: Modifier, animate: Boolean) {
    val orb1 = remember { Animatable(if (animate) 0.5f else 0.5f) }
    val orb2 = remember { Animatable(if (animate) 0.5f else 0.5f) }
    val tw = List(3) { remember { Animatable(0.55f) } }
    LaunchedEffect(animate) {
        if (!animate) {
            orb1.snapTo(0.5f); orb2.snapTo(0.5f); tw.forEach { it.snapTo(0.55f) }
            return@LaunchedEffect
        }
        suspend fun drift(a: Animatable<Float, *>, period: Int, phase: Float) {
            while (true) {
                delay(phase.toLong())
                a.animateTo(1f, tween(period, easing = FastOutSlowInEasing))
                a.animateTo(0f, tween(period, easing = FastOutSlowInEasing))
            }
        }
        suspend fun twink(a: Animatable<Float, *>, period: Int, phase: Long) {
            while (true) {
                delay(phase)
                a.animateTo(0.8f, tween(period, easing = FastOutSlowInEasing))
                a.animateTo(0.3f, tween(period, easing = FastOutSlowInEasing))
            }
        }
        coroutineScope {
            launch { drift(orb1, 5_500, 0f) }
            launch { drift(orb2, 6_000, 1_200f) }
            launch { twink(tw[0], 1_250, 0L) }
            launch { twink(tw[1], 1_600, 700L) }
            launch { twink(tw[2], 2_000, 1_400L) }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val drift1 = (orb1.value - 0.5f) * 24f
        val drift2 = (orb2.value - 0.5f) * 24f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f)),
                center = Offset(w * 0.24f + drift1, h * 0.24f),
                radius = w * 0.55f,
            ),
            center = Offset(w * 0.24f + drift1, h * 0.24f),
            radius = w * 0.55f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFCBD9F0).copy(alpha = 0.12f), Color(0xFFCBD9F0).copy(alpha = 0f)),
                center = Offset(w * 0.86f + drift2, h * 0.74f),
                radius = w * 0.5f,
            ),
            center = Offset(w * 0.86f + drift2, h * 0.74f),
            radius = w * 0.5f,
        )
        val stars = listOf(
            Triple(0.14f, 0.34f, tw[0].value),
            Triple(0.82f, 0.20f, tw[1].value),
            Triple(0.68f, 0.58f, tw[2].value),
        )
        for ((fx, fy, alpha) in stars) {
            val cx = w * fx
            val cy = h * fy
            val r = 5.dp.toPx()
            drawLine(Color.White.copy(alpha = alpha), Offset(cx - r, cy), Offset(cx + r, cy), 1.4.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = alpha), Offset(cx, cy - r), Offset(cx, cy + r), 1.4.dp.toPx(), StrokeCap.Round)
        }
    }
}

// ---------- 吉祥物:会动的 Mina 脸 ----------

@Composable
private fun MinaMascot(choice: ModelChoice, touched: Boolean, active: Boolean, reduceMotion: Boolean) {
    // 表情状态:0=平常 1=眨眼 2=眨单眼 3=开心 4=看向旁边
    var expr by remember { mutableIntStateOf(0) }
    var lookDir by remember { mutableFloatStateOf(1f) }
    val blink = remember { Animatable(0f) }
    val wink = remember { Animatable(0f) }
    val happy = remember { Animatable(0f) }
    val look = remember { Animatable(0f) }
    val float = remember { Animatable(0f) }
    val breath = remember { Animatable(0f) }
    val bounce = remember { Animatable(0f) }
    val first = remember { mutableStateOf(true) }

    LaunchedEffect(active, reduceMotion) {
        if (!active || reduceMotion) {
            float.snapTo(0f); breath.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                while (true) {
                    float.animateTo(1f, tween(1_750, easing = FastOutSlowInEasing))
                    float.animateTo(0f, tween(1_750, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (true) {
                    breath.animateTo(1f, tween(1_750, easing = FastOutSlowInEasing))
                    breath.animateTo(0f, tween(1_750, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    LaunchedEffect(active, reduceMotion) {
        if (!active || reduceMotion) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2_500, 4_000))
            expr = Random.nextInt(1, 5)
            when (expr) {
                1 -> { blink.snapTo(0f); blink.animateTo(1f, tween(80)); blink.animateTo(0f, tween(80)) }
                2 -> { wink.snapTo(0f); wink.animateTo(1f, tween(90)); wink.animateTo(0f, tween(90)) }
                3 -> { happy.snapTo(0f); happy.animateTo(1f, tween(180)); delay(240); happy.animateTo(0f, tween(180)) }
                else -> {
                    lookDir = if (Random.nextBoolean()) 1f else -1f
                    look.animateTo(lookDir, tween(200, easing = FastOutSlowInEasing))
                    delay(600)
                    look.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                }
            }
            expr = 0
        }
    }
    // 选了别的卡片:Mina 快乐地弹一下,眼睛看向那一边
    LaunchedEffect(choice) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        if (reduceMotion) return@LaunchedEffect
        bounce.snapTo(0f)
        bounce.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        bounce.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 380f))
    }
    val gazeBias by animateFloatAsState(
        if (choice == ModelChoice.MinaCloud) -1f else 1f,
        if (reduceMotion) snapSpec() else spring(dampingRatio = 0.85f, stiffness = 300f),
        label = "gaze",
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
        val bob = 4.dp * float.value
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer { translationY = bob.toPx(); scaleX = 1f + 0.08f * bounce.value; scaleY = 1f + 0.08f * bounce.value },
        ) {
            // 身后的暖色光环,轻轻呼吸
            Box(
                Modifier.matchParentSize().graphicsLayer {
                    scaleX = 1f + 0.06f * breath.value; scaleY = 1f + 0.06f * breath.value
                }.drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFFFFE3B8).copy(alpha = 0.5f), Color(0xFFFFE3B8).copy(alpha = 0f)),
                            radius = size.minDimension * 0.82f,
                        ),
                        radius = size.minDimension * 0.82f,
                    )
                },
            )
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
                drawRoundRect(Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.25f))
                drawMinaFace(
                    blink = blink.value, wink = wink.value, happy = happy.value,
                    look = look.value * 0.03f + gazeBias * 0.03f,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Bubble(
            text = when {
                !touched -> tr(R.string.choose_bubble_pick)
                choice == ModelChoice.MinaCloud -> tr(R.string.choose_bubble_free)
                else -> tr(R.string.choose_bubble_key)
            },
            modifier = Modifier.padding(top = 8.dp),
            reduceMotion = reduceMotion,
        )
    }
}

@Composable
private fun Bubble(text: String, modifier: Modifier = Modifier, reduceMotion: Boolean) {
    val c = MinaTheme.colors
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) fadeIn(snapSpec()) togetherWith fadeOut(snapSpec())
            else fadeIn(tween(220)) togetherWith fadeOut(tween(220))
        },
        label = "bubble",
    ) { t ->
        Box(
            Modifier
                .background(c.glass.copy(alpha = if (c.isDark) 0.55f else 0.75f), RoundedCornerShape(50))
                .border(1.dp, c.cardEdge, RoundedCornerShape(50))
                .padding(horizontal = 11.dp, vertical = 5.dp),
        ) {
            Text(t, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        }
    }
}

/** 画脸:两颗黑椭圆眼睛 + 微笑;[happy] 时眼睛变弧线、笑更宽。 */
private fun DrawScope.drawMinaFace(blink: Float, wink: Float, happy: Float, look: Float) {
    val s = size.minDimension
    val eyeCy = 0.44f * s
    val eyeGap = 0.115f * s
    val eyeRx = 0.052f * s
    val eyeRy = 0.082f * s
    val ink = Color(0xFF161616)
    val closedL = blink.coerceAtLeast(0f)
    val closedR = if (wink > 0.5f) 0.9f else blink
    val eyesHappy = happy > 0.5f
    for (side in listOf(-1f, 1f)) {
        val closed = if (side < 0) closedL else closedR
        val cx = 0.5f * s + side * eyeGap + look * s
        if (eyesHappy) {
            // 开心:眼睛变成小弧线
            drawArc(
                ink, 180f, 180f, false,
                topLeft = Offset(cx - eyeRx, eyeCy - eyeRy * 0.5f),
                size = Size(eyeRx * 2, eyeRy * 1.2f),
                style = Stroke(width = 0.035f * s, cap = StrokeCap.Round),
            )
        } else {
            val ry = eyeRy * (1f - 0.9f * closed)
            drawOval(ink, topLeft = Offset(cx - eyeRx, eyeCy - ry), size = Size(eyeRx * 2, ry * 2))
        }
    }
    // 微笑:开心时更宽
    val mR = (0.14f + 0.03f * happy) * s
    drawArc(
        ink, 20f, 140f + 30f * happy, false,
        topLeft = Offset(0.5f * s - mR, 0.565f * s - mR),
        size = Size(mR * 2, mR * 2),
        style = Stroke(width = 0.032f * s, cap = StrokeCap.Round),
    )
}

// ---------- 卡片 ----------

@Composable
private fun GlassCard(
    selected: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    cd: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MinaTheme.colors
    val haze = LocalHazeState.current
    val glassBase = if (c.isDark) GlassBaseDark else GlassBaseLight
    val glassStyle = remember(c) {
        HazeStyle(
            backgroundColor = glassBase,
            tint = if (c.isDark) HazeTint(c.surface) else HazeTint(Color.White.copy(alpha = 0.70f)),
            blurRadius = 28.dp,
        )
    }
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressTarget = when {
        pressed -> 0.97f
        selected -> 1.02f
        else -> 0.98f
    }
    val scale by animateFloatAsState(
        pressTarget,
        if (reduceMotion) snapSpec() else spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "cardScale",
    )
    val borderWidth by animateDpAsState(
        if (selected) 1.5.dp else 0.dp,
        if (reduceMotion) snapSpec() else spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "cardBorder",
    )
    val elev by animateDpAsState(
        if (selected) 10.dp else 0.dp,
        if (reduceMotion) snapSpec() else spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "cardShadow",
    )
    val contentAlpha by animateFloatAsState(
        if (selected) 1f else 0.7f,
        if (reduceMotion) snapSpec() else tween(200),
        label = "cardAlpha",
    )
    val checkScale by animateFloatAsState(
        if (selected) 1f else 0f,
        if (reduceMotion) snapSpec() else spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "check",
    )
    // 选中瞬间:一道柔光从左到右扫过顶边,只扫一次
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected && !reduceMotion) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            sweep.snapTo(0f)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elev, shape, clip = false, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
            .then(if (haze != null && com.meenai.mina.ui.components.GlassBlurSupported) Modifier.hazeChild(haze, shape = shape, style = glassStyle) else Modifier)
            .background(if (c.isDark) c.surface else Color.White.copy(alpha = 0.70f), shape)
            .border(borderWidth, if (selected) SelectInk else Color.Transparent, shape)
            .drawBehind {
                // 顶边 1dp 白色高光
                drawRoundRect(
                    Color.White.copy(alpha = 0.75f),
                    topLeft = Offset(size.width * 0.06f, 0f),
                    size = Size(size.width * 0.88f, 1.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                )
                if (sweep.value > 0f && sweep.value < 1f) {
                    val band = size.width * 0.25f
                    val x = (sweep.value * (size.width + band)) - band
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            startX = x, endX = x + band,
                        ),
                        topLeft = Offset(x, 0f),
                        size = Size(band, 3.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                contentDescription = cd
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp).graphicsLayer { alpha = contentAlpha }, content = content)
        // 选中标记:黑色小圆 + 白勾,右上角
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(22.dp)
                .graphicsLayer { scaleX = checkScale; scaleY = checkScale }
                .background(SelectInk, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MinaIcons.Check, null, Modifier.size(13.dp), tint = Color.White)
        }
    }
}

@Composable
private fun MinaCloudCard(selected: Boolean, onSelect: () -> Unit) {
    val c = MinaTheme.colors
    val reduceMotion = MinaTheme.reduceMotion
    val active = rememberScreenActive()
    GlassCard(selected, reduceMotion, onSelect, cd = tr(R.string.choose_cd_cloud)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CloudMinaIcon(active.value && !reduceMotion, Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.choose_mina_cloud), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Spacer(Modifier.width(8.dp))
                    FreePill(active.value && !reduceMotion)
                }
                Text(
                    tr(R.string.choose_cloud_desc),
                    Modifier.padding(top = 3.dp),
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = c.ink2,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CheckRow(tr(R.string.choose_free_actions))
        CheckRow(tr(R.string.choose_works_right_away))
        CheckRow(tr(R.string.choose_plans))
    }
}

@Composable
private fun CheckRow(text: String) {
    val c = MinaTheme.colors
    Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MinaIcons.Check, null, Modifier.size(15.dp), tint = c.ink)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.5.sp, lineHeight = 18.sp, color = c.ink2)
    }
}

@Composable
private fun FreePill(animate: Boolean) {
    val shimmer = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) { shimmer.snapTo(1f); return@LaunchedEffect }
        while (true) {
            delay(4_000)
            shimmer.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
            shimmer.snapTo(0f)
        }
    }
    Box(
        Modifier
            .background(MintPillBg, RoundedCornerShape(50))
            .drawBehind {
                if (shimmer.value in 0.01f..0.99f) {
                    val band = size.width * 0.5f
                    val x = (shimmer.value * (size.width + band)) - band
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                            startX = x, endX = x + band,
                        ),
                        topLeft = Offset(x, 0f),
                        size = Size(band, size.height),
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 2.5.dp),
    ) {
        Text("FREE", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MintPillInk, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun ByokCard(selected: Boolean, onSelect: () -> Unit) {
    val c = MinaTheme.colors
    val reduceMotion = MinaTheme.reduceMotion
    val active = rememberScreenActive()
    GlassCard(selected, reduceMotion, onSelect, cd = tr(R.string.choose_cd_byok)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyIcon(active.value && !reduceMotion, Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.choose_byok), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(
                    tr(R.string.choose_byok_desc),
                    Modifier.padding(top = 3.dp),
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = c.ink2,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            tr(R.string.choose_works_with),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(8.dp))
        ProviderShowcase(active.value && !reduceMotion, reduceMotion)
    }
}

// ---------- 卡片图标:云上的小 Mina、带眼睛的钥匙 ----------

@Composable
private fun CloudMinaIcon(animate: Boolean, modifier: Modifier = Modifier) {
    val bob = remember { Animatable(if (animate) 0.5f else 0.5f) }
    val drift = remember { Animatable(if (animate) 0.5f else 0.5f) }
    LaunchedEffect(animate) {
        if (!animate) { bob.snapTo(0.5f); drift.snapTo(0.5f); return@LaunchedEffect }
        coroutineScope {
            launch {
                while (true) {
                    bob.animateTo(0f, tween(1_200, easing = FastOutSlowInEasing))
                    bob.animateTo(1f, tween(1_200, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (true) {
                    drift.animateTo(0f, tween(2_800, easing = FastOutSlowInEasing))
                    drift.animateTo(1f, tween(2_800, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    Canvas(modifier) {
        val s = size.minDimension
        val cloudDx = (drift.value - 0.5f) * 0.07f * s
        val bobDy = (bob.value - 0.5f) * 0.055f * s
        // 云:三个圆 + 圆角矩形拼成
        val cloud = Path().apply {
            val cy = 0.66f * s
            addOval(Rect(Offset(0.16f * s + cloudDx, cy - 0.20f * s), Size(0.30f * s, 0.30f * s)))
            addOval(Rect(Offset(0.38f * s + cloudDx, cy - 0.30f * s), Size(0.34f * s, 0.34f * s)))
            addOval(Rect(Offset(0.60f * s + cloudDx, cy - 0.22f * s), Size(0.30f * s, 0.30f * s)))
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = Rect(Offset(0.10f * s + cloudDx, cy), Size(0.80f * s, 0.20f * s)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.10f * s),
                ),
            )
        }
        drawPath(cloud, Color.White.copy(alpha = 0.95f))
        // 云上的小 Mina 脸(白圆角方块 + 眼睛 + 笑)
        val fx = 0.42f * s + cloudDx
        val fy = 0.10f * s + bobDy
        val fs = 0.34f * s
        drawRoundRect(
            Color.White,
            topLeft = Offset(fx, fy),
            size = Size(fs, fs),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(fs * 0.28f),
        )
        drawRoundRect(
            Color(0x14000000),
            topLeft = Offset(fx, fy),
            size = Size(fs, fs),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(fs * 0.28f),
            style = Stroke(width = 0.015f * s),
        )
        drawMiniFace(fx + fs * 0.5f, fy + fs * 0.48f, fs)
    }
}

private fun DrawScope.drawMiniFace(cx: Float, cy: Float, s: Float) {
    val ink = Color(0xFF161616)
    val eyeR = 0.055f * s
    drawOval(ink, topLeft = Offset(cx - 0.17f * s - eyeR, cy - 0.10f * s - eyeR * 1.5f), size = Size(eyeR * 2, eyeR * 3))
    drawOval(ink, topLeft = Offset(cx + 0.17f * s - eyeR, cy - 0.10f * s - eyeR * 1.5f), size = Size(eyeR * 2, eyeR * 3))
    drawArc(
        ink, 20f, 140f, false,
        topLeft = Offset(cx - 0.12f * s, cy + 0.04f * s),
        size = Size(0.24f * s, 0.24f * s),
        style = Stroke(width = 0.03f * s, cap = StrokeCap.Round),
    )
}

@Composable
private fun KeyIcon(animate: Boolean, modifier: Modifier = Modifier) {
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) { tilt.snapTo(0f); return@LaunchedEffect }
        while (true) {
            tilt.animateTo(-1f, tween(1_500, easing = FastOutSlowInEasing))
            tilt.animateTo(1f, tween(1_500, easing = FastOutSlowInEasing))
        }
    }
    val c = MinaTheme.colors
    Canvas(modifier) {
        rotate(degrees = 4f * tilt.value, pivot = center) {
            val s = size.minDimension
            val ink = c.ink
            val head = Offset(0.34f * s, 0.32f * s)
            // 钥匙头圆环 + 柄 + 两颗齿
            drawCircle(ink, radius = 0.16f * s, center = head, style = Stroke(width = 0.055f * s, cap = StrokeCap.Round))
            val dir = Offset(0.75f, 0.75f).getNormalized()
            drawLine(ink, head + dir * 0.10f * s, head + dir * 0.46f * s, 0.05f * s, StrokeCap.Round)
            val tip = head + dir * 0.46f * s
            drawLine(ink, tip, tip + Offset(-0.13f, 0.13f).getNormalized() * 0.13f * s, 0.05f * s, StrokeCap.Round)
            drawLine(ink, head + dir * 0.30f * s, head + dir * 0.30f * s + Offset(-0.11f, 0.11f).getNormalized() * 0.10f * s, 0.05f * s, StrokeCap.Round)
            // 头上的两只小眼睛
            val eyeR = 0.028f * s
            for (side in listOf(-1f, 1f)) {
                drawOval(
                    ink,
                    topLeft = Offset(head.x + side * 0.055f * s - eyeR, head.y - eyeR * 1.4f),
                    size = Size(eyeR * 2, eyeR * 2.6f),
                )
            }
        }
    }
}

private fun Offset.getNormalized(): Offset {
    val len = kotlin.math.hypot(x.toDouble(), y.toDouble())
    return if (len == 0.0) Offset(0f, 0f) else Offset((x / len).toFloat(), (y / len).toFloat())
}

// ---------- 服务商展示:自动轮换 + 左右滑动 ----------

@Composable
private fun ProviderShowcase(animate: Boolean, reduceMotion: Boolean) {
    val c = MinaTheme.colors
    var index by rememberSaveable { mutableIntStateOf(0) }
    var pausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate, reduceMotion) {
        while (true) {
            if (!animate || reduceMotion) { delay(300); continue }
            delay(2_200)
            if (animate && !reduceMotion && SystemClock.uptimeMillis() >= pausedUntil) {
                index = (index + 1) % PROVIDERS.size
            }
        }
    }
    var dragAcc by remember { mutableStateOf(0f) }
    val drag = Modifier.pointerInput(reduceMotion) {
        detectHorizontalDragGestures(
            onDragStart = { dragAcc = 0f },
            onDragEnd = {
                if (!reduceMotion && kotlin.math.abs(dragAcc) > 60f) {
                    index = if (dragAcc < 0) (index + 1) % PROVIDERS.size else (index + PROVIDERS.size - 1) % PROVIDERS.size
                    pausedUntil = SystemClock.uptimeMillis() + 4_000
                }
            },
        ) { change, amount ->
            change.consume()
            dragAcc += amount
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = index,
            modifier = Modifier.fillMaxWidth().then(drag),
            transitionSpec = {
                if (reduceMotion) fadeIn(snapSpec()) togetherWith fadeOut(snapSpec())
                else (
                    fadeIn(tween(380, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 3 }
                    ) togetherWith (
                    fadeOut(tween(380, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(380, easing = FastOutSlowInEasing)) { -it / 3 }
                    )
            },
            label = "provider",
        ) { i ->
            val p = PROVIDERS[i]
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(p.res), p.name,
                    Modifier.size(40.dp),
                    tint = if (p.mono) c.ink else Color.Unspecified,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    p.name,
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PROVIDERS.forEachIndexed { i, _ ->
                val w by animateDpAsState(if (i == index) 18.dp else 6.dp, tween(220, easing = FastOutSlowInEasing), label = "pd")
                Box(
                    Modifier
                        .size(w, 6.dp)
                        .background(if (i == index) c.ink else c.hair, CircleShape),
                )
            }
        }
    }
}

// ---------- 底部:继续按钮 + 条款 ----------

@Composable
private fun ContinueButton(choice: ModelChoice, onClick: () -> Unit) {
    val c = MinaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.8f, stiffness = 400f), label = "btn")
    val reduceMotion = MinaTheme.reduceMotion
    val shape = MinaShapes.Button
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(c.btnPrimary, shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag("choose-continue"),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = choice,
            transitionSpec = {
                if (reduceMotion) fadeIn(snapSpec()) togetherWith fadeOut(snapSpec())
                else (
                    fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 }
                    ) togetherWith (
                    fadeOut(tween(220, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 2 }
                    )
            },
            label = "btnLabel",
        ) { ch ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch == ModelChoice.MinaCloud) {
                    Icon(painterResource(R.drawable.logo_google_white), null, Modifier.size(19.dp), tint = Color.White)
                    Spacer(Modifier.width(9.dp))
                }
                Text(
                    if (ch == ModelChoice.MinaCloud) tr(R.string.choose_continue_google) else tr(R.string.choose_continue),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onBtnPrimary,
                )
            }
        }
    }
}

@Composable
private fun TermsLine(modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("${tr(R.string.choose_terms_prefix)} ", fontSize = 13.sp, color = c.ink3)
        TermsLink(tr(R.string.choose_terms_terms))
        Text(" ${tr(R.string.choose_terms_and)} ", fontSize = 13.sp, color = c.ink3)
        TermsLink(tr(R.string.choose_terms_privacy))
        val suffix = tr(R.string.choose_terms_suffix)
        if (suffix.isNotEmpty()) Text(" $suffix", fontSize = 13.sp, color = c.ink3)
    }
}

/** 条款/隐私:占位链接,先可点、后接真链接。 */
@Composable
private fun TermsLink(text: String) {
    val c = MinaTheme.colors
    Text(
        text,
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { /* TODO: 链接到 Terms / Privacy 页 */ },
        fontSize = 13.sp, color = c.ink3, textDecoration = TextDecoration.Underline,
    )
}

// 小工具:只有用到时才 import 的东西放这里,免得文件头太乱
private fun <T> snapSpec(): androidx.compose.animation.core.SnapSpec<T> = androidx.compose.animation.core.snap()
