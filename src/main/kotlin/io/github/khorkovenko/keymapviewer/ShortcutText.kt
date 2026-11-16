package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.KeyStroke

/**
 * Parses shortcuts typed as text, in the same notation the table shows:
 *   Ctrl+Shift+F   ·   ⌘⇧F   ·   Ctrl+K, Ctrl+K (two steps)   ·   Double Shift / Shift+Shift
 *   Ctrl+Click   ·   Ctrl+Double Click   ·   Alt+Right Click   ·   Ctrl+Wheel Up
 * Several shortcuts are separated by "or" (also ";" or "|").
 */
object ShortcutText {

    sealed interface Result {
        data class Ok(val shortcuts: List<Shortcut>) : Result
        data class Error(val message: String) : Result
    }

    const val EXAMPLES = "Ctrl+Shift+F  ·  Ctrl+K, Ctrl+K  ·  Double Shift  ·  Ctrl+Click  ·  Ctrl+Double Click"

    private val MODIFIERS: Map<String, Int> = buildMap {
        listOf("ctrl", "control", "ctl", "⌃").forEach { put(it, KeyEvent.VK_CONTROL) }
        listOf("alt", "option", "opt", "⌥").forEach { put(it, KeyEvent.VK_ALT) }
        listOf("shift", "⇧").forEach { put(it, KeyEvent.VK_SHIFT) }
        listOf("cmd", "command", "meta", "win", "windows", "super", "⌘").forEach { put(it, KeyEvent.VK_META) }
    }

    private fun maskOf(modifierKey: Int) = when (modifierKey) {
        KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
        KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
        KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
        else -> InputEvent.META_DOWN_MASK
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s_\\-]"), "")

