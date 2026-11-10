package com.meenai.mina.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home 各卡的公共玻璃底座:28dp 圆角毛玻璃、1dp 描边,直接用 [MinaCard]。
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    MinaCard(modifier, content = content)

/**
 * 左滑删除的卡片:卡片跟着手指走,轻微左倾(最多 3 度)并缩到 0.98,
 * 背后右侧的垃圾桶随进度淡入放大;过了 40% 宽度或快速一甩就滑出去删掉,否则弹回原位。
 * 过线的瞬间来一下轻触感。
 */
@Composable
fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MinaTheme.colors
    val haptic = LocalHapticFeedback.current
    val reduceMotion = MinaTheme.reduceMotion
    val scope = rememberCoroutineScope()
    var cardW by remember { mutableStateOf(1) }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var crossed by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().onSizeChanged { cardW = it.width.coerceAtLeast(1) }) {
        // 卡片背后右侧的垃圾桶
        Box(Modifier.matchParentSize().clip(MinaShapes.Card)) {
            val p = ((-offset.value) / (cardW * 0.4f)).coerceIn(0f, 1f)
            Icon(
                MinaIcons.Trash, null,
                Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = 0.12f + 0.88f * p
                        val s = 0.8f + 0.4f * p
                        scaleX = s; scaleY = s
                    }
                    .padding(end = 26.dp)
                    .size(24.dp),
                tint = c.bad,
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value
                    if (!reduceMotion) {
                        val p = ((-offset.value) / (cardW * 0.4f)).coerceIn(0f, 1f)
                        rotationZ = -3f * p
                        val s = 1f - 0.02f * p
                        scaleX = s; scaleY = s
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { crossed = false },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val threshold = cardW * 0.4f
                            val target = (offset.value + drag).coerceAtMost(0f)
                            scope.launch { offset.snapTo(target) }
                            if (!crossed && target <= -threshold) {
                                crossed = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (crossed && target > -threshold) {
                                crossed = false
                            }
                        },
                        onDragEnd = {
                            val threshold = cardW * 0.4f
                            val velocity = tracker.calculateVelocity().x
                            val past = offset.value <= -threshold || velocity < -1500f
                            if (past) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    offset.animateTo(-cardW * 1.35f, tween(220))
                                    onDelete()
                                }
                            } else {
                                scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) } },
                    )
                },
            content = content,
        )
    }
}

/**
 * 顶部通知卡:一小句话 —— 动词小胶囊 + 正文 + 内联的小圆 App 图标,[FlowRow] 自然换行;整卡可点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationCard(
    verb: String,
    title: String,
    app: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val c = MinaTheme.colors
    val icon = remember(app) { app?.let { resolveAppIcon(context, it) } }
    GlassCard(modifier.pressScale(onClick)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .clip(MinaShapes.Pill)
                    .background(c.fill)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(verb, style = MinaType.Caption, color = c.ink)
            }
            Text(
                title,
                Modifier.align(Alignment.CenterVertically),
                style = MinaType.Body,
                color = c.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null) {
                Image(
                    icon.asImageBitmap(), null,
                    Modifier
                        .align(Alignment.CenterVertically)
                        .size(18.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

private fun resolveAppIcon(context: Context, pkg: String): Bitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(48, 48)
}.getOrNull()

/** 天气卡在界面上长什么样(纯展示,文字在上层取好)。 */
data class WeatherCardModel(
    val place: String?,
    val condition: String,
    val temp: String,
    val sunLabel: String,
    val sunTime: String,
    val icon: ImageVector,
)

/**
 * 常驻天气卡:顶行是天气图标 + 地名;里面两块白 70% 圆角 20dp 的小瓷砖,
 * 左边天气 + 大号温度,右边日落/日出 + 时刻;数值和图标原地淡换,第一次才闪一下。
 */
@Composable
fun WeatherCardView(
    w: WeatherCardModel?,
    loading: Boolean,
    showEnableLocation: Boolean,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier,
    rationale: String? = null,
) {
    val c = MinaTheme.colors
    GlassCard(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedContent(w?.icon ?: MinaIcons.Cloud, label = "weatherIcon") { ic ->
                        Icon(ic, null, Modifier.size(20.dp), tint = c.ink2)
                    }
                    Text(
                        w?.place.orEmpty(),
                        Modifier.weight(1f),
                        style = MinaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showEnableLocation) {
                        Box(
                            Modifier
                                .clip(MinaShapes.Pill)
                                .background(c.btnPrimary)
                                .pressScale(onEnableLocation)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                com.meenai.mina.i18n.tr(com.meenai.mina.R.string.home_enable_location),
                                style = MinaType.Caption,
                                color = c.onBtnPrimary,
                            )
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WeatherTile(Modifier.weight(1f)) {
                        AnimatedContent(w?.condition.orEmpty(), label = "cond") { label ->
                            Text(label, style = MinaType.Body.copy(fontSize = 15.sp), color = c.ink3)
                        }
                        AnimatedContent(w?.temp.orEmpty(), label = "temp") { t ->
                            Text(t, style = MinaType.LargeTitle.copy(fontSize = 36.sp), color = c.ink)
                        }
                    }
                    WeatherTile(Modifier.weight(1f)) {
                        AnimatedContent(w?.sunLabel.orEmpty(), label = "sunLabel") { l ->
                            Text(l, style = MinaType.Body.copy(fontSize = 15.sp), color = c.ink3)
                        }
                        AnimatedContent(w?.sunTime.orEmpty(), label = "sunTime") { t ->
                            Text(t, style = MinaType.Title.copy(fontSize = 22.sp), color = c.ink)
                        }
                    }
                }
                if (rationale != null) {
                    Text(rationale, Modifier.padding(top = 8.dp), style = MinaType.Hint, color = c.ink3)
                }
            }
            if (loading && w == null) ShimmerOverlay(Modifier.fillMaxSize())
        }
    }
}

