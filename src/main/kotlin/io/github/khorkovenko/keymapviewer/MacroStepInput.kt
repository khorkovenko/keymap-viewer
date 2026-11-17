package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * A text field for adding macro steps by hand, with a floating list of suggestions while you type
 * (like the Shortcut cell in the table):
 *   • an action name or ID ("reformat", "SaveAll") → run that action
 *   • "type: some text" → type the text into the editor
 *   • "window: database" → open that tool window
 * ↑/↓ pick a suggestion, Enter or Tab adds it, Esc closes the list.
 */
class MacroStepInput(private val project: Project, private val onAdd: (MacroStep) -> Unit) {

    private data class Suggestion(val label: String, val detail: String, val step: MacroStep)

    val field = JBTextField().apply {
        emptyText.text = "Add a step: action name, “type: text”, “key: Enter” or “window: name”, then Enter"
        toolTipText = "<html>Type to add a step by hand:<br>" +
            "• an action name or ID, e.g. <b>reformat</b> or <b>SaveAll</b><br>" +
            "• <b>type: hello</b>, to type text into the editor<br>" +
            "• <b>window: database</b>, to open a tool window<br>" +
            "• <b>key: Enter</b> or <b>key: Ctrl+S</b>, to press keys in whatever has the focus<br>" +
            "↑/↓ choose a suggestion, Enter or Tab adds it, Esc closes the list.</html>"
    }
    private val model = CollectionListModel<Suggestion>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
        cellRenderer = object : ColoredListCellRenderer<Suggestion>() {
            override fun customizeCellRenderer(list: JList<out Suggestion>, value: Suggestion?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                icon = when (value.step) {
                    is MacroStep.Action -> AllIcons.Actions.Execute
                    is MacroStep.Typing -> AllIcons.FileTypes.Text
                    is MacroStep.ToolWindow -> AllIcons.Actions.MoveToWindow
                    is MacroStep.Key -> AllIcons.General.Keyboard
                }
                append(value.label)
                if (value.detail.isNotEmpty()) append("   ${value.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val i = locationToIndex(e.point)
                if (i >= 0) accept(model.getElementAt(i))
            }
        })
    }
    private var popup: JBPopup? = null

    private val actions: List<Suggestion> by lazy {
        val manager = ActionManager.getInstance()
        manager.getActionIdList("")
            .filter { !it.startsWith(MacroStore.PREFIX) && manager.getActionOrStub(it) !is ActionGroup }
            .map { Suggestion(KeymapData.actionName(it), it, MacroStep.Action(it)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun toolWindows(): List<Suggestion> {
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.mapNotNull { manager.getToolWindow(it) }
            .map { Suggestion("Open tool window “${it.stripeTitle}”", "tool window", MacroStep.ToolWindow(it.id)) }
    }

    private fun suggestions(query: String): List<Suggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        Regex("^type:\\s?(.*)$", RegexOption.IGNORE_CASE).find(q)?.let {
            val text = it.groupValues[1]
            return if (text.isEmpty()) emptyList() else listOf(Suggestion("Type “$text”", "into the editor", MacroStep.Typing(text)))
        }
        Regex("^key:\\s*(.*)$", RegexOption.IGNORE_CASE).find(q)?.let {
            val text = it.groupValues[1].trim()
            val r = ShortcutText.parse(text, ShortcutFormatter.currentOsIsMac)
            val sc = (r as? ShortcutText.Result.Ok)?.shortcuts?.firstOrNull() as? com.intellij.openapi.actionSystem.KeyboardShortcut
            return if (text.isEmpty() || sc == null || DoubleModifier.isDouble(sc)) emptyList()
            else listOf(Suggestion("Press ${ShortcutFormatter.format(sc.firstKeyStroke, ShortcutFormatter.currentOsIsMac)}",
                "key press (not an action)", MacroStep.Key(sc.firstKeyStroke)))
        }
        Regex("^(?:window|tool window|tw):\\s*(.*)$", RegexOption.IGNORE_CASE).find(q)?.let {
            val name = it.groupValues[1]
            return toolWindows().filter { s -> name.isEmpty() || s.label.contains(name, true) }
        }

        fun rank(list: List<Suggestion>) =
            list.filter { it.label.startsWith(q, true) || it.detail.startsWith(q, true) } +
                list.filter { !(it.label.startsWith(q, true) || it.detail.startsWith(q, true)) && (it.label.contains(q, true) || it.detail.contains(q, true)) }

        return (rank(actions).take(60) + rank(toolWindows()).take(5) +
            Suggestion("Type “$q”", "into the editor (or write type: …)", MacroStep.Typing(q)))
    }

    init {
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                ApplicationManager.getApplication().invokeLater { updatePopup() }
            }
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val open = popup?.isVisible == true && model.size > 0
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> if (open) { move(1); e.consume() }
                    KeyEvent.VK_UP -> if (open) { move(-1); e.consume() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> if (open) {
                        list.selectedValue?.let(::accept); e.consume()
                    } else if (field.text.isNotBlank()) e.consume() // never let Enter close the dialog by accident
                    KeyEvent.VK_ESCAPE -> if (open) { hidePopup(); e.consume() }
                }
            }
        })
    }

    private fun updatePopup() {
        if (!field.isShowing) return hidePopup()
        val items = suggestions(field.text)
        if (items.isEmpty()) return hidePopup()
        model.replaceAll(items)
        list.selectedIndex = 0
        list.ensureIndexIsVisible(0)
        if (popup?.isVisible != true) {
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(JBScrollPane(list), null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setCancelOnClickOutside(true)
                .setMinSize(JBUI.size(field.width.coerceAtLeast(260), 10))
                .createPopup()
            placeNextTo(popup!!, field)
        } else {
            popup!!.pack(false, true)
            placeNextTo(popup!!, field) // keeps it attached to the field when its height changes
        }
    }

    private fun move(delta: Int) {
        val i = (list.selectedIndex + delta).coerceIn(0, model.size - 1)
        list.selectedIndex = i
        list.ensureIndexIsVisible(i)
    }

    private fun accept(s: Suggestion) {
        onAdd(s.step)
        field.text = ""
        hidePopup()
        field.requestFocusInWindow()
    }

    fun hidePopup() {
        popup?.cancel()
        popup = null
    }
}
