package com.meenai.mina.ui

// Requires: Compose BOM 2024.09+ (foundation 1.7+ for Modifier.animateItem)
// UI-only reference design. Not routed anywhere yet; MinaChatDemo renders fake data.

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- MODEL (UI only) ----------
sealed interface ChatItem { val id: String }
data class UserMsg(override val id: String, val text: String) : ChatItem
data class MinaMsg(override val id: String, val text: String) : ChatItem
data class ChatResultCard(
    override val id: String,
    val title: String,
    val subtitle: String,
    val rows: List<String>,
    val status: String? = null,      // e.g. "Done", "Working"
) : ChatItem

// ---------- TOKENS ----------
private val Ink = Color(0xFF1C1C1E)
private val SubInk = Color(0xFF6B7280)
private val GlassFill = Color.White.copy(alpha = 0.72f)
private val GlassEdge = Color.White.copy(alpha = 0.9f)
private val Mint = Color(0xFF1E7A4C)
private val MintBg = Color(0xFFD6F5E3)

private val EnterSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)

// ---------- SCREEN ----------
@Composable
fun MinaChatScreen(
    items: List<ChatItem>,
    isTyping: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // ids already shown, so only NEW messages animate (not on scroll back)
    val seen = remember { mutableStateListOf<String>() }

    // Smoothly follow the newest message / typing indicator
    val lastIndex = items.size + (if (isTyping) 1 else 0) - 1
    LaunchedEffect(lastIndex) {
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val isNew = item.id !in seen
                SideEffect { if (isNew) seen.add(item.id) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .animateItem(
                            fadeInSpec = null, fadeOutSpec = tween(150),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        )
                ) {
                    when (item) {
                        is UserMsg -> EntranceAnim(isNew, fromRight = true) {
                            ChatUserBubble(item.text, Modifier.align(Alignment.CenterEnd))
                        }
                        is MinaMsg -> EntranceAnim(isNew, fromRight = false) {
                            MinaChatText(item.text, animate = isNew)
                        }
                        is ChatResultCard -> EntranceAnim(isNew, fromRight = false) {
                            ResultCard(item, animate = isNew)
                        }
                    }
                }
            }
            if (isTyping) {
                item(key = "typing") {
                    Box(Modifier.animateItem()) { TypingIndicator() }
                }
            }
        }
        ChatInputBar(onSend = { onSend(it); scope.launch { if (lastIndex >= 0) listState.animateScrollToItem(lastIndex) } })
    }
}

// ---------- ENTRANCE: slide up + scale + fade, spring ----------
@Composable
private fun EntranceAnim(animate: Boolean, fromRight: Boolean, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) p.animateTo(1f, EnterSpring) }
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            val v = p.value
            alpha = v.coerceIn(0f, 1f)
            translationY = (1f - v) * 24.dp.toPx()
            val s = 0.94f + 0.06f * v
            scaleX = s; scaleY = s
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (fromRight) 1f else 0f, 1f)
        }
    ) { content() }
}

// ---------- USER BUBBLE (black pill, right) ----------
@Composable
private fun ChatUserBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
            .background(Ink)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp)
    }
}

// ---------- MINA TEXT: word-by-word fade-in (streaming feel) ----------
@Composable
private fun MinaChatText(text: String, animate: Boolean) {
    val words = remember(text) { text.split(" ") }
    var shown by remember { mutableIntStateOf(if (animate) 0 else words.size) }
    LaunchedEffect(text) {
        if (animate) while (shown < words.size) { delay(28); shown++ }
    }
    val annotated = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            val a = when {
                i < shown - 3 -> 1f
                i < shown -> 0.35f + 0.22f * (3 - (shown - i))   // soft trailing fade
                else -> 0f
            }.coerceIn(0f, 1f)
            withStyle(SpanStyle(color = Ink.copy(alpha = a))) { append(w) }
            if (i != words.lastIndex) append(" ")
        }
    }
    Text(
        annotated,
        modifier = Modifier.fillMaxWidth(0.92f).padding(start = 4.dp, end = 24.dp),
        style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    )
}

// ---------- GLASS SURFACE (swap to Haze here if you want real blur) ----------
@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(0.08f), spotColor = Color.Black.copy(0.10f))
            .clip(shape)
            // .hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())  // Haze option
            .background(GlassFill)
            .border(1.dp, GlassEdge, shape),
        content = content
    )
}

