package com.gamebex.client.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import com.gamebex.client.ui.SpaceGrotesk

/**
 * [focusRequester] lets a D-pad screen move real text focus into the field;
 * IME Search calls [onSubmit]. The clear target calls [onClear] when given,
 * else [onQuery](""). With [visibleHeight] (Home header) the visible box is
 * that tall, centered inside the taller hit box from [modifier].
 *
 * The rendered text is a hoisted `TextFieldValue` updated on every keystroke,
 * so a slow query echo can't scramble typing; [query] is adopted only when it
 * is an external change, not a value this field sent (see [adoptExternalQuery]).
 */
@Composable
fun ConsoleSearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    focused: Boolean,
    focusRequester: FocusRequester,
    onTap: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
    onClear: (() -> Unit)? = null,
    visibleHeight: Dp? = null,
    /** Real editor focus changes: true on focus gain. */
    onFocusChange: (Boolean) -> Unit = {},
    /** Home header only: a round (X) key-hint glyph while the field is empty. */
    showKeyHint: Boolean = false,
) {
    // External `query` changes (state swaps, store clears) are adopted
    // below; this field's own echoes are ignored.
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query))
    }
    var lastSent by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (adoptExternalQuery(query, value.text, lastSent)) {
            value = TextFieldValue(query, TextRange(query.length))
        }
    }
    ConsoleSearchField(
        value = value,
        onValueChange = { next ->
            value = next
            if (next.text != query) {
                lastSent = next.text
                onQuery(next.text)
            }
        },
        placeholder = placeholder,
        focused = focused,
        focusRequester = focusRequester,
        onTap = onTap,
        onSubmit = onSubmit,
        modifier = modifier,
        onClear = {
            value = TextFieldValue("")
            lastSent = ""
            if (onClear != null) onClear() else onQuery("")
        },
        visibleHeight = visibleHeight,
        onFocusChange = onFocusChange,
        showKeyHint = showKeyHint,
    )
}

/**
 * Only the last query this field sent counts as an echo. Older sent values
 * and "" are real external changes (Library's "Clear filters", Search's
 * recent-query picks).
 */
internal fun adoptExternalQuery(
    prop: String,
    text: String,
    lastSent: String?,
): Boolean = prop != text && prop != lastSent

/**
 * What the field should show after an external query change, and the
 * last-sent value that change leaves behind. Adopting updates last-sent:
 * otherwise a fill that was not typed (last-sent still "") treats the next
 * external "" as its own echo, and the old text sits over an unfiltered grid.
 */
internal data class FieldQuery(val text: String, val lastSent: String)

internal fun fieldAfterExternalQuery(prop: String, text: String, lastSent: String): FieldQuery =
    if (adoptExternalQuery(prop, text, lastSent)) FieldQuery(prop, prop) else FieldQuery(text, lastSent)

