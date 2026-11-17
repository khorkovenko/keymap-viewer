package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import java.awt.event.InputEvent
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.util.SystemInfo

/** Which OS a keymap is designed for. [isMac] decides the shortcut notation (⌘⇧F vs Ctrl+Shift+F). */
enum class TargetOs(val label: String, val isMac: Boolean) {
    MAC("macOS", true),
    WINDOWS_LINUX("Windows / Linux", false),
    LINUX("Linux", false),
    UNKNOWN("Any OS (not detected)", SystemInfo.isMac);

    companion object {
        val current: String
            get() = when {
                SystemInfo.isMac -> "macOS"
                SystemInfo.isWindows -> "Windows"
                else -> "Linux"
            }
    }
}

/** One row of the table: an action and the shortcuts bound to it (empty when unassigned). */
data class ShortcutRow(
    val actionName: String,
    val actionId: String,
    val shortcuts: String,
    /** Block this action belongs to in the Markdown view/export (see [ActionCategories]). */
    val category: String = ActionCategories.OTHER,
) {
    val isAssigned: Boolean get() = shortcuts.isNotEmpty()
}

/** Everything we show (and export) about one keymap. */
data class KeymapSummary(
    val name: String,
    val targetOs: TargetOs,
    val inheritance: List<String>,   // e.g. ["My Keymap", "macOS"]
    val isCustom: Boolean,
    val includesUnassigned: Boolean,
    val rows: List<ShortcutRow>,
    /** Block names in display order. */
    val categoryOrder: List<String> = emptyList(),
) {
    /** Rows split into blocks (main menu order, then Editor/Debugger/…/Other); empty blocks are left out. */
    fun rowsByCategory(): List<Pair<String, List<ShortcutRow>>> =
        rows.groupBy { it.category }.toList().sortedBy { (cat, _) ->
            categoryOrder.indexOf(cat).let { if (it < 0) categoryOrder.size else it }
        }

    val assignedCount: Int get() = rows.count { it.isAssigned }
}

object KeymapData {

    private val keymapManager: KeymapManagerEx get() = KeymapManagerEx.getInstanceEx()

    fun allKeymaps(): List<Keymap> =
        keymapManager.allKeymaps.sortedBy { it.presentableName.lowercase() }

    fun activeKeymap(): Keymap = keymapManager.activeKeymap

    fun findKeymap(name: String): Keymap? = keymapManager.getKeymap(name)

    /** The action's shortcuts in this keymap plus its double-modifier shortcut (stored by this plugin). */
    fun shortcutsOf(keymap: Keymap, actionId: String): List<Shortcut> {
        val double = DoubleModifierStore.getInstance().get(keymap.name, actionId)
        return keymap.getShortcuts(actionId).toList() + listOfNotNull(double?.let(DoubleModifier::of))
    }

    fun makeActive(keymap: Keymap) {
        keymapManager.activeKeymap = keymap
    }

    /**
     * Built-in keymaps carry the OS in their internal name ("Mac OS X 10.5+", "Default for XWin",
     * "Default for GNOME", "$default" ...). Custom keymaps inherit it from their parent,
     * so we walk up the parent chain until we find a recognisable name.
     */
    fun detectTargetOs(keymap: Keymap): TargetOs {
        val chain = generateSequence(keymap) { it.parent }.take(32).toList()
        // Some parents only say "Windows" in their display name, not their internal name
        fun named(k: Keymap) = osFromName(k.name) ?: osFromName(k.presentableName)
        // 1. The built-in keymap it is based on is the most reliable sign: custom names are free text
        //    ("macOS copy (Windows / Linux)" is a Windows keymap made from a macOS one)
        chain.filter { !it.canModify() }.firstNotNullOfOrNull(::named)?.let { return it }
        // 2. No built-in ancestor says: look at the shortcuts themselves
        guessFromShortcuts(keymap)?.let { return it }
        // 3. Last resort: the custom names
        return chain.filter { it.canModify() }.firstNotNullOfOrNull(::named) ?: TargetOs.UNKNOWN
    }