// ---------- RESULT CARD: rows stagger in, status pill, press scale ----------
@Composable
private fun ResultCard(card: ChatResultCard, animate: Boolean) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, 500f), label = "press")
    var expanded by rememberSaveable(card.id) { mutableStateOf(true) }

    GlassSurface(
        Modifier
            .fillMaxWidth(0.94f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            }
    ) {
        Column(Modifier.padding(18.dp).animateContentSize(spring(0.85f, 300f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(card.subtitle, fontSize = 14.sp, color = SubInk)
                }
                card.status?.let { StatusPill(it, animate) }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                card.rows.forEachIndexed { i, row -> StaggerRow(row, i, animate) }
            }
        }
        ShimmerSweep(animate)
    }
}

@Composable
private fun StaggerRow(text: String, index: Int, animate: Boolean) {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) { delay(180L + index * 70L); p.animateTo(1f, spring(0.8f, 400f)) }
    }
    Row(
        Modifier.padding(vertical = 5.dp).graphicsLayer {
            alpha = p.value; translationX = (1f - p.value) * -12.dp.toPx()
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 15.sp, color = Ink)
    }
}

@Composable
private fun StatusPill(text: String, animate: Boolean) {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) { delay(250); p.animateTo(1f, spring(0.5f, 450f)) } }
    Box(
        Modifier
            .graphicsLayer { scaleX = p.value; scaleY = p.value; alpha = p.value }
            .clip(RoundedCornerShape(50))
            .background(MintBg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

// one soft light sweep across the card when it first appears
@Composable
private fun BoxScope.ShimmerSweep(animate: Boolean) {
    if (!animate) return
    val x = remember { Animatable(-0.4f) }
    LaunchedEffect(Unit) { delay(120); x.animateTo(1.4f, tween(900, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = if (x.value >= 1.4f) 0f else 1f }
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to Color.Transparent,
                    (x.value - 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                    x.value.coerceIn(0f, 1f) to Color.White.copy(alpha = 0.35f),
                    (x.value + 0.15f).coerceIn(0f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                )
            )
    )
}

// ---------- TYPING INDICATOR: 3 dots wave inside a glass pill ----------
@Composable
private fun TypingIndicator() {
    val t = rememberInfiniteTransition(label = "typing")
    GlassSurface(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val y by t.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        keyframes {
                            durationMillis = 1200
                            0f at 0; 1f at 300 using FastOutSlowInEasing; 0f at 600; 0f at 1200
                        },
                        initialStartOffset = StartOffset(i * 150)
                    ), label = "dot$i"
                )
                Box(
                    Modifier.size(8.dp)
                        .graphicsLayer { translationY = -5.dp.toPx() * y; alpha = 0.4f + 0.6f * y }
                        .clip(CircleShape).background(Ink)
                )
            }
        }
    }
}

// ---------- INPUT BAR: glass pill, send button pops in ----------
@Composable
private fun ChatInputBar(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val canSend = text.isNotBlank()
    val sendScale by animateFloatAsState(if (canSend) 1f else 0.6f, spring(0.55f, 420f), label = "send")
    val sendAlpha by animateFloatAsState(if (canSend) 1f else 0.35f, tween(160), label = "sendA")

    GlassSurface(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (text.isEmpty()) Text("Ask Mina anything", color = SubInk, fontSize = 16.sp)
                BasicTextField(text, { text = it }, textStyle = TextStyle(fontSize = 16.sp, color = Ink), maxLines = 5)
            }
            Box(
                Modifier.size(44.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale; alpha = sendAlpha }
                    .clip(CircleShape).background(Ink)
                    .clickable(enabled = canSend) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend(text.trim()); text = ""
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Send", tint = Color.White) }
        }
    }
}

// ---------- PREVIEW / DEMO (fake data, UI only) ----------
@Composable
fun MinaChatDemo() {
    val items = remember { mutableStateListOf<ChatItem>(MinaMsg("m0", "Hi, I'm Mina. What should I do on your phone today?")) }
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var n by remember { mutableIntStateOf(1) }
    MinaChatScreen(items, typing, onSend = { msg ->
        items += UserMsg("u${n++}", msg)
        scope.launch {
            typing = true; delay(1200); typing = false
            items += MinaMsg("m${n++}", "On it. I opened Settings and turned on dark mode for you.")
            delay(500)
            items += ChatResultCard("c${n++}", "Dark mode", "Settings · Display", listOf("Opened Settings", "Found Display", "Turned on dark mode"), status = "Done")
        }
    })
}