/** Controlled editor core: [value] is the caller's source of truth. */
@Composable
fun ConsoleSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focused: Boolean,
    focusRequester: FocusRequester,
    onTap: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
    onClear: (() -> Unit)? = null,
    visibleHeight: Dp? = null,
    /** Real editor focus changes: true on focus gain. */
    onFocusChange: (Boolean) -> Unit = {},
    /** Home header only: a round (X) key-hint glyph while the field is empty. */
    showKeyHint: Boolean = false,
) {
    val g = LocalConsoleGeometry.current
    val shape = RoundedCornerShape(g.x(ConsoleDesign.RADIUS_CARD))
    val clear: () -> Unit = onClear ?: { onValueChange(TextFieldValue("")) }
    val stroke = Modifier.border(
        if (focused) g.x(ConsoleDesign.FOCUS_STROKE) else g.x(1),
        if (focused) ConsoleColors.FocusRing else ConsoleColors.Steel,
        shape,
    )
    val hit = g.hitMin(g.x(84))
    val clipboard = LocalClipboardManager.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The field's `focused` semantics mirror the editor's real focus
    // (onFocusChanged), not the caller's zone guess.
    var editorFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        val frame = if (visibleHeight == null) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().height(visibleHeight)
        }
        Spacer(frame.clip(shape).background(ConsoleColors.Surface).then(stroke))
        // While text exists, the trailing spacer reserves the clear glyph's
        // cell so text doesn't run under it.
        Row(
            modifier = Modifier.fillMaxSize().padding(start = g.x(18)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search, null, tint = ConsoleColors.TextTertiary,
                modifier = Modifier.size(g.x(22)),
            )
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = g.x(14)),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        placeholder, color = ConsoleColors.TextTertiary,
                        fontSize = g.t(18), lineHeight = g.t(22), maxLines = 1,
                    )
                }
                // BasicTextField's semantics node sits at the text layout
                // (about 16 dp tall), below every layout modifier, and is its
                // own merging root, so `mergeDescendants` can't absorb it. This
                // full-height wrapper replaces it via `clearAndSetSemantics` and
                // re-exposes the text actions, giving readers a >= 48 dp node.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(hit)
                        .clearAndSetSemantics {
                            editableText = AnnotatedString(value.text)
                            textSelectionRange = value.selection
                            isEditable = true
                            this.focused = editorFocused
                            // clearAndSetSemantics drops the editor's click and
                            // IME actions; re-add them so a click starts
                            // editing and the IME action submits.
                            onClick { onTap(); true }
                            onImeAction(ImeAction.Search) { onSubmit(); true }
                            // Focus lives on the real editor underneath; the
                            // wrapper must still route focus requests there
                            // (TalkBack double-tap, compose-test text input).
                            this[androidx.compose.ui.semantics.SemanticsActions.RequestFocus] =
                                androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>(
                                    label = null,
                                ) {
                                    focusRequester.requestFocus()
                                    true
                                }
                            textEditingActions(
                                currentValue = { value },
                                onValueChange = onValueChange,
                                clipboard = clipboard,
                                layoutResult = { layoutResult },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        cursorBrush = SolidColor(ConsoleColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        textStyle = TextStyle(
                            color = ConsoleColors.TextPrimary, fontSize = g.t(18),
                            lineHeight = g.t(22), fontFamily = SpaceGrotesk,
                        ),
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            // Home's editing state follows real editor focus,
                            // so tapping the text itself enters it too, not
                            // only the outer tap.
                            .onFocusChanged {
                                editorFocused = it.isFocused
                                onFocusChange(it.isFocused)
                            },
                    )
                }
            }
            if (value.text.isNotEmpty()) {
                Spacer(Modifier.width(g.x(48)))
            }
        }
        // A `hitMin` hit box on the right edge; padding keeps the 20px glyph's
        // center g.x(28) from that edge.
        if (value.text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .sizeIn(minWidth = hit, minHeight = hit)
                    .clickable(onClick = clear),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Close, "Clear search", tint = ConsoleColors.TextSecondary,
                    modifier = Modifier.padding(end = g.x(18)).size(g.x(20)),
                )
            }
        } else if (showKeyHint) {
            // "Press X to search" hint, where the clear ✕ sits.
            Box(
                modifier = Modifier.align(Alignment.CenterEnd),
                contentAlignment = Alignment.CenterEnd,
            ) {
                ControllerGlyphBadge(
                    ControllerGlyph.X,
                    modifier = Modifier.padding(end = g.x(12)),
                )
            }
        }
    }
}

/**
 * Text actions for the wrapper's 48 dp semantics node. foundation gates
 * Copy / Cut / Paste on selection state and never exposes
 * GetTextLayoutResult, so all of them are re-exposed here.
 */
private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.textEditingActions(
    currentValue: () -> TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    clipboard: ClipboardManager,
    layoutResult: () -> TextLayoutResult?,
) {
    getTextLayoutResult { results -> layoutResult()?.let { results.add(it); true } ?: false }
    setText { text ->
        onValueChange(TextFieldValue(text.text, TextRange(text.text.length)))
        true
    }
    insertTextAtCursor { text ->
        val v = currentValue()
        // A backwards selection has start > end; replaceRange throws
        // without ordered bounds.
        val start = minOf(v.selection.start, v.selection.end)
        val end = maxOf(v.selection.start, v.selection.end)
        val newText = v.text.replaceRange(start, end, text.text)
        onValueChange(TextFieldValue(newText, TextRange(start + text.text.length)))
        true
    }
    setSelection { start, end, _ ->
        val v = currentValue()
        if (start == v.selection.start && end == v.selection.end) {
            false
        } else if (minOf(start, end) >= 0 && maxOf(start, end) <= v.text.length) {
            onValueChange(TextFieldValue(v.annotatedString, TextRange(start, end)))
            true
        } else {
            false
        }
    }
    copyText {
        val sel = currentValue().selection
        if (sel.collapsed) {
            false
        } else {
            clipboard.setText(AnnotatedString(currentValue().text.substring(sel.min, sel.max)))
            true
        }
    }
    cutText {
        val v = currentValue()
        val sel = v.selection
        if (sel.collapsed) {
            false
        } else {
            clipboard.setText(AnnotatedString(v.text.substring(sel.min, sel.max)))
            onValueChange(TextFieldValue(v.text.removeRange(sel.min, sel.max), TextRange(sel.min)))
            true
        }
    }
    pasteText {
        val clip = clipboard.getText() ?: return@pasteText false
        val v = currentValue()
        // Ordered bounds for a backwards selection, as above.
        val start = minOf(v.selection.start, v.selection.end)
        val end = maxOf(v.selection.start, v.selection.end)
        val newText = v.text.replaceRange(start, end, clip.text)
        onValueChange(TextFieldValue(newText, TextRange(start + clip.text.length)))
        true
    }
}
