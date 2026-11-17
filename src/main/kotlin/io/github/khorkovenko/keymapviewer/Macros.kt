package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.KeyboardFocusManager
import java.util.Base64

/** One recorded step: run an IDE action, or type text into the editor. */
sealed interface MacroStep {
    data class Action(val actionId: String) : MacroStep
    data class Typing(val text: String) : MacroStep
    /** Open (activate) a tool window, e.g. by clicking its button on the side bar. */
    data class ToolWindow(val id: String) : MacroStep
    /** Press a key combination in whatever has the focus (e.g. Enter in a popup), not an IDE action. */
    data class Key(val stroke: javax.swing.KeyStroke) : MacroStep

    /** How the step is shown in the recording window and the editor list. */
    fun describe(): String = when (this) {
        is Action -> "▶  ${KeymapData.actionName(actionId)}" +
            (if (KeymapData.actionName(actionId) != actionId) "   ($actionId)" else "")
        is Typing -> "⌨  Type “${text.replace("\n", "⏎").replace("\t", "⇥")}”"
        is ToolWindow -> "▣  Open tool window “$id”"
        is Key -> "⌨  Press ${ShortcutFormatter.format(stroke, ShortcutFormatter.currentOsIsMac)}"
    }
}

data class Macro(val name: String, val steps: List<MacroStep>) {
    val actionId: String get() = MacroStore.PREFIX + name
}

/**
 * Keeps the user's macros and registers each one as a normal IDE action ("Macro: <name>"), so it can get
 * shortcuts in any keymap, appears in this plugin's table and in Find Action.
 * (IntelliJ's own macro system is internal API, so the plugin has its own.)
 */
@Service(Service.Level.APP)
@State(name = "KeymapViewerMacros", storages = [Storage("keymapViewerMacros.xml")])
class MacroStore : PersistentStateComponent<MacroStore.Data>, Disposable {

    class Data {
        /** One entry per macro: name, then steps ("A:<action id>" or "T:<base64 text>"), separated by U+001F. */
        var macros: MutableList<String> = mutableListOf()
    }

    private var data = Data()
    private val registered = HashSet<String>()

    override fun getState() = data
    override fun loadState(state: Data) { data = state; registerActions() }

    fun all(): List<Macro> = data.macros.mapNotNull(::decode).sortedBy { it.name.lowercase() }

    fun get(name: String): Macro? = all().firstOrNull { it.name == name }

    /** Saves [macro]; if it was renamed from [oldName], the old one is replaced and its shortcuts move along. */
    fun save(macro: Macro, oldName: String?) {
        if (oldName != null && oldName != macro.name) {
            moveShortcuts(MacroStore.PREFIX + oldName, macro.actionId)
            remove(oldName, keepShortcuts = true)
        }
        data.macros.removeAll { decode(it)?.name == macro.name }
        data.macros.add(encode(macro))
        registerActions()
    }

    fun remove(name: String, keepShortcuts: Boolean = false) {
        data.macros.removeAll { decode(it)?.name == name }
        if (!keepShortcuts) {
            // Don't leave shortcuts pointing at a macro that no longer exists
            KeymapManager.getInstance().let { km ->
                (km as? com.intellij.openapi.keymap.ex.KeymapManagerEx)?.allKeymaps
                    ?.filter { it.canModify() }
                    ?.forEach { it.removeAllActionShortcuts(PREFIX + name) }
            }
        }
        registerActions()
    }

    private fun moveShortcuts(fromId: String, toId: String) {
        val km = com.intellij.openapi.keymap.ex.KeymapManagerEx.getInstanceEx()
        km.allKeymaps.filter { it.canModify() }.forEach { k ->
            val own = k.getShortcuts(fromId)
            if (own.isNotEmpty()) {
                k.removeAllActionShortcuts(fromId)
                own.forEach { k.addShortcut(toId, it) }
            }
        }
    }

    /** (Re-)registers one IDE action per macro and removes actions of deleted macros. */
    fun registerActions() {
        val manager = ActionManager.getInstance()
        val wanted = all().associateBy { it.actionId }
        (registered - wanted.keys).forEach { manager.unregisterAction(it) }
        registered.retainAll(wanted.keys)
        wanted.forEach { (id, macro) ->
            if (id in registered) manager.unregisterAction(id) // steps may have changed
            manager.registerAction(id, MacroAction(macro))
            registered += id
        }
    }

    override fun dispose() {
        val manager = ActionManager.getInstance()
        registered.forEach { manager.unregisterAction(it) }
        registered.clear()
    }

