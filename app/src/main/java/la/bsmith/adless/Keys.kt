package la.bsmith.adless

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * For a popup opened by a long press: OK is still held when it appears, and
 * the repeats and the release would click the focused item. They are
 * swallowed until OK has been let go once; a fresh press (repeat count 0)
 * passes through.
 */
fun Modifier.ignoreHeldSelect(): Modifier = composed {
    var armed by remember { mutableStateOf(true) }
    onPreviewKeyEvent { event ->
        if (!armed || !event.isSelect()) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyUp -> { armed = false; true }
            event.nativeKeyEvent.repeatCount == 0 -> { armed = false; false }
            else -> true
        }
    }
}

fun KeyEvent.isSelect(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