    /**
     * For keymaps whose names say nothing (e.g. a standalone "Custom_w11" without a parent): look at the
     * shortcuts themselves. macOS keymaps use ⌘ (Meta) as the main modifier, Windows/Linux keymaps use Ctrl.
     */
    private fun guessFromShortcuts(keymap: Keymap): TargetOs? {
        var ctrl = 0
        var meta = 0
        for (id in keymap.actionIdList) {
            for (sc in keymap.getShortcuts(id)) {
                val m = (sc as? KeyboardShortcut)?.firstKeyStroke?.modifiers ?: continue
                if (m and InputEvent.CTRL_DOWN_MASK != 0) ctrl++
                if (m and InputEvent.META_DOWN_MASK != 0) meta++
            }
        }
        if (ctrl + meta < 10) return null // too few shortcuts to tell
        return when {
            meta > ctrl * 2 -> TargetOs.MAC
            ctrl > meta * 2 -> TargetOs.WINDOWS_LINUX
            else -> null
        }
    }

    /** OS a keymap name points to ("Mac OS X 10.5+" → macOS, …), or null if the name doesn't say. */
    fun osFromName(name: String): TargetOs? {
        val n = name.lowercase()
        return when {
            // Whole words only: "Emacs" and "My machine" are not macOS keymaps
            MAC_NAME.containsMatchIn(n) -> TargetOs.MAC
            listOf("xwin", "gnome", "kde", "linux").any { it in n } -> TargetOs.LINUX
            n == "\$default" || "windows" in n -> TargetOs.WINDOWS_LINUX
            else -> null
        }
    }

    private val MAC_NAME = Regex("""\bmac(os)?\b|\bosx\b|\bos x\b""")

    /**
     * Actions whose shortcuts differ from the parent keymap's (changed, added or removed there), or null for a
     * keymap without a parent. Uses only the public Keymap API.
     */
    fun ownActionIds(keymap: Keymap): List<String>? {
        val parent = keymap.parent ?: return null
        return (keymap.actionIdList + parent.actionIdList).distinct()
            .filter { !keymap.getShortcuts(it).contentEquals(parent.getShortcuts(it)) }
    }

    /** Human-readable action name; falls back to the ID for actions that only get a name at runtime. */
    fun actionName(actionId: String): String =
        ActionManager.getInstance().getActionOrStub(actionId)
            ?.templatePresentation?.text?.takeIf { it.isNotBlank() } ?: actionId

    /**
     * @param includeUnassigned false → only actions that have a shortcut in this keymap;
     *                          true  → every registered action, so all keymaps produce a table of the same size.
     */
    fun summarize(keymap: Keymap, includeUnassigned: Boolean): KeymapSummary {
        val os = detectTargetOs(keymap)
        val actionManager = ActionManager.getInstance()
        val categories = ActionCategories.load()
        val doubles = DoubleModifierStore.getInstance().forKeymap(keymap.name)

        // Every registered action, not just the keymap's own entries: some actions have no entry of their own
        // but "use the shortcut of" another action, and keymap.getShortcuts() returns those too.
        // Both views use the same list, so "assigned" means the same thing in each.
        val all = actionManager.getActionIdList("").filter {
            // Skip menus/toolbars (groups) unless the keymap binds a shortcut to them
            actionManager.getActionOrStub(it) !is ActionGroup || keymap.getShortcuts(it).isNotEmpty()
        }
        val ids: Collection<String> = (all + keymap.actionIdList + doubles.keys).toSet()

        val rows = ids.mapNotNull { id ->
            val shortcuts = keymap.getShortcuts(id).toList() + listOfNotNull(doubles[id]?.let(DoubleModifier::of))
            if (shortcuts.isEmpty() && !includeUnassigned) return@mapNotNull null
            ShortcutRow(
                actionName = actionName(id),
                actionId = id,
                shortcuts = shortcuts.joinToString(ShortcutFormatter.SEPARATOR) { ShortcutFormatter.format(it, os.isMac) },
                category = categories.of(id),
            )
        }.sortedBy { it.actionName.lowercase() }

        return KeymapSummary(
            name = keymap.presentableName,
            targetOs = os,
            inheritance = generateSequence(keymap) { it.parent }.map { it.presentableName }.toList(),
            isCustom = keymap.canModify(),
            includesUnassigned = includeUnassigned,
            rows = rows,
            categoryOrder = categories.order,
        )
    }
}
