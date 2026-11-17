package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.KeyStroke

/**
 * "Double Ctrl", "Double Shift", "Double Alt", "Double ⌘": pressing a modifier key twice on its own.
 *
 * IntelliJ keymap files can't store these (the IDE registers its own, like double Shift for Search Everywhere,
 * in code). This plugin detects the taps itself. Inside the plugin a double modifier travels as the two-keystroke shortcut "Ctrl, Ctrl" made of bare
 * modifier keys, so dialogs, conflicts and undo treat it like any other shortcut; [KeymapEditSession] routes
 * it to [DoubleModifierStore] instead of the keymap.
 */
object DoubleModifier {

    val modifierKeys = listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_META)

    fun of(keyCode: Int): KeyboardShortcut =
        KeyboardShortcut(KeyStroke.getKeyStroke(keyCode, 0), KeyStroke.getKeyStroke(keyCode, 0))

    /** The modifier key code if [sc] is a double modifier, otherwise null. */
    fun keyCodeOf(sc: Shortcut?): Int? {
        val k = sc as? KeyboardShortcut ?: return null
        val second = k.secondKeyStroke ?: return null
        val code = k.firstKeyStroke.keyCode
        return code.takeIf { it in modifierKeys && second.keyCode == it }
    }

    fun isDouble(sc: Shortcut?) = keyCodeOf(sc) != null

    /** Ctrl ↔ ⌘ for moving between macOS and Windows/Linux. */
    fun swapCtrlMeta(keyCode: Int) = when (keyCode) {
        KeyEvent.VK_CONTROL -> KeyEvent.VK_META
        KeyEvent.VK_META -> KeyEvent.VK_CONTROL
        else -> keyCode
    }

    /** Built-in IDE features that already use a double modifier, so the user can be warned. */
    fun builtInUse(keyCode: Int): String? = when (keyCode) {
        KeyEvent.VK_SHIFT -> "Search Everywhere"
        KeyEvent.VK_CONTROL -> "Run Anything"
        else -> null
    }
}

/**
 * Remembers double-modifier shortcuts per keymap and runs the active keymap's ones when you double-tap
 * (they work only while this plugin is installed and are not part of exported keymap .xml files).
 */
@Service(Service.Level.APP)
@State(name = "KeymapViewerDoubleModifiers", storages = [Storage("keymapViewerDoubleModifiers.xml")])
class DoubleModifierStore : PersistentStateComponent<DoubleModifierStore.Data>, Disposable {

    class Data {
        /** One entry per binding: "keymap name<TAB>action id<TAB>modifier key code". */
        var entries: MutableList<String> = mutableListOf()
    }

