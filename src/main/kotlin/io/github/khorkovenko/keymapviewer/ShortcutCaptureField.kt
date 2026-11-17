package io.github.khorkovenko.keymapviewer

import com.intellij.ide.KeyboardAwareFocusOwner
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.ui.components.JBTextField
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * A text field that records the shortcut you perform instead of typing text:
 *  • keys: pressing Ctrl, then Shift, then F records Ctrl+Shift+F (pure modifier presses are ignored);
 *  • mouse: clicking inside the field records a mouse shortcut, e.g. Ctrl+Click, Ctrl+Double Click,
 *    Alt+Right Click or Ctrl+Wheel Up. A plain single left click only focuses the field.
 * Plain Backspace/Delete clears the field; plain Enter/Esc confirm/cancel the dialog.
 */
class ShortcutCaptureField(private val render: (Shortcut) -> String) : JBTextField(), KeyboardAwareFocusOwner {

    /** What was performed, in this computer's modifier convention (translation happens in [ShortcutInput]). */
    var shortcut: Shortcut? = null
        private set

    var onChange: (Shortcut?) -> Unit = {}

    /** When on, a key combination pressed after the first one becomes the second step (Ctrl+K, Ctrl+K). */
    var twoSteps = false

    // Double-tap detection for Double Ctrl / Shift / Alt / ⌘
    private var tapCandidate: Int? = null
    private var lastTapKey: Int? = null
    private var lastTapTime = 0L

    private val ignoredKeys = setOf(
        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META,
        KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_WINDOWS, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_UNDEFINED,
    )

    init {
        isEditable = false
        focusTraversalKeysEnabled = false
        emptyText.text = "Press keys, or click here with a modifier (e.g. Ctrl+Click)"
        columns = 24

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                tapCandidate = null; lastTapKey = null // Ctrl+Click, Ctrl+Click is not "Double Ctrl"
                val modifiers = e.modifiersEx and MODIFIERS
                val button = MouseShortcut.getButton(e)
                // A plain single left click is how you focus the field, so it isn't recorded
                if (button == MouseEvent.BUTTON1 && e.clickCount == 1 && modifiers == 0) return
                // A second press of a double click replaces the single click recorded a moment ago
                setShortcut(MouseShortcut(button, modifiers, e.clickCount.coerceIn(1, 2)))
                e.consume()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val modifiers = e.modifiersEx and MODIFIERS
                if (modifiers == 0) { parent?.dispatchEvent(e); return } // let plain scrolling scroll the dialog
                setShortcut(MouseShortcut(MouseShortcut.getButton(e), modifiers, 1))
                e.consume()
            }
        }
        addMouseListener(mouse)
        addMouseWheelListener(mouse)
    }

    /**
     * The IDE runs keymap shortcuts (Ctrl+C → Copy…) before the focused component sees the key, so a shortcut
     * that's already assigned could never be recorded. This tells the IDE to leave keys to the field, except
     * plain Enter / Esc, which still save / cancel the dialog.
     */
    override fun skipKeyEventDispatcher(event: KeyEvent): Boolean {
        val plain = event.modifiersEx and MODIFIERS == 0
        return !(plain && (event.keyCode == KeyEvent.VK_ENTER || event.keyCode == KeyEvent.VK_ESCAPE))
    }

    override fun processKeyEvent(e: KeyEvent) {
        val plain = e.modifiersEx and MODIFIERS == 0

        // Plain Enter / Esc are not recorded: they confirm / cancel the dialog.
        // (They can still be recorded together with a modifier, e.g. Ctrl+Enter.)
        if (plain && (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_ESCAPE)) {
            super.processKeyEvent(e)
            return
        }

        if (e.keyCode in DoubleModifier.modifierKeys) {
            handleModifierTap(e)
            e.consume()
            return
        }

        if (e.id == KeyEvent.KEY_PRESSED && e.keyCode !in ignoredKeys) {
            tapCandidate = null; lastTapKey = null
            // Plain Backspace / Delete clears the field when it shows something; pressed on an empty field,
            // it is recorded as the shortcut itself.
            if (plain && shortcut != null && (e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE)) {
                setShortcut(null)
            } else {
                val stroke = ShortcutFormatter.normalize(e.keyCode, e.modifiersEx)
                val current = shortcut
                setShortcut(
                    if (twoSteps && current is KeyboardShortcut && current.secondKeyStroke == null && !DoubleModifier.isDouble(current))
                        KeyboardShortcut(current.firstKeyStroke, stroke)
                    else KeyboardShortcut(stroke, null)
                )
            }
        }
        e.consume() // never let other keys reach the dialog or the IDE
    }

    /** A modifier pressed and released on its own twice within half a second records "Double Ctrl" etc. */
    private fun handleModifierTap(e: KeyEvent) {
        val ownMask = when (e.keyCode) {
            KeyEvent.VK_CONTROL -> KeyEvent.CTRL_DOWN_MASK
            KeyEvent.VK_SHIFT -> KeyEvent.SHIFT_DOWN_MASK
            KeyEvent.VK_ALT -> KeyEvent.ALT_DOWN_MASK
            else -> KeyEvent.META_DOWN_MASK
        }
        when (e.id) {
            KeyEvent.KEY_PRESSED -> {
                if (e.modifiersEx and MODIFIERS != ownMask) { tapCandidate = null; lastTapKey = null; return } // with other modifiers
                if (tapCandidate == e.keyCode) return // key auto-repeat while held
                if (lastTapKey == e.keyCode && e.`when` - lastTapTime < 500) {
                    lastTapKey = null; tapCandidate = null
                    setShortcut(DoubleModifier.of(e.keyCode))
                    return
                }
                tapCandidate = e.keyCode
            }
            KeyEvent.KEY_RELEASED -> {
                if (tapCandidate == e.keyCode) { lastTapKey = e.keyCode; lastTapTime = e.`when` }
                tapCandidate = null
            }
        }
    }

    private companion object {
        const val MODIFIERS = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or
            KeyEvent.SHIFT_DOWN_MASK or KeyEvent.META_DOWN_MASK
    }

    fun setShortcut(sc: Shortcut?) {
        shortcut = sc
        rerender()
        onChange(sc)
    }

    /** Re-draw the text, e.g. after the "translate Ctrl ↔ ⌘" option changed. */
    fun rerender() {
        text = shortcut?.let(render) ?: ""
    }
}
