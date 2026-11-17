package sh.hnet.contacts.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onPress] when a finger touches down and [onMove] when the touch turns into a scroll (it
 * moves beyond the touch slop, or the list takes it), without taking the touch: the row's own tap
 * and the list's scroll go on as before.
 */
fun Modifier.onTouchDown(key: Any, onPress: () -> Unit, onMove: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress()
        val slop = viewConfiguration.touchSlop
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                onMove()
                break
            }
        }
    }
}
