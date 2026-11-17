package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import java.awt.EventQueue
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList

/** One action you ran with the mouse although it has a keyboard shortcut. */
data class MissedShortcut(val actionId: String, val count: Int, val lastMillis: Long)

/**
 * Counts "missed shortcuts": actions you run with the mouse (menu, toolbar, context menu, side-bar button)
 * although the active keymap has a keyboard shortcut for them. The side-bar button shows a red dot while
 * there are misses you haven't looked at yet.
 */
@Service(Service.Level.APP)
@State(name = "KeymapViewerMissedShortcuts", storages = [Storage("keymapViewerMissedShortcuts.xml")])
class MissedShortcutStore : PersistentStateComponent<MissedShortcutStore.Data>, Disposable {

    class Data {
        /** "action id<TAB>count<TAB>last time (ms)" */
        var entries: MutableList<String> = mutableListOf()
        /** Misses since the list was last opened (drives the red dot). */
        var unseen: Int = 0
    }

    private var data = Data()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // Tool windows opened with their keyboard shortcut must not count as missed when they appear
    private var lastKeyboardActivation: Pair<String, Long>? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    if (MacroPlayer.isPlaying) return
                    val id = ActionManager.getInstance().getId(action) ?: return
                    when (event.inputEvent) {
                        is KeyEvent -> if (id.startsWith("Activate") && id.endsWith("ToolWindow")) {
                            lastKeyboardActivation = id to System.currentTimeMillis()
                        }
                        // A mouse shortcut (Ctrl+Click → Go to Declaration) is a shortcut too, not a miss
                        is MouseEvent -> if (event.place != ActionPlaces.MOUSE_SHORTCUT && shortcutOf(id) != null) record(id)
                        else -> {} // run by the IDE itself or from a popup list: not a missed shortcut
                    }
                }
            })
    }

    override fun getState() = data
    override fun loadState(state: Data) { data = state }

    /** The first keyboard shortcut (or double modifier) of [actionId] in the active keymap, if any. */
    fun shortcutOf(actionId: String): Shortcut? {
        val keymap = KeymapManager.getInstance()?.activeKeymap ?: return null
        return KeymapData.shortcutsOf(keymap, actionId).firstOrNull { it is KeyboardShortcut }
    }

    val unseen: Int get() = data.unseen

    fun all(): List<MissedShortcut> = data.entries.mapNotNull { e ->
        val p = e.split('\t')
        if (p.size == 3) MissedShortcut(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0) else null
    }.sortedWith(compareByDescending<MissedShortcut> { it.lastMillis })

    fun record(actionId: String) {
        val old = all().firstOrNull { it.actionId == actionId }
        data.entries.removeAll { it.startsWith("$actionId\t") }
        data.entries.add("$actionId\t${(old?.count ?: 0) + 1}\t${System.currentTimeMillis()}")
        data.unseen++
        changed()
    }

    /** Called by the tool window listener: a tool window opened by clicking its side-bar button. */
    fun toolWindowClicked(toolWindow: ToolWindow) {
        if (MacroPlayer.isPlaying) return
        val actionId = "Activate" + toolWindow.id.replace(" ", "") + "ToolWindow"
        val kb = lastKeyboardActivation
        if (kb != null && kb.first == actionId && System.currentTimeMillis() - kb.second < 1500) return
        // Only a real click on a side-bar (stripe) button; not windows the IDE opens by itself (Run, Find…)
        val current = EventQueue.getCurrentEvent() as? MouseEvent ?: return
        if (!current.component.javaClass.name.contains("Stripe", ignoreCase = true)) return
        if (shortcutOf(actionId) != null) record(actionId)
    }

    fun markSeen() { if (data.unseen != 0) { data.unseen = 0; changed() } }

    fun clear() { data.entries.clear(); data.unseen = 0; changed() }

    fun remove(actionId: String) { data.entries.removeAll { it.startsWith("$actionId\t") }; changed() }

    /** Calls [listener] (on the UI thread) whenever the list changes, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun changed() {
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it() } }
    }

    override fun dispose() = listeners.clear()

    companion object {
        fun getInstance(): MissedShortcutStore = service()
    }
}

/** Per project: notices tool windows opened with a click on their side-bar button. */
object MissedShortcutTracker {
    fun install(project: Project) {
        val store = MissedShortcutStore.getInstance()
        // Tied to the store, so the listener goes away when the plugin is unloaded (and with the project)
        project.messageBus.connect(store).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) = store.toolWindowClicked(toolWindow)
        })
    }
}
