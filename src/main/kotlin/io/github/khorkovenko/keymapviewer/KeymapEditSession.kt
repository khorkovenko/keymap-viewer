package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap

/**
 * Applies shortcut changes to a keymap and remembers every touched action's original shortcuts,
 * so the whole edit (assignment + all conflict fixes) can be rolled back with [undoAll].
 */
class KeymapEditSession(val keymap: Keymap) {

    private val originals = LinkedHashMap<String, Array<Shortcut>>()
    private val originalDoubles = LinkedHashMap<String, Int?>()
    private val doubles get() = DoubleModifierStore.getInstance()

    var removedFromOthers = 0
        private set
    var reassigned = 0
        private set
    var keptConflicts = 0

    private fun remember(actionId: String) {
        originals.getOrPut(actionId) { keymap.getShortcuts(actionId).copyOf() }
        if (actionId !in originalDoubles) originalDoubles[actionId] = doubles.get(keymap.name, actionId)
    }

    // Double modifiers (Double Ctrl…) can't live in a keymap, so they go to DoubleModifierStore
    private fun put(actionId: String, sc: Shortcut) {
        val code = DoubleModifier.keyCodeOf(sc)
        if (code != null) doubles.set(keymap.name, actionId, code) else keymap.addShortcut(actionId, sc)
    }

    private fun take(actionId: String, sc: Shortcut) {
        if (DoubleModifier.isDouble(sc)) doubles.set(keymap.name, actionId, null) else keymap.removeShortcut(actionId, sc)
    }

    fun add(actionId: String, shortcut: Shortcut) {
        remember(actionId)
        put(actionId, shortcut)
    }

    fun remove(actionId: String, shortcut: Shortcut, countAsConflictFix: Boolean = false) {
        remember(actionId)
        take(actionId, shortcut)
        if (countAsConflictFix) removedFromOthers++
    }

    fun reassign(actionId: String, old: Shortcut, new: Shortcut) {
        remember(actionId)
        take(actionId, old)
        put(actionId, new)
        reassigned++
    }

    fun removeAll(actionId: String) {
        remember(actionId)
        keymap.removeAllActionShortcuts(actionId)
        doubles.set(keymap.name, actionId, null)
    }

    fun undoAll() {
        originals.forEach { (id, shortcuts) ->
            keymap.removeAllActionShortcuts(id)
            shortcuts.forEach { keymap.addShortcut(id, it) }
        }
        originalDoubles.forEach { (id, code) -> doubles.set(keymap.name, id, code) }
        originals.clear()
        originalDoubles.clear()
        removedFromOthers = 0; reassigned = 0; keptConflicts = 0
    }
}
