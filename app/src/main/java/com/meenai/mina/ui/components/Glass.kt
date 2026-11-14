package com.meenai.mina.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ---------- 背景:渐变 + 光晕 + 胶片颗粒 ----------

/** 页面级 Haze 状态:[GlassPage] 在根部提供,玻璃卡片用它把身后的内容真模糊。 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

private val TopLight = Color(0xFF7B9BC4)
private val MidLight = Color(0xFFA9B6C8)
private val BottomLight = Color(0xFFD6D4D2)
private val GlowWarm = Color(0xFFF3E9E0)
private val TopDark = Color(0xFF1B2433)
private val BottomDark = Color(0xFF0E1117)

/** 玻璃卡片身后的近似底色(haze 的 HazeStyle 必须要一个 backgroundColor):光晕后面的渐变中段。 */
val GlassBaseLight = MidLight
val GlassBaseDark = Color(0xFF131824)

/** 页面底:竖向渐变 + 中上部的暖色光晕 + 5% 胶片颗粒。深色用 [TopDark] → [BottomDark]。 */
@Composable
fun GradientGrainBackground(modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val brush = Brush.verticalGradient(
        if (c.isDark) listOf(TopDark, Color(0xFF131824), BottomDark)
        else listOf(TopLight, MidLight, BottomLight),
    )
    Box(modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(brush))
        Box(
            Modifier.matchParentSize().drawBehind {
                val glow = Brush.radialGradient(
                    colors = listOf(
                        GlowWarm.copy(alpha = if (c.isDark) 0.08f else 0.35f),
                        GlowWarm.copy(alpha = 0f),
                    ),
                    center = Offset(size.width / 2f, size.height * 0.38f),
                    radius = maxOf(size.width, size.height) * 0.8f,
                )
                drawRect(glow)
            },
        )
        val grain = remember { grainBitmap() }
        Box(
            Modifier.matchParentSize().drawBehind {
                val cell = 256.dp.roundToPx()
                var y = 0
                while (y < size.height.toInt()) {
                    var x = 0
                    while (x < size.width.toInt()) {
                        drawImage(grain, dstOffset = IntOffset(x, y), dstSize = IntSize(cell, cell), alpha = 0.05f, filterQuality = FilterQuality.None)
                        x += cell
                    }
                    y += cell
                }
            },
        )
    }
}

/** 背景加 Haze 采样点 + 玻璃卡片共享状态:页面用它包住全部内容。 */
@Composable
fun GlassPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val haze = remember { HazeState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalHazeState provides haze) {
        Box(modifier.fillMaxSize()) {
            GradientGrainBackground(Modifier.matchParentSize().haze(haze))
            content()
        }
    }
}

@Volatile private var grainCache: androidx.compose.ui.graphics.ImageBitmap? = null

private fun grainBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    grainCache?.let { return it }
    val n = 256
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val px = IntArray(n * n)
    val rnd = java.util.Random(20261110L)
    for (i in px.indices) {
        val v = 170 + rnd.nextInt(85)
        px[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
    }
    bmp.setPixels(px, 0, n, 0, 0, n, n)
    val ib = bmp.asImageBitmap()
    grainCache = ib
    return ib
}

// ---------- 按压效果:0.97 缩放 + 轻微压暗,无默认 ripple ----------

fun Modifier.pressScale(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    role: androidx.compose.ui.semantics.Role? = null,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = MinaTheme.reduceMotion
    val down = pressed && !reduce
    // Spring in, springier out: the press sinks fast and the release lands with a small bounce.
    val scale by animateFloatAsState(
        if (down) pressedScale else 1f,
        if (reduce) androidx.compose.animation.core.snap<Float>() else if (down) MinaMotion.press<Float>() else MinaMotion.release<Float>(),
        label = "pressScale",
    )
    val dim by animateFloatAsState(
        if (down) 0.05f else 0f,
        if (reduce) androidx.compose.animation.core.snap<Float>() else tween<Float>(if (down) 90 else 220),
        label = "pressDim",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick,
                )
            } else Modifier,
        )
        .drawWithContent {
            drawContent()
            if (dim > 0.001f) drawRect(Color.Black.copy(alpha = dim))
        }
}

// ---------- 列表入场:依次淡入上移 ----------

