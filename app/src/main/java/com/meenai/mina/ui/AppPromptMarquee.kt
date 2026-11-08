package com.meenai.mina.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// TRY SAYING — home marquee (ported from AURA's home screen).
//
// A curved carousel of the apps the user ACTUALLY has installed, each carrying
// one example prompt. It advances one app at a time; the centred app is large
// and captioned, its neighbours ride a downward parabola off both edges so the
// band reads as an arc rather than a straight strip. Icons are square (rounded
// far less than AURA's, per Anil's request).
//
// Tapping picks the centred app's prompt and fills the composer with it, like
// the example rows below; a tap never starts a task by itself.
// ============================================================================

/** One curated app: the packages that could provide it, and the prompt it advertises. */
internal data class CuratedApp(
    val label: String,
    val prompt: String,
    val packages: List<String>,
)

/**
 * Apps common enough to be worth advertising, each with a prompt that shows what
 * Mina can actually do with it. Order is the display order; only the installed
 * ones survive [resolveMarqueeItems].
 *
 * Deliberately no banking/payment apps — those requests are refused, so
 * advertising them would promise something we decline.
 */
internal val CURATED_APPS = listOf(
    CuratedApp("WhatsApp", "Text Mom that I'll be home soon", listOf("com.whatsapp", "com.whatsapp.w4b")),
    CuratedApp("YouTube", "Put on a lo-fi playlist", listOf("com.google.android.youtube")),
    CuratedApp("Spotify", "Play my Discover Weekly", listOf("com.spotify.music")),
    CuratedApp("Apple Music", "Play my music library", listOf("com.apple.android.music", "com.apple.music")),
    CuratedApp("Instagram", "Show my latest DMs", listOf("com.instagram.android")),
    CuratedApp("X", "Show my latest posts", listOf("com.twitter.android")),
    CuratedApp("Reddit", "Show my home feed", listOf("com.reddit.frontpage")),
    CuratedApp("Discord", "Read my latest Discord messages", listOf("com.discord")),
    CuratedApp("Gmail", "Check for new mail", listOf("com.google.android.gm")),
    CuratedApp("Maps", "Start navigation home", listOf("com.google.android.apps.maps")),
    CuratedApp("Chrome", "Find flights to Goa", listOf("com.android.chrome")),
    CuratedApp("Swiggy", "Reorder my last meal", listOf("in.swiggy.android")),
    CuratedApp("Zomato", "Find biryani nearby", listOf("com.application.zomato")),
    CuratedApp("Amazon", "Track my latest order", listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping")),
    CuratedApp("Flipkart", "Find earbuds under ₹2,000", listOf("com.flipkart.android")),
    CuratedApp("Uber", "Book a ride to the airport", listOf("com.ubercab")),
    CuratedApp("Telegram", "Reply to my latest Telegram", listOf("org.telegram.messenger")),
    CuratedApp("Netflix", "Resume my show", listOf("com.netflix.mediaclient")),
    CuratedApp("Prime Video", "Resume my Prime show", listOf("com.amazon.avod.thirdpartyclient")),
    CuratedApp("Disney+ Hotstar", "Resume my Hotstar show", listOf("in.startv.hotstar")),
    CuratedApp("Photos", "Show yesterday's photos", listOf("com.google.android.apps.photos")),
    CuratedApp("Calendar", "Read today's schedule", listOf("com.google.android.calendar")),
    CuratedApp("Clock", "Set an alarm for 7:00 AM", listOf("com.google.android.deskclock", "com.oneplus.deskclock")),

    // Common system and productivity apps. These are intentionally explicit
    // rather than showing every installed package: the band should feel
    // curated, not like a package-manager dump.
    CuratedApp("Messages", "Text someone about dinner", listOf("com.google.android.apps.messaging")),
    CuratedApp("Meet", "Start a Meet call", listOf("com.google.android.apps.tachyon")),
    CuratedApp("Drive", "Find my latest document", listOf("com.google.android.apps.docs")),
    CuratedApp("Keep", "Find my grocery list", listOf("com.google.android.keep")),
    CuratedApp("Notion", "Open my latest note", listOf("notion.id")),
    CuratedApp("Todoist", "Show my tasks for today", listOf("com.todoist")),
    CuratedApp("Zoom", "Join my next Zoom call", listOf("us.zoom.videomeetings")),
    CuratedApp("LinkedIn", "Show my LinkedIn updates", listOf("com.linkedin.android")),
    CuratedApp("GitHub", "Open my GitHub notifications", listOf("com.github.android")),
    CuratedApp("ChatGPT", "Ask ChatGPT a question", listOf("com.openai.chatgpt")),
    CuratedApp("Claude", "Ask Claude for help", listOf("com.anthropic.claude")),
    CuratedApp("DeepSeek", "Ask DeepSeek a question", listOf("com.deepseek.chat")),
    CuratedApp("Files", "Find the PDF I downloaded", listOf("com.google.android.apps.nbu.files", "com.oneplus.filemanager")),
    CuratedApp("Settings", "Open Wi-Fi settings", listOf("com.android.settings")),
    CuratedApp("Phone", "Call Dad", listOf("com.google.android.dialer", "com.oplus.dialer", "com.android.dialer")),
    CuratedApp("Contacts", "Find a contact", listOf("com.google.android.contacts")),
    CuratedApp("Calculator", "Calculate a tip", listOf("com.oneplus.calculator", "com.google.android.calculator")),
    CuratedApp("Play Store", "Find a new app", listOf("com.android.vending")),
    CuratedApp("Camera", "Open the camera", listOf("com.oplus.camera", "com.android.camera2")),
    CuratedApp("Perplexity", "Ask Perplexity a question", listOf("ai.perplexity.app.android")),
    CuratedApp("Grok", "Ask Grok something", listOf("ai.x.grok")),
)

/**
 * Prompts shown when not one curated app resolves — a locked-down device, a
 * ROM with none of these installed, or package visibility denied. An empty band
 * would be strictly worse than nothing, so we always render something.
 */
internal val FALLBACK_PROMPTS = listOf(
    "What's on my screen?",
    "Read my notifications",
    "Set an alarm for 7:00 AM",
    "Text someone on WhatsApp",
)

private val INITIAL_MARQUEE_ITEMS = FALLBACK_PROMPTS.map {
    MarqueeItem(label = "Mina", prompt = it, icon = null)
}

/** A resolved marquee entry. [icon] is null while its package icon is loading. */
internal data class MarqueeItem(
    val label: String,
    val prompt: String,
    val icon: ImageBitmap?,
    val packageName: String? = null,
)

/**
 * Pure geometry for the curve — no Compose, no Android, so it is unit-testable.
 *
 * `position` is a continuous index: 3.0 means "app 3 dead centre", 3.5 means
 * "halfway between app 3 and app 4".
 */
internal object MarqueeGeometry {
    /** The 0.3 of y = -0.3·x². */
    const val CURVE_K = 0.3f

    /** How many slots fit between the centre and the screen edge (x = 1). */
    const val SPREAD = 1.6f

    /**
     * Shortest signed distance from [position] to slot [index] on a ring of
     * [count] slots, so the band wraps instead of running out of items.
     * Result is in (-count/2, count/2].
     */
    fun signedDelta(index: Int, position: Float, count: Int): Float {
        if (count <= 0) return 0f
        var d = index - position
        val half = count / 2f
        while (d > half) d -= count
        while (d < -half) d += count
        return d
    }

    /** Slot distance → curve space, where ±1 is the screen edge. */
    fun normalizedX(delta: Float, spread: Float = SPREAD): Float = delta / spread

    /**
     * Downward pixel offset for a logo at [x]. Zero at the apex (centre) and
     * positive — i.e. LOWER on screen — towards both edges.
     */
    fun curveDrop(x: Float, amplitudePx: Float): Float = CURVE_K * x * x * amplitudePx

    /** Logos shrink as they leave the centre; never below 45%. */
    fun scaleAt(x: Float): Float = (1f - 0.42f * abs(x)).coerceAtLeast(0.45f)

    /** …and fade, so the ones bleeding off the edge feel like they're receding. */
    fun alphaAt(x: Float): Float = (1f - 0.62f * abs(x)).coerceIn(0f, 1f)

    /** Index of the app currently closest to the centre. */
    fun centredIndex(position: Float, count: Int): Int =
        if (count <= 0) 0 else ((position.roundToInt() % count) + count) % count

    /** Keeps a fling from accumulating an ever-growing continuous index. */
    fun wrapPosition(position: Float, count: Int): Float {
        if (count <= 0) return 0f
        val wrapped = position % count
        return if (wrapped < 0f) wrapped + count else wrapped
    }

    /**
     * Caption opacity: full when an app is settled at the centre, zero while the
     * band is mid-travel, so one prompt fades out before the next fades in.
     */
    fun captionAlpha(position: Float): Float {
        val drift = abs(position - position.roundToInt())
        return (1f - drift * 3.2f).coerceIn(0f, 1f)
    }
}

/**
 * Resolves installed packages first. This phase is deliberately separate from
 * icon rasterisation so the marquee can enter with stable slots immediately;
 * slow adaptive-icon work must never delay the rest of the Home layout.
 */
private suspend fun resolveMarqueeItems(context: Context): List<MarqueeItem> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val resolved = CURATED_APPS.mapNotNull { app ->
            val pkg = app.packages.firstOrNull { isInstalled(pm, it) } ?: return@mapNotNull null
            MarqueeItem(label = app.label, prompt = app.prompt, icon = null, packageName = pkg)
        }
        resolved.ifEmpty {
            FALLBACK_PROMPTS.map { MarqueeItem(label = "Mina", prompt = it, icon = null) }
        }
    }