    private fun encode(m: Macro): String = (listOf(m.name) + m.steps.map {
        when (it) {
            is MacroStep.Action -> "A:${it.actionId}"
            is MacroStep.Typing -> "T:" + Base64.getEncoder().encodeToString(it.text.toByteArray())
            is MacroStep.ToolWindow -> "W:${it.id}"
            is MacroStep.Key -> "K:${it.stroke}"
        }
    }).joinToString(SEP)

    private fun decode(s: String): Macro? {
        val parts = s.split(SEP)
        val name = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val steps = parts.drop(1).mapNotNull { p ->
            when {
                p.startsWith("A:") -> MacroStep.Action(p.substring(2))
                p.startsWith("W:") -> MacroStep.ToolWindow(p.substring(2))
                p.startsWith("K:") -> javax.swing.KeyStroke.getKeyStroke(p.substring(2))?.let { MacroStep.Key(it) }
                p.startsWith("T:") -> runCatching { MacroStep.Typing(String(Base64.getDecoder().decode(p.substring(2)))) }.getOrNull()
                else -> null
            }
        }
        return Macro(name, steps)
    }

    companion object {
        const val PREFIX = "KeymapViewer.Macro."
        private const val SEP = "\u001F"
        fun getInstance(): MacroStore = service()
    }
}

/** The IDE action a macro is registered as; running it plays the macro back. */
private class MacroAction(private val macro: Macro) :
    DumbAwareAction("Macro: ${macro.name}", "Plays the recorded macro “${macro.name}” (${macro.steps.size} steps)", AllIcons.Ide.Macro.Recording_1) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !MacroPlayer.isPlaying && !MacroRecorder.isRecording
    }

    override fun actionPerformed(e: AnActionEvent) = MacroPlayer.play(macro, e.dataContext)
}

/** Plays macro steps one after another, each in the component that has the focus at that moment. */
object MacroPlayer {
    @Volatile
    var isPlaying = false
        private set

    fun play(macro: Macro, context: DataContext) {
        if (isPlaying || macro.steps.isEmpty()) return
        isPlaying = true
        step(macro.steps, 0, context)
    }

    private fun currentContext(fallback: DataContext): DataContext {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return fallback
        return DataManager.getInstance().getDataContext(focus)
    }

    private fun step(steps: List<MacroStep>, index: Int, fallback: DataContext) {
        if (index >= steps.size) { isPlaying = false; return }
        val next = { ApplicationManager.getApplication().invokeLater { step(steps, index + 1, fallback) } }
        try {
            when (val s = steps[index]) {
                is MacroStep.Typing -> {
                    val ctx = currentContext(fallback)
                    val editor = CommonDataKeys.EDITOR.getData(ctx)
                    if (editor != null) s.text.forEach { TypedAction.getInstance().actionPerformed(editor, it, ctx) }
                    next()
                }
                is MacroStep.Key -> {
                    // Posted through the event queue so the IDE handles it like a real key press
                    val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    if (focus != null) {
                        val queue = java.awt.Toolkit.getDefaultToolkit().systemEventQueue
                        val now = System.currentTimeMillis()
                        val ch = if (s.stroke.modifiers and (java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.META_DOWN_MASK or java.awt.event.InputEvent.ALT_DOWN_MASK) == 0 &&
                            s.stroke.keyCode == java.awt.event.KeyEvent.VK_ENTER) '\n' else java.awt.event.KeyEvent.CHAR_UNDEFINED
                        queue.postEvent(java.awt.event.KeyEvent(focus, java.awt.event.KeyEvent.KEY_PRESSED, now, s.stroke.modifiers, s.stroke.keyCode, ch))
                        queue.postEvent(java.awt.event.KeyEvent(focus, java.awt.event.KeyEvent.KEY_RELEASED, now + 1, s.stroke.modifiers, s.stroke.keyCode, ch))
                    }
                    next()
                }
                is MacroStep.ToolWindow -> {
                    val project = CommonDataKeys.PROJECT.getData(currentContext(fallback))
                    val window = project?.let { ToolWindowManager.getInstance(it).getToolWindow(s.id) }
                    if (window == null || !window.isAvailable) next() else window.activate({ next() }, true)
                }
                is MacroStep.Action -> {
                    val manager = ActionManager.getInstance()
                    val action = manager.getAction(s.actionId)
                    // Missing actions (plugin removed) and macros calling macros are skipped
                    if (action == null || s.actionId.startsWith(MacroStore.PREFIX)) { next(); return }
                    val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    manager.tryToExecute(action, null, focus, ActionPlaces.KEYBOARD_SHORTCUT, true).doWhenProcessed { next() }
                }
            }
        } catch (e: Exception) {
            isPlaying = false
            throw e
        }
    }
}
