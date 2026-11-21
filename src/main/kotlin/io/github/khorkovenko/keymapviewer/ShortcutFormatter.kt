package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.SystemInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.KeyStroke

/**
 * Renders shortcuts in the notation of the keymap's target OS, independent of the OS the IDE runs on:
 * macOS keymaps → "⌃⌥⇧⌘F", Windows/Linux keymaps → "Ctrl+Alt+Shift+F".
 */
object ShortcutFormatter {

    const val SEPARATOR = "  or  "

    private const val MODIFIER_MASK =
        InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.META_DOWN_MASK

    private val macKeys = mapOf(
        KeyEvent.VK_ENTER to "↩", KeyEvent.VK_BACK_SPACE to "⌫", KeyEvent.VK_DELETE to "⌦",
        KeyEvent.VK_ESCAPE to "⎋", KeyEvent.VK_TAB to "⇥", KeyEvent.VK_SPACE to "Space",
        KeyEvent.VK_LEFT to "←", KeyEvent.VK_RIGHT to "→", KeyEvent.VK_UP to "↑", KeyEvent.VK_DOWN to "↓",
        KeyEvent.VK_PAGE_UP to "⇞", KeyEvent.VK_PAGE_DOWN to "⇟", KeyEvent.VK_HOME to "↖", KeyEvent.VK_END to "↘",
    )

    fun format(shortcut: Shortcut, mac: Boolean): String = when {
        // Both this plugin's double modifiers and IntelliJ's own double-tap gestures read "Double Alt"
        DoubleModifier.anyKeyCodeOf(shortcut) != null -> "Double " + modifierName(DoubleModifier.anyKeyCodeOf(shortcut)!!, mac)
        else -> formatPlain(shortcut, mac)
    }

    /** "Ctrl", "Shift", "Alt", "Win"/"Meta" or, for macOS keymaps, "⌃", "⇧", "⌥", "⌘". */
    fun modifierName(keyCode: Int, mac: Boolean): String = when (keyCode) {
        KeyEvent.VK_CONTROL -> if (mac) "⌃" else "Ctrl"
        KeyEvent.VK_SHIFT -> if (mac) "⇧" else "Shift"
        KeyEvent.VK_ALT -> if (mac) "⌥" else "Alt"
        KeyEvent.VK_META -> if (mac) "⌘" else if (SystemInfo.isWindows) "Win" else "Meta"
        else -> KeymapUtil.getKeyText(keyCode)
    }

    /** Symbols used for special keys in macOS notation (also accepted when typing a shortcut). */
    val macKeySymbols: Map<Int, String> get() = macKeys

    private fun formatPlain(shortcut: Shortcut, mac: Boolean): String = when (shortcut) {
        is KeyboardShortcut -> buildString {
            append(format(shortcut.firstKeyStroke, mac))
            shortcut.secondKeyStroke?.let { append(", ").append(format(it, mac)) }
        }
        is MouseShortcut -> formatMouse(shortcut, mac)
        else -> KeymapUtil.getShortcutText(shortcut) // keyboard gestures
    }

    /** "Ctrl+Double Click" / "⌘Double Click", "Alt+Right Click", "Ctrl+Wheel Up". */
    fun formatMouse(sc: MouseShortcut, mac: Boolean): String {
        val m = sc.modifiers
        fun has(mask: Int) = m and mask != 0
        val what = when (sc.button) {
            MouseShortcut.BUTTON_WHEEL_UP -> "Wheel Up"
            MouseShortcut.BUTTON_WHEEL_DOWN -> "Wheel Down"
            else -> {
                val button = when (sc.button) {
                    MouseEvent.BUTTON1 -> ""
                    MouseEvent.BUTTON2 -> "Middle "
                    MouseEvent.BUTTON3 -> "Right "
                    else -> "Button ${sc.button} "
                }
                button + (if (sc.clickCount >= 2) "Double Click" else "Click")
            }
        }
        return if (mac) buildString {
            if (has(InputEvent.CTRL_DOWN_MASK)) append("⌃")
            if (has(InputEvent.ALT_DOWN_MASK)) append("⌥")
            if (has(InputEvent.SHIFT_DOWN_MASK)) append("⇧")
            if (has(InputEvent.META_DOWN_MASK)) append("⌘")
            append(what)
        } else buildList {
            if (has(InputEvent.CTRL_DOWN_MASK)) add("Ctrl")
            if (has(InputEvent.ALT_DOWN_MASK)) add("Alt")
            if (has(InputEvent.SHIFT_DOWN_MASK)) add("Shift")
            if (has(InputEvent.META_DOWN_MASK)) add(if (SystemInfo.isWindows) "Win" else "Meta")
            add(what)
        }.joinToString("+")
    }

