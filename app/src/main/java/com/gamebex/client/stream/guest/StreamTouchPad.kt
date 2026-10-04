package com.gamebex.client.stream.guest

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamebex.client.ui.SpaceGrotesk
import com.gamebex.client.ui.console.ConsoleColors

/** Local controls sit above the picture; only their hit areas consume touches, never the video. */
@Composable
internal fun StreamTouchPad(
    onTouchKey: (Int, Boolean, Long) -> Unit,
    onTouchAxes: (Float, Float, Long) -> Unit,
    onReleaseTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyCallback by rememberUpdatedState(onTouchKey)
    val axesCallback by rememberUpdatedState(onTouchAxes)
    val releaseCallback by rememberUpdatedState(onReleaseTouchInput)
    val pad = remember {
        TouchPadState(
            onKey = { key, down, atNs -> keyCallback(key, down, atNs) },
            onStick = { x, y, atNs -> axesCallback(x, y, atNs) },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, pad) {
        fun release() {
            pad.releaseAll(System.nanoTime())
            releaseCallback()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    active = false
                    release()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release()
        }
    }
    if (!active) return

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag(TAG_TOUCH_PAD)) {
        val wide = maxWidth >= 540.dp
        val keySize = if (maxHeight < 260.dp) 44.dp else 48.dp
        Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PadKey("L", KeyEvent.KEYCODE_BUTTON_L1, pad, Modifier.size(64.dp, 40.dp))
                Text(
                    "GameBEX controls",
                    color = ConsoleColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontSize = 12.sp,
                    modifier = Modifier.background(ConsoleColors.Background.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(4.dp),
                )
                PadKey("R", KeyEvent.KEYCODE_BUTTON_R1, pad, Modifier.size(64.dp, 40.dp))
            }
            if (!wide) {
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadKey("Select", KeyEvent.KEYCODE_BUTTON_SELECT, pad, Modifier.size(76.dp, 40.dp))
                    PadKey("Start", KeyEvent.KEYCODE_BUTTON_START, pad, Modifier.size(76.dp, 40.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    Dpad(pad, keySize)
                    if (wide) LeftStick(pad, keySize * 2)
                }
                if (wide) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadKey("Select", KeyEvent.KEYCODE_BUTTON_SELECT, pad, Modifier.size(76.dp, 40.dp))
                        PadKey("Start", KeyEvent.KEYCODE_BUTTON_START, pad, Modifier.size(76.dp, 40.dp))
                    }
                }
                FaceKeys(pad, keySize)
            }
        }
    }
}

@Composable
private fun Dpad(pad: TouchPadState, size: Dp) {
    Box(Modifier.size(size * 3)) {
        PadKey("↑", KeyEvent.KEYCODE_DPAD_UP, pad, Modifier.align(Alignment.TopCenter).size(size), "Up")
        PadKey("↓", KeyEvent.KEYCODE_DPAD_DOWN, pad, Modifier.align(Alignment.BottomCenter).size(size), "Down")
        PadKey("←", KeyEvent.KEYCODE_DPAD_LEFT, pad, Modifier.align(Alignment.CenterStart).size(size), "Left")
        PadKey("→", KeyEvent.KEYCODE_DPAD_RIGHT, pad, Modifier.align(Alignment.CenterEnd).size(size), "Right")
    }
}

@Composable
private fun FaceKeys(pad: TouchPadState, size: Dp) {
    Box(Modifier.size(size * 3)) {
        PadKey("Y", KeyEvent.KEYCODE_BUTTON_Y, pad, Modifier.align(Alignment.TopCenter).size(size))
        PadKey("A", KeyEvent.KEYCODE_BUTTON_A, pad, Modifier.align(Alignment.BottomCenter).size(size))
        PadKey("X", KeyEvent.KEYCODE_BUTTON_X, pad, Modifier.align(Alignment.CenterStart).size(size))
        PadKey("B", KeyEvent.KEYCODE_BUTTON_B, pad, Modifier.align(Alignment.CenterEnd).size(size))
    }
}

@Composable
private fun PadKey(label: String, key: Int, pad: TouchPadState, modifier: Modifier, description: String = label) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .testTag(touchKeyTag(key))
            .background((if (pressed) ConsoleColors.AccentShade else ConsoleColors.Surface).copy(alpha = 0.65f), shape)
            .border(1.dp, (if (pressed) ConsoleColors.Accent else ConsoleColors.TextSecondary).copy(alpha = 0.7f), shape)
            .semantics {
                contentDescription = "$description · GameBEX control"
                role = Role.Button
                selected = pressed
            }
            .pointerInput(pad, key) {
                val held = mutableSetOf<Long>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                val id = change.id.value
                                val inside = change.position.x >= 0f && change.position.x < size.width &&
                                    change.position.y >= 0f && change.position.y < size.height
                                if (change.changedToDownIgnoreConsumed() && inside) {
                                    held.add(id)
                                    pad.press(id, key, System.nanoTime())
                                }
                                if (id in held) {
                                    change.consume()
                                    if (!change.pressed || !inside) {
                                        held.remove(id)
                                        pad.release(id, System.nanoTime())
                                    }
                                }
                            }
                            pressed = held.isNotEmpty()
                        }
                    }
                } finally {
                    held.forEach { pad.release(it, System.nanoTime()) }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = SpaceGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ConsoleColors.TextPrimary)
    }
}

@Composable
private fun LeftStick(pad: TouchPadState, size: Dp) {
    var axes by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        Modifier.size(size).testTag(TAG_TOUCH_STICK)
            .semantics { contentDescription = "Left stick · GameBEX control" }
            .pointerInput(pad) {
                var pointer: Long? = null
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (pointer == null && change.changedToDownIgnoreConsumed()) pointer = change.id.value
                                if (pointer == change.id.value) {
                                    change.consume()
                                    if (change.pressed) {
                                        val radius = minOf(this@pointerInput.size.width, this@pointerInput.size.height) / 2f
                                        val (x, y) = touchStickAxes(change.position.x - radius, change.position.y - radius, radius)
                                        pad.moveStick(change.id.value, x, y, System.nanoTime())
                                        axes = Offset(x, y)
                                    } else {
                                        pad.release(change.id.value, System.nanoTime())
                                        pointer = null
                                        axes = Offset.Zero
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    pointer?.let { pad.release(it, System.nanoTime()) }
                    axes = Offset.Zero
                }
            },
    ) {
        val radius = this.size.minDimension / 2f
        drawCircle(ConsoleColors.Surface.copy(alpha = 0.65f), radius)
        drawCircle(ConsoleColors.TextSecondary.copy(alpha = 0.7f), radius - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
        drawLine(ConsoleColors.TextSecondary.copy(alpha = 0.4f), Offset(center.x - radius * 0.55f, center.y), Offset(center.x + radius * 0.55f, center.y))
        drawLine(ConsoleColors.TextSecondary.copy(alpha = 0.4f), Offset(center.x, center.y - radius * 0.55f), Offset(center.x, center.y + radius * 0.55f))
        drawCircle(ConsoleColors.Accent.copy(alpha = 0.75f), radius * 0.3f, center + axes * (radius * 0.65f))
    }
}

internal const val TAG_TOUCH_PAD = "stream-touch-pad"
internal const val TAG_TOUCH_STICK = "stream-touch-stick"
internal fun touchKeyTag(key: Int) = "stream-touch-key-$key"