    /** Every key name we understand → key code (VK_ names, IDE key texts, macOS symbols, common aliases). */
    private val KEYS: Map<String, Int> by lazy {
        val map = HashMap<String, Int>()
        for (f in KeyEvent::class.java.fields) {
            if (!f.name.startsWith("VK_") || f.type != Int::class.javaPrimitiveType) continue
            val code = f.getInt(null)
            if (code in DoubleModifier.modifierKeys || code == KeyEvent.VK_UNDEFINED) continue
            map.putIfAbsent(norm(f.name.removePrefix("VK_")), code)
            runCatching { KeymapUtil.getKeyText(code) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { map.putIfAbsent(norm(it), code) }
            KeyEvent.getKeyText(code).takeIf { it.isNotBlank() }?.let { map.putIfAbsent(norm(it), code) }
        }
        ShortcutFormatter.macKeySymbols.forEach { (code, sym) -> map[norm(sym)] = code }
        mapOf(
            "esc" to KeyEvent.VK_ESCAPE, "return" to KeyEvent.VK_ENTER, "del" to KeyEvent.VK_DELETE,
            "ins" to KeyEvent.VK_INSERT, "pgup" to KeyEvent.VK_PAGE_UP, "pgdn" to KeyEvent.VK_PAGE_DOWN,
            "pagedn" to KeyEvent.VK_PAGE_DOWN, "backspace" to KeyEvent.VK_BACK_SPACE, "bksp" to KeyEvent.VK_BACK_SPACE,
            "plus" to KeyEvent.VK_PLUS, "menu" to KeyEvent.VK_CONTEXT_MENU, "printscreen" to KeyEvent.VK_PRINTSCREEN,
            "apostrophe" to KeyEvent.VK_QUOTE, "backtick" to KeyEvent.VK_BACK_QUOTE, "grave" to KeyEvent.VK_BACK_QUOTE,
            "dot" to KeyEvent.VK_PERIOD,
        ).forEach { (k, v) -> map[k] = v }
        map
    }

    /** Names offered in the suggestion popup while typing (modifiers first, then keys, then mouse). */
    fun suggestions(mac: Boolean): List<String> = buildList {
        if (mac) addAll(listOf("⌘", "⌥", "⌃", "⇧", "Cmd", "Option", "Ctrl", "Shift"))
        else addAll(listOf("Ctrl", "Alt", "Shift", "Win", "Meta"))
        val mods = if (mac) listOf("⌘", "⌥", "⌃", "⇧") else listOf("Ctrl", "Alt", "Shift")
        mods.forEach { add("Double $it") }
        addAll(listOf("Enter", "Backspace", "Tab", "Space", "Escape", "Delete", "Insert", "Home", "End",
            "Page Up", "Page Down", "Up", "Down", "Left", "Right"))
        (1..24).forEach { add("F$it") }
        ('A'..'Z').forEach { add(it.toString()) }
        ('0'..'9').forEach { add(it.toString()) }
        addAll(listOf("Comma", "Period", "Slash", "Back Slash", "Semicolon", "Quote", "Back Quote", "Minus", "Equals",
            "Open Bracket", "Close Bracket", "Pause", "Print Screen", "Context Menu", "Caps Lock", "Num Lock", "Scroll Lock"))
        (0..9).forEach { add("NumPad-$it") }
        addAll(listOf("Click", "Double Click", "Right Click", "Right Double Click", "Middle Click",
            "Button 4 Click", "Button 5 Click", "Wheel Up", "Wheel Down"))
    }

    /** Modifier names, so the editor can append "+" after accepting one. */
    fun isModifierName(name: String) = norm(name) in MODIFIERS

    /**
     * @param existing the action's current shortcuts: a piece typed exactly as one of them is shown keeps it as is
     *                 (so shortcuts the parser can't express, like keyboard gestures, survive an edit).
     */
    fun parse(text: String, mac: Boolean, existing: List<Shortcut> = emptyList()): Result {
        val known = existing.associateBy { ShortcutFormatter.format(it, mac).trim().lowercase() }
        val pieces = text.split(Regex("\\s+or\\s+|;|\\|", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Shortcut>()
        for (p in pieces) {
            known[p.lowercase()]?.let { out += it; continue }
            try {
                out += parseOne(p)
            } catch (e: IllegalArgumentException) {
                return Result.Error("“$p”: ${e.message}")
            }
        }
        if (out.count { DoubleModifier.isDouble(it) } > 1) return Result.Error("Only one double-modifier shortcut per action is possible.")
        return Result.Ok(out.distinct())
    }

    private sealed interface Stroke {
        data class Key(val stroke: KeyStroke) : Stroke
        data class BareModifier(val keyCode: Int) : Stroke
        data class Mouse(val shortcut: MouseShortcut) : Stroke
        data class Double(val keyCode: Int) : Stroke
    }

    private fun parseOne(text: String): Shortcut {
        // "Ctrl+K, Ctrl+K": a comma not directly after "+" separates the two steps ("Ctrl+," is the comma key)
        val steps = text.split(Regex("(?<=[^+]),\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        require(steps.size in 1..2) { "a shortcut has at most two steps (e.g. Ctrl+K, Ctrl+K)" }
        val strokes = steps.map(::parseStroke)

        if (strokes.size == 1) return when (val s = strokes[0]) {
            is Stroke.Key -> KeyboardShortcut(s.stroke, null)
            is Stroke.Mouse -> s.shortcut
            is Stroke.Double -> DoubleModifier.of(s.keyCode)
            is Stroke.BareModifier -> throw IllegalArgumentException(
                "a modifier alone can't be a shortcut. Use “Double ${steps[0]}” for pressing it twice, or add a key (${steps[0]}+F)")
        }
        val (a, b) = strokes
        if (a is Stroke.BareModifier && b is Stroke.BareModifier && a.keyCode == b.keyCode) return DoubleModifier.of(a.keyCode)
        require(a is Stroke.Key && b is Stroke.Key) { "both steps of a two-step shortcut must be key combinations (e.g. Ctrl+K, Ctrl+K)" }
        return KeyboardShortcut(a.stroke, b.stroke)
    }

    private fun parseStroke(raw: String): Stroke {
        var s = raw.trim()
        Regex("^double\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val code = MODIFIERS[norm(m.groupValues[1])]
                ?: throw IllegalArgumentException("“Double” works with Ctrl, Shift, Alt or ${"⌘"}/Win (e.g. Double Shift)")
            return Stroke.Double(code)
        }

        // macOS notation: leading symbols without "+" (⌘⇧F)
        val mods = ArrayList<Int>()
        while (s.length > 1 && s[0] in "⌃⌥⇧⌘") {
            mods += MODIFIERS.getValue(s[0].toString()); s = s.substring(1).trimStart('+', ' ')
        }

        // "Ctrl++" means the "+" key
        val tokens = if (s.endsWith("++")) s.dropLast(2).split('+') .filter { it.isNotBlank() } + "+"
        else s.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "nothing to read" }

        val last = tokens.last()
        tokens.dropLast(1).forEach { t ->
            mods += MODIFIERS[norm(t)] ?: throw IllegalArgumentException("“$t” is not a modifier (Ctrl, Alt, Shift, ${"⌘"}/Win)")
        }

        MODIFIERS[norm(last)]?.let { code ->
            // "Ctrl+Ctrl" = double Ctrl; a lone "Ctrl" is only valid as a step of "Ctrl, Ctrl"
            if (mods.isEmpty()) return Stroke.BareModifier(code)
            if (mods.size == 1 && mods[0] == code) return Stroke.Double(code)
            throw IllegalArgumentException("ends with a modifier; add a key, e.g. ${tokens.joinToString("+")}+F")
        }
        val mask = mods.fold(0) { acc, m -> acc or maskOf(m) }

        parseMouse(last, mask)?.let { return Stroke.Mouse(it) }

        val code = keyCode(last) ?: throw IllegalArgumentException("unknown key “$last”")
        return Stroke.Key(KeyStroke.getKeyStroke(code, mask))
    }

    private fun parseMouse(token: String, mask: Int): MouseShortcut? {
        val t = token.trim().lowercase()
        Regex("^wheel\\s*(up|down)$").find(t)?.let {
            return MouseShortcut(if (it.groupValues[1] == "up") MouseShortcut.BUTTON_WHEEL_UP else MouseShortcut.BUTTON_WHEEL_DOWN, mask, 1)
        }
        val m = Regex("^(?:(left|middle|right|button\\s*(\\d+))\\s+)?(double\\s+)?click$").find(t) ?: return null
        val button = when {
            m.groupValues[1] == "middle" -> MouseEvent.BUTTON2
            m.groupValues[1] == "right" -> MouseEvent.BUTTON3
            m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
            else -> MouseEvent.BUTTON1
        }
        return MouseShortcut(button, mask, if (m.groupValues[3].isNotEmpty()) 2 else 1)
    }

    private fun keyCode(token: String): Int? {
        KEYS[norm(token)]?.let { return it }
        // Single printable ASCII characters: "F", "7", ",", "-", "/" …
        if (token.length == 1 && token[0].code in 33..126) {
            val c = KeyEvent.getExtendedKeyCodeForChar(token[0].code)
            if (c != KeyEvent.VK_UNDEFINED) return c
        }
        return null
    }
}