private suspend fun loadMarqueeIcons(
    context: Context,
    items: List<MarqueeItem>,
    iconPx: Int,
    onIconLoaded: (Int, ImageBitmap) -> Unit,
) = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    items.forEachIndexed { index, item ->
        val pkg = item.packageName ?: return@forEachIndexed
        runCatching {
            pm.getApplicationIcon(pkg).toBitmap(iconPx, iconPx).asImageBitmap()
        }.getOrNull()?.let { onIconLoaded(index, it) }
    }
}

private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
    runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

/**
 * The curved app marquee. It is a browsing surface: horizontal dragging rotates
 * it, and a tap hands the centred app's prompt to the composer via [onPick],
 * exactly like the example rows. Nothing sends by itself.
 */
@Composable
fun AppPromptMarquee(
    modifier: Modifier = Modifier,
    dwellMillis: Long = 2200,
    travelMillis: Int = 700,
    onPick: (String) -> Unit = {},
) {
    val c = MinaTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    val logoSize = LOGO_SIZE
    val curveAmplitude = CURVE_AMPLITUDE
    val bandHeight = logoSize + curveAmplitude * MarqueeGeometry.CURVE_K + 8.dp
    val iconPx = with(density) { logoSize.roundToPx() }

    // Package resolution is fast enough to reveal the band early. Icons are
    // then painted into those already-visible slots one by one, preventing the
    // marquee from appearing late as a single heavy block.
    val resolvedItems by produceState(initialValue = INITIAL_MARQUEE_ITEMS, context) {
        value = resolveMarqueeItems(context)
    }
    val items by produceState(initialValue = INITIAL_MARQUEE_ITEMS, resolvedItems, iconPx) {
        value = resolvedItems
        if (resolvedItems.isNotEmpty() && resolvedItems.any { it.packageName != null }) {
            loadMarqueeIcons(context, resolvedItems, iconPx) { index, icon ->
                value = value.toMutableList().also { current ->
                    current[index] = current[index].copy(icon = icon)
                }
            }
        }
    }
    if (items.isEmpty()) {
        // First composition, before the IO load lands. Reserve the height so the
        // rest of the screen doesn't jump when the band appears.
        Box(modifier.fillMaxWidth().height(bandHeight + CAPTION_HEIGHT))
        return
    }

    // One continuous index drives everything. Advancing it by exactly 1 per beat
    // is what makes the band step app-by-app instead of crawling continuously.
    // repeatOnLifecycle parks it below STARTED when the app leaves the foreground.
    val position = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var isSettling by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val visualPosition by remember {
        derivedStateOf { if (isDragging) dragPosition else position.value }
    }
    val centred by remember(items) {
        derivedStateOf { items[MarqueeGeometry.centredIndex(visualPosition, items.size)] }
    }

    val slotDistancePx = with(density) { SLOT_DISTANCE.toPx() }

    // User dragging owns the animation until it snaps to the nearest app. Keying
    // the loop by isDragging cancels an automatic travel as soon as a finger
    // arrives, so the two motions never fight over the same Animatable.
    LaunchedEffect(items.size, lifecycle, isDragging, isSettling) {
        if (isDragging || isSettling) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(dwellMillis)
                val next = position.value.roundToInt() + 1f
                position.animateTo(next, tween(travelMillis, easing = FastOutSlowInEasing))
                if (abs(position.value) >= items.size) {
                    position.snapTo(MarqueeGeometry.wrapPosition(position.value, items.size))
                }
            }
        }
    }

    // A CLOCK_TICK is intentionally emitted only when a new slot crosses the
    // centre, like a physical picker wheel.
    LaunchedEffect(items.size) {
        var previousSlot: Int? = null
        snapshotFlow {
            val currentPosition = if (isDragging) dragPosition else position.value
            currentPosition.roundToInt() to (isDragging || isSettling)
        }.collect { (slot, active) ->
            if (active && previousSlot != null && slot != previousSlot) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            previousSlot = slot
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "TRY SAYING",
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp),
            color = c.ink3,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(bandHeight)
                .padding(top = 10.dp)
                .pointerInput(items.size, onPick) {
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            isSettling = false
                            isDragging = true
                            dragPosition = position.value
                            scope.launch { position.stop() }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            dragPosition -= dragAmount / slotDistancePx
                        },
                        onDragEnd = {
                            val dragged = dragPosition
                            val velocitySlotsPerSecond =
                                (-velocityTracker.calculateVelocity().x / slotDistancePx)
                                    .coerceIn(-MAX_FLING_SLOTS_PER_SECOND, MAX_FLING_SLOTS_PER_SECOND)
                            isSettling = true
                            settleJob = scope.launch {
                                position.stop()
                                position.snapTo(dragged)
                                isDragging = false
                                if (abs(velocitySlotsPerSecond) > 0.08f) {
                                    position.animateDecay(
                                        initialVelocity = velocitySlotsPerSecond,
                                        animationSpec = exponentialDecay(frictionMultiplier = FLING_FRICTION),
                                    )
                                }
                                val target = position.value.roundToInt().toFloat()
                                position.animateTo(
                                    target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                )
                                position.snapTo(MarqueeGeometry.wrapPosition(position.value, items.size))
                                if (isActive) isSettling = false
                            }
                        },
                        onDragCancel = {
                            val target = dragPosition.roundToInt().toFloat()
                            isSettling = true
                            settleJob = scope.launch {
                                position.stop()
                                position.snapTo(target)
                                isDragging = false
                                position.animateTo(
                                    target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                )
                                position.snapTo(MarqueeGeometry.wrapPosition(position.value, items.size))
                                if (isActive) isSettling = false
                            }
                        },
                    )
                }
                .pointerInput(items.size, onPick) {
                    detectTapGestures { onPick(centred.prompt) }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            // Use the actual viewport width and clip symmetrically.
            val halfSpanPx = constraints.maxWidth / 2f
            val amplitudePx = with(density) { curveAmplitude.toPx() }

            items.forEachIndexed { index, item ->
                MarqueeLogo(
                    item = item,
                    size = logoSize,
                    modifier = Modifier.graphicsLayer {
                        val delta = MarqueeGeometry.signedDelta(index, visualPosition, items.size)
                        val x = MarqueeGeometry.normalizedX(delta)
                        translationX = x * halfSpanPx
                        translationY = MarqueeGeometry.curveDrop(x, amplitudePx)
                        val s = MarqueeGeometry.scaleAt(x)
                        scaleX = s
                        scaleY = s
                        // Fully transparent past the edge — an alpha-0 layer is
                        // skipped at draw time, so off-screen slots cost nothing.
                        alpha = if (abs(delta) > OFFSCREEN_SLOTS) 0f else MarqueeGeometry.alphaAt(x)
                    },
                )
            }
        }

        // The caption sits below the arc and belongs to whichever app is centred.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CAPTION_HEIGHT)
                .pointerInput(items.size, onPick) { detectTapGestures { onPick(centred.prompt) } },
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "“${centred.prompt}”",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = c.ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = MarqueeGeometry.captionAlpha(visualPosition) },
            )
        }
    }
}