/** 白 70% 的小瓷砖(深色下用主题底色),圆角 20dp、内边距 16dp。 */
@Composable
fun WeatherTile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MinaTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (c.isDark) c.fill else Color.White.copy(alpha = 0.70f))
            .padding(16.dp),
        content = content,
    )
}

/** 第一次还没数据时的一层细闪。 */
@Composable
private fun ShimmerOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmerX",
    )
    Box(
        modifier.clip(MinaShapes.Card).drawWithContent {
            drawContent()
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                    start = Offset(size.width * (x * 2f - 1f), 0f),
                    end = Offset(size.width * (x * 2f), size.height),
                )
            )
        }
    )
}

/** 新通知进场:淡入 + 向下滑进来。 */
fun Modifier.appearDown(): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    if (MinaTheme.reduceMotion) return@composed this
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(240, easing = MinaMotion.Decelerate), label = "appearAlpha")
    val ty by animateFloatAsState(if (shown) 0f else -16.dp.value, tween(300, easing = MinaMotion.Decelerate), label = "appearY")
    graphicsLayer { alpha = a; translationY = ty }
}

/** WMO 天气代码选图标:0 晴(白天太阳/夜里月亮),雨系用雨,雾用雾,雷用闪电,其余都算云。 */
fun weatherIconFor(code: Int?, isDay: Boolean): ImageVector = when {
    code == 0 -> if (isDay) MinaIcons.Sun else MinaIcons.Moon
    code in 51..57 || code in 61..67 || code in 80..82 -> MinaIcons.Rain
    code == 45 || code == 48 -> MinaIcons.Fog
    code in 95..99 -> MinaIcons.Bolt
    else -> MinaIcons.Cloud
}

// ---------- 预览 ----------

@Preview(name = "Notification card", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun NotificationCardPreview() {
    MinaTheme(dark = false) {
        NotificationCard(verb = "Show", title = "Priya: running late, see you in 10", app = null, Modifier.padding(8.dp))
    }
}

@Preview(name = "Weather (day)", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun WeatherDayPreview() {
    MinaTheme(dark = false) {
        WeatherCardView(
            WeatherCardModel("Lachhmangarh Tehsil, IN", "Clear Sky", "30°C", "Sunset", "5:34 PM", MinaIcons.Sun),
            loading = false, showEnableLocation = false, onEnableLocation = {},
            Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "Weather (after sunset)", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun WeatherNightPreview() {
    MinaTheme(dark = false) {
        WeatherCardView(
            WeatherCardModel("Lachhmangarh Tehsil, IN", "Fog", "18°C", "Sunrise", "6:12 AM", MinaIcons.Moon),
            loading = false, showEnableLocation = false, onEnableLocation = {},
            Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "Empty notifications state", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun EmptyNotificationsPreview() {
    MinaTheme(dark = false) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WeatherCardView(
                WeatherCardModel("Lachhmangarh Tehsil, IN", "Clear Sky", "30°C", "Sunset", "5:34 PM", MinaIcons.Sun),
                loading = false, showEnableLocation = false, onEnableLocation = {},
            )
        }
    }
}

/**
 * 顶栏的紧凑分段:56dp 高、玻璃底(白 35~45% 罩在 Haze 模糊上,1dp 白 30% 描边),
 * 每项内容定宽(文字部分最少 88dp,左右各 22dp 内边距),
 * 黑色滑块随弹簧滑动、宽度跟着标签走,文字 200ms 渐换色,切换带轻触感,每项按压 0.97。
 */
@Composable
fun GlassCompactTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MinaTheme.colors
    val haze = LocalHazeState.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val tabWidths = remember(options) {
        options.map { label ->
            val text = measurer.measure(AnnotatedString(label), style, maxLines = 1, softWrap = false).size.width
            text.coerceAtLeast(with(density) { 44.dp.roundToPx() }) + with(density) { 44.dp.roundToPx() }
        }
    }
    val height = 56.dp
    // haze 0.9 画玻璃时要给 backgroundColor(v0.1.61 的崩溃就是它没给)
    val glassBase = if (c.isDark) GlassBaseDark else GlassBaseLight
    val glassStyle = remember(c) { HazeStyle(backgroundColor = glassBase, tint = HazeTint(c.surface), blurRadius = 28.dp) }
    Box(
        modifier
            .height(height)
            .clip(MinaShapes.Pill)
            .then(
                if (haze != null && GlassBlurSupported) {
                    Modifier.hazeChild(haze, shape = MinaShapes.Pill, style = glassStyle)
                } else {
                    Modifier.background(c.glass.copy(alpha = 0.42f))
                }
            )
            .border(1.dp, Color.White.copy(alpha = 0.30f), MinaShapes.Pill)
    ) {
        val offX by animateDpAsState(
            with(density) { (tabWidths.take(selected).sum() + 4.dp.toPx()).toDp() },
            spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "pillX",
        )
        val pillW by animateDpAsState(
            with(density) { tabWidths[selected].toDp() },
            spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "pillW",
        )
        Box(
            Modifier
                .offset(x = offX)
                .width(pillW)
                .height(height)
                .padding(vertical = 4.dp)
                .clip(MinaShapes.Pill)
                .background(c.btnPrimary)
        )
        Row(Modifier.padding(horizontal = 4.dp)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .width(with(density) { tabWidths[i].toDp() })
                        .height(height)
                        .pressScale(onClick = {
                            if (i != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    val color by animateColorAsState(if (i == selected) c.onBtnPrimary else c.ink2, tween(200), label = "tabColor")
                    Text(label, style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}
