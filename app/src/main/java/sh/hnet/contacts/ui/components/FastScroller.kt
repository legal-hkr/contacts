package sh.hnet.contacts.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val THUMB_HEIGHT = 48.dp
private val THUMB_TOUCH = 48.dp
private val BUBBLE_SIZE = 64.dp
private val INDEX_WIDTH = 28.dp
private val INDEX_ROW = 22.dp

/**
 * Music's fast scroller (SPEC §4.6), extended with the letter index: a thumb on the end edge, shown
 * while the list scrolls and 1.5 s after. While the thumb is dragged, the index of the letters
 * present stands along the edge and the finger points at one of them: the list jumps to that
 * letter, which the index highlights and a bubble shows. Hidden from TalkBack, which pages.
 *
 * @param sections The letter of every item of the list, in order
 * @param letters The index: the letters present, in the list's order (the star for favorites first)
 * @param letterItem The item each letter starts at
 */
@Composable
fun FastScroller(state: LazyListState, sections: List<String>, letters: List<String>, letterItem: Map<String, Int>, modifier: Modifier = Modifier) {
    val itemCount = sections.size
    if (itemCount < 2) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val count by rememberUpdatedState(itemCount)

    BoxWithConstraints(modifier.fillMaxHeight().clearAndSetSemantics {}) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - THUMB_HEIGHT).toPx() }.coerceAtLeast(1f)
        // Read through derived state: the scroll position changes every frame, the thumb only when its place does
        val listFraction by remember(state, itemCount) {
            derivedStateOf { (state.firstVisibleItemIndex.toFloat() / (itemCount - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)).coerceIn(0f, 1f) }
        }
        val firstSection by remember(state, sections) { derivedStateOf { sections.getOrElse(state.firstVisibleItemIndex) { "" } } }
        val fraction = if (dragging) dragFraction else listFraction
        val thumbY = (fraction * trackPx).roundToInt()
        val current = if (dragging) sections.getOrElse((fraction * (itemCount - 1)).roundToInt()) { "" } else firstSection
        val trackPxNow by rememberUpdatedState(trackPx)
        val listFractionNow by rememberUpdatedState(listFraction)

        // The index runs the full height of the list (SPEC §4.6), every letter in an equal share of it, at least 22 dp; when the
        // screen is too short for that, every second letter stands in it
        val minRowPx = with(density) { INDEX_ROW.toPx() }
        val shownLetters = if (letters.isEmpty() || constraints.maxHeight / letters.size >= minRowPx) letters else letters.filterIndexed { i, _ -> i % 2 == 0 }
        val rowPx = constraints.maxHeight.toFloat() / shownLetters.size.coerceAtLeast(1)
        // While the thumb is dragged, the finger points at a letter of the index: the list jumps to it
        val pointed = if (dragging && shownLetters.isNotEmpty()) {
            val y = fraction * trackPx + with(density) { THUMB_HEIGHT.toPx() } / 2
            shownLetters[(y / rowPx).toInt().coerceIn(0, shownLetters.lastIndex)]
        } else null
        val letter = pointed ?: current
        val pointedNow by rememberUpdatedState(pointed)
        LaunchedEffect(pointed) {
            pointed?.let { letterItem[it] }?.let { state.scrollToItem(it) }
        }

        AnimatedVisibility(visible = dragging, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp)) {
            Column(
                Modifier
                    .width(INDEX_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (entry in shownLetters) {
                    val active = entry == letter
                    Text(
                        entry,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else null,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.width(INDEX_WIDTH),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = shown || dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) },
        ) {
            Box(Modifier.size(THUMB_TOUCH + BUBBLE_SIZE + 24.dp, THUMB_HEIGHT)) {
                if (dragging) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(BUBBLE_SIZE)
                            .offset(y = (THUMB_HEIGHT - BUBBLE_SIZE) / 2)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 6.dp, bottomStart = 32.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(THUMB_TOUCH, THUMB_HEIGHT)
                        .pointerInput(Unit) {
                            var job: Job? = null
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = listFractionNow
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / trackPxNow).coerceIn(0f, 1f)
                                // With an index the letter under the finger moves the list; without one, the thumb's place does, as in Music
                                if (pointedNow == null) {
                                    val target = (dragFraction * (count - 1)).roundToInt()
                                    job?.cancel()
                                    job = scope.launch { state.scrollToItem(target) }
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(4.dp, THUMB_HEIGHT)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