/** One logo well. Real app icons draw as-is; the fallback rows draw a neutral glyph. */
@Composable
private fun MarqueeLogo(item: MarqueeItem, size: Dp, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    Box(
        modifier = modifier
            .size(size)
            // Square, per Anil's request: only a hint of rounding, far less than AURA's size/3.
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface)
            .border(0.5.dp, c.edge, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = item.icon, animationSpec = tween(220), label = "marquee_icon") { icon ->
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(size),
                )
            } else {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = item.label,
                    tint = c.ink2,
                    modifier = Modifier.size(size * 0.47f),
                )
            }
        }
    }
}

private val LOGO_SIZE = 60.dp
private val SLOT_DISTANCE = 96.dp

/** A little extra carry makes the marquee feel like a rotary dial, without becoming a runaway carousel. */
private const val MAX_FLING_SLOTS_PER_SECOND = 7.5f
private const val FLING_FRICTION = 1.15f

/** Peak-to-edge drop of the parabola at scale 1; the band reserves 0.3× of it. */
private val CURVE_AMPLITUDE = 150.dp
private val CAPTION_HEIGHT = 46.dp

/** Slots beyond this distance from the centre are past the screen edge. */
private const val OFFSCREEN_SLOTS = MarqueeGeometry.SPREAD * 1.3f
