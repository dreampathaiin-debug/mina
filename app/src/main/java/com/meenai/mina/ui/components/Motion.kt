package com.meenai.mina.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.launch

/**
 * Page-level counter for staggered entrances. [PageScaffold] provides one, so every
 * [GroupLabel] / [GroupCard] / [StateCard] on a sub page rises in one after another
 * without each call site passing an index.
 */
class StaggerSequence {
    private var n = 0
    fun next(): Int = n++
}

val LocalStagger = staticCompositionLocalOf<StaggerSequence?> { null }

/** Takes the next slot from the page's [StaggerSequence] and rises in on it; no-op outside a page. */
fun Modifier.pageStagger(): Modifier = composed {
    val seq = LocalStagger.current
    val index = remember { seq?.next() }
    if (index == null) this else this.staggerIn(index, 18.dp)
}

/**
 * Chat bubble entrance: a soft spring pop (0.86 → 1 scale, 10dp rise, fade) from the
 * bubble's tail corner. Plays once per item: [rememberSaveable] keeps the "played" flag
 * inside LazyColumn, so scrolling back up never replays it.
 */
fun Modifier.bubbleIn(fromEnd: Boolean): Modifier = composed {
    if (MinaTheme.reduceMotion) return@composed this
    var played by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val rise = with(density) { 10.dp.toPx() }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    val alpha = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            launch { alpha.animateTo(1f, tween(180, easing = MinaMotion.Decelerate)) }
            progress.animateTo(1f, MinaMotion.bouncy<Float>())
            played = true
        }
    }
    this.graphicsLayer {
        val p = progress.value
        val s = 0.86f + 0.14f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * rise
        this.alpha = alpha.value
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
    }
}