fun Modifier.staggerIn(index: Int, slide: Dp = 26.dp): Modifier = composed {
    if (MinaTheme.reduceMotion) {
        this
    } else {
        val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
        val rise = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            // Each item waits for its slot, then fades and rises on a spring: the list lands like a cascade.
            kotlinx.coroutines.delay(((index % 12) * 35).toLong())
            launch { alpha.animateTo(1f, tween(260, easing = MinaMotion.Decelerate)) }
            rise.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 380f))
        }
        graphicsLayer {
            val q = rise.value
            this.alpha = alpha.value
            translationY = (1f - q) * slide.toPx()
            val sc = 0.97f + 0.03f * q
            scaleX = sc
            scaleY = sc
        }
    }
}

// ---------- 玻璃胶囊分段:黑色滑块,弹簧同步 ----------

/** 玻璃胶囊里的分段选择:黑色实底滑块随弹簧滑动(damping 0.8 / stiffness 400),切换带轻触感。 */
@Composable
fun GlassPillTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
    height: Dp = 36.dp,
) {
    val c = MinaTheme.colors
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pillShape = MinaShapes.Pill
    BoxWithConstraints(
        modifier.clip(pillShape)
            .background(c.glass.copy(alpha = if (c.isDark) 0.55f else 0.55f))
            .border(1.dp, c.cardEdge, pillShape),
    ) {
        val n = options.size.coerceAtLeast(1)
        // 宽度没被约束时(BoxWithConstraints 会给 Infinity)算不出滑块位,退回平铺布局
        val w = if (maxWidth.value.isFinite()) maxWidth / n else maxWidth
        val offset by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = 400f), label = "pillx")
        Box(
            Modifier.offset(x = offset).width(w).height(height)
                .clip(pillShape).background(if (c.isDark) c.btnPrimary else c.btnPrimary),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.width(w).height(height)
                        .pressScale(
                            onClick = {
                                if (i != selected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(i)
                                }
                            },
                        )
                        .then(if (tags != null) Modifier.testTag(tags[i]) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        color = if (i == selected) c.onBtnPrimary else c.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------- 56dp 圆形玻璃按钮 ----------

@Composable
fun CircleIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    val c = MinaTheme.colors
    Box(
        modifier.size(56.dp)
            .background(c.glass.copy(alpha = if (c.isDark) 0.55f else 0.6f), CircleShape)
            .border(1.dp, c.cardEdge, CircleShape)
            .clip(CircleShape)
            .pressScale(onClick, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = tint.takeOrElse { c.ink })
    }
}

// ---------- 72dp 设置行 / 键值行 ----------

/** 玻璃分组里的标准设置行:24dp 图标、18sp 标题、右箭头,72dp 高。 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    ListRow(icon, title, subtitle, first, onClick = onClick, trailing = { trailing(); RowChevron() })
}

/** 键值行:左边灰色标签,右边深色值;值为空时灰色占位。 */
@Composable
fun KeyValueRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    first: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MinaTheme.colors
    val empty = value.isNullOrBlank()
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.pressScale(onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MinaType.Body, color = c.ink2)
        Text(
            if (empty) (placeholder ?: "") else value,
            style = MinaType.Value,
            color = if (empty) c.ink3 else c.ink,
            textAlign = TextAlign.End,
            maxLines = 2,
        )
        trailing()
    }
}

// ---------- 左滑删除:倾斜 + 后面露出垃圾桶 ----------

/**
 * 包住一行可删的内容:向左拖出一条缝,后面露出深灰玻璃和垃圾桶;
 * 拖过阈值松手就删(调用方自己的删除逻辑),否则弹回。卡片带一点倾斜。
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MinaTheme.colors
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    var dx by remember { mutableStateOf(0f) }
    var gone by remember { mutableStateOf(false) }
    val state = rememberDraggableState { d ->
        dx = (dx + d).coerceIn(-threshold * 2.2f, 0f)
    }
    Box(modifier.fillMaxWidth()) {
        // 后面露出的删除提示
        Row(
            Modifier.matchParentSize().clip(MinaShapes.Card).background(c.badSoft),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(
                com.meenai.mina.ui.theme.MinaIcons.Trash, null,
                Modifier.padding(end = 24.dp).size(22.dp), tint = c.bad,
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = dx
                    rotationZ = (dx / 40f).coerceIn(-6f, 0f)
                    alpha = if (gone) 0f else 1f
                }
                .draggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = {
                        if (dx <= -threshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            gone = true
                            onDelete()
                        } else {
                            dx = 0f
                        }
                    },
                ),
            content = content,
        )
    }
}