    fun format(stroke: KeyStroke, mac: Boolean): String {
        val m = stroke.modifiers
        fun has(mask: Int) = m and mask != 0
        val key = (if (mac) macKeys[stroke.keyCode] else null) ?: KeymapUtil.getKeyText(stroke.keyCode)

        return if (mac) {
            buildString {
                if (has(InputEvent.CTRL_DOWN_MASK)) append("⌃")
                if (has(InputEvent.ALT_DOWN_MASK)) append("⌥")
                if (has(InputEvent.SHIFT_DOWN_MASK)) append("⇧")
                if (has(InputEvent.META_DOWN_MASK)) append("⌘")
                append(key)
            }
        } else {
            buildList {
                if (has(InputEvent.CTRL_DOWN_MASK)) add("Ctrl")
                if (has(InputEvent.ALT_DOWN_MASK)) add("Alt")
                if (has(InputEvent.SHIFT_DOWN_MASK)) add("Shift")
                if (has(InputEvent.META_DOWN_MASK)) add(if (SystemInfo.isWindows) "Win" else "Meta")
                add(key)
            }.joinToString("+")
        }
    }

    /** Keys pressed on this computer use this OS's modifier convention. */
    val currentOsIsMac: Boolean get() = SystemInfo.isMac

    /**
     * Converts a key combination pressed on this OS into the convention of the target keymap.
     * The only real difference between the platforms is the primary modifier: Ctrl on Windows/Linux, ⌘ on macOS,
     * so we swap Ctrl and Meta when the families differ (Ctrl+Shift+F pressed on Windows → ⌘⇧F for a macOS keymap).
     */
    fun translate(stroke: KeyStroke, toMac: Boolean): KeyStroke {
        if (toMac == currentOsIsMac) return stroke
        return KeyStroke.getKeyStroke(stroke.keyCode, swapCtrlMeta(stroke.modifiers))
    }

    /** Same as [translate] for any shortcut: keyboard (both keystrokes) and mouse. */
    fun translate(sc: Shortcut, toMac: Boolean): Shortcut {
        if (toMac == currentOsIsMac) return sc
        DoubleModifier.keyCodeOf(sc)?.let { return DoubleModifier.of(DoubleModifier.swapCtrlMeta(it)) }
        return when (sc) {
            is KeyboardShortcut -> KeyboardShortcut(translate(sc.firstKeyStroke, toMac), sc.secondKeyStroke?.let { translate(it, toMac) })
            is MouseShortcut -> MouseShortcut(sc.button, swapCtrlMeta(sc.modifiers), sc.clickCount)
            else -> sc
        }
    }

    /** Swaps Ctrl and Meta (⌘) in a modifier mask; Alt and Shift are unchanged. */
    fun swapCtrlMeta(modifiers: Int): Int {
        val m = modifiers and MODIFIER_MASK
        var out = m and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK).inv()
        if (m and InputEvent.CTRL_DOWN_MASK != 0) out = out or InputEvent.META_DOWN_MASK
        if (m and InputEvent.META_DOWN_MASK != 0) out = out or InputEvent.CTRL_DOWN_MASK
        return out
    }

    fun normalize(keyCode: Int, modifiersEx: Int): KeyStroke =
        KeyStroke.getKeyStroke(keyCode, modifiersEx and MODIFIER_MASK)
}