    private var data = Data()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(KeymapManagerListener.TOPIC, object : KeymapManagerListener {
                override fun activeKeymapChanged(keymap: Keymap?) = apply()
            })
    }

    override fun getState() = data
    override fun loadState(state: Data) { data = state; apply() }

    private fun parse() = data.entries.mapNotNull { e ->
        val p = e.split('\t')
        if (p.size == 3) Triple(p[0], p[1], p[2].toIntOrNull() ?: return@mapNotNull null) else null
    }

    /** action ID → modifier key code for [keymapName]. */
    fun forKeymap(keymapName: String): Map<String, Int> =
        parse().filter { it.first == keymapName }.associate { it.second to it.third }

    fun get(keymapName: String, actionId: String): Int? = forKeymap(keymapName)[actionId]

    /** Sets (or with null removes) the double modifier of an action; one per action. */
    fun set(keymapName: String, actionId: String, keyCode: Int?) {
        data.entries.removeAll { it.startsWith("$keymapName\t$actionId\t") }
        if (keyCode != null) data.entries.add("$keymapName\t$actionId\t$keyCode")
        apply()
    }

    fun copyKeymap(from: String, to: String, swapCtrlMeta: Boolean = false) {
        forKeymap(from).forEach { (id, code) ->
            data.entries.add("$to\t$id\t${if (swapCtrlMeta) DoubleModifier.swapCtrlMeta(code) else code}")
        }
        apply()
    }

    fun removeKeymap(name: String) {
        data.entries.removeAll { it.startsWith("$name\t") }
        apply()
    }

    /** An action got a new ID (a renamed macro), or with [toId] null was deleted: its double modifiers follow. */
    fun moveAction(fromId: String, toId: String?) {
        val mine = parse().filter { it.second == fromId }
        if (mine.isEmpty()) return
        data.entries.removeAll { e -> e.split('\t').getOrNull(1) == fromId }
        if (toId != null) mine.forEach { (keymap, _, code) -> data.entries.add("$keymap\t$toId\t$code") }
        apply()
    }

    /** Re-reads which actions the active keymap binds to double modifiers. */
    fun apply() {
        val active = KeymapManager.getInstance()?.activeKeymap?.name
        activeBindings = if (active == null) emptyMap()
        else forKeymap(active).entries.groupBy({ it.value }, { it.key })
    }

    override fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(tapListener)
    }

    // ---- detecting double taps ----------------------------------------------------------------------
    // Uses plain AWT key events and the public ActionManager API (no IDE-internal classes).

    /** modifier key code → action IDs bound to its double tap in the active keymap. */
    @Volatile
    private var activeBindings: Map<Int, List<String>> = emptyMap()
    private var tapCandidate: Int? = null
    private var lastTapKey: Int? = null
    private var lastTapTime = 0L

    private val tapListener = AWTEventListener { event ->
        if (event is MouseEvent) {
            // Ctrl+Click, Ctrl+Click (or scrolling with Ctrl held) is not a double tap of Ctrl
            if (event.id == MouseEvent.MOUSE_PRESSED || event.id == MouseEvent.MOUSE_WHEEL) { tapCandidate = null; lastTapKey = null }
            return@AWTEventListener
        }
        val e = event as? KeyEvent ?: return@AWTEventListener
        if (activeBindings.isEmpty()) return@AWTEventListener
        if (e.keyCode !in DoubleModifier.modifierKeys) {
            if (e.id == KeyEvent.KEY_PRESSED) { tapCandidate = null; lastTapKey = null } // another key: not a tap
            return@AWTEventListener
        }
        val ownMask = when (e.keyCode) {
            KeyEvent.VK_CONTROL -> KeyEvent.CTRL_DOWN_MASK
            KeyEvent.VK_SHIFT -> KeyEvent.SHIFT_DOWN_MASK
            KeyEvent.VK_ALT -> KeyEvent.ALT_DOWN_MASK
            else -> KeyEvent.META_DOWN_MASK
        }
        val allMods = KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.META_DOWN_MASK
        when (e.id) {
            KeyEvent.KEY_PRESSED -> {
                if (e.modifiersEx and allMods != ownMask) { tapCandidate = null; lastTapKey = null; return@AWTEventListener }
                if (tapCandidate == e.keyCode) return@AWTEventListener // auto-repeat while held
                if (lastTapKey == e.keyCode && e.`when` - lastTapTime < 400) {
                    lastTapKey = null; tapCandidate = null
                    runBound(e.keyCode, e)
                    return@AWTEventListener
                }
                tapCandidate = e.keyCode
            }
            KeyEvent.KEY_RELEASED -> {
                if (tapCandidate == e.keyCode) { lastTapKey = e.keyCode; lastTapTime = e.`when` }
                tapCandidate = null
            }
        }
    }

    init {
        // Declared after the listener property: Kotlin initializes properties and init blocks in order
        Toolkit.getDefaultToolkit().addAWTEventListener(tapListener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
    }

    private fun runBound(keyCode: Int, e: KeyEvent) {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focus is ShortcutCaptureField) return // the user is recording a shortcut, not using one
        val ids = activeBindings[keyCode] ?: return
        val manager = ActionManager.getInstance()
        ApplicationManager.getApplication().invokeLater {
            ids.forEach { id ->
                manager.getAction(id)?.let { manager.tryToExecute(it, e, focus, ActionPlaces.KEYBOARD_SHORTCUT, true) }
            }
        }
    }

    companion object {
        fun getInstance(): DoubleModifierStore = service()
    }
}

/** Registers the saved double-modifier shortcuts when the IDE starts. */
class DoubleModifierStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        DoubleModifierStore.getInstance().apply()
        MacroStore.getInstance().registerActions() // saved macros become actions with their shortcuts
        MissedShortcutStore.getInstance() // starts counting missed shortcuts
        MissedShortcutTracker.install(project) // side-bar button clicks count too
    }
}
