package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.EventObject
import javax.swing.AbstractCellEditor
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.TableCellEditor

/**
 * Lets you type a shortcut straight into the Shortcut cell ("Ctrl+Shift+F", "Double Shift", "Ctrl+K, Ctrl+K",
 * "Ctrl+Click"…). One click starts editing; while you type, a floating list suggests key names that match the word
 * under the cursor (↑/↓ to pick, Enter or Tab to insert, or click one). Enter or leaving the cell saves, Esc cancels.
 * Double-clicking the cell opens the "press the keys" dialog instead.
 *
 * @param isMac notation of the selected keymap, for the suggestions
 * @param onDoubleClick opens the edit dialog for the row being edited
 */
class ShortcutCellEditor(
    private val isMac: () -> Boolean,
    private val onDoubleClick: () -> Unit,
) : AbstractCellEditor(), TableCellEditor {

    private val field = JBTextField().apply {
        border = JBUI.Borders.empty(0, 4)
        emptyText.text = "e.g. ${ShortcutText.EXAMPLES}"
        toolTipText = "<html>Type a shortcut: ${ShortcutText.EXAMPLES}<br>" +
            "Separate several with “or”. Enter or leaving the cell saves, Esc cancels, double-click to press keys instead.</html>"
    }
    private val model = CollectionListModel<String>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
    }
    private var popup: JBPopup? = null
    private var userTyped = false // don't pop up for the text the cell starts with

    init {
        field.addActionListener { if (popup?.isVisible != true) stopCellEditing() }
        field.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (userTyped) com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { updatePopup() } }
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) { userTyped = true }
            override fun keyPressed(e: KeyEvent) {
                val open = popup?.isVisible == true && model.size > 0
                when {
                    open && e.keyCode == KeyEvent.VK_DOWN -> { move(1); e.consume() }
                    open && e.keyCode == KeyEvent.VK_UP -> { move(-1); e.consume() }
                    open && (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB) -> {
                        list.selectedValue?.let(::accept); e.consume()
                    }
                    open && e.keyCode == KeyEvent.VK_ESCAPE -> { hidePopup(); e.consume() }
                    e.keyCode == KeyEvent.VK_DOWN && e.isControlDown -> { userTyped = true; updatePopup(showAll = true); e.consume() }
                }
            }
        })
        field.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) { cancelCellEditing(); onDoubleClick() }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val i = list.locationToIndex(e.point)
                if (i >= 0) accept(model.getElementAt(i))
            }
        })
    }

    // ---- suggestions -----------------------------------------------------------------------------

    /** Start of the word under the cursor: after the last "+", ",", ";", "|" or " or ". */
    private fun tokenStart(): Int {
        val text = field.text.substring(0, field.caretPosition.coerceAtMost(field.text.length))
        val m = Regex("(\\+|,|;|\\||\\s+or\\s+)\\s*", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()
        return m?.range?.last?.plus(1) ?: text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    }

    private fun updatePopup(showAll: Boolean = false) {
        if (!field.isShowing) return hidePopup()
        val start = tokenStart()
        val token = field.text.substring(start, field.caretPosition.coerceIn(start, field.text.length)).trim()
        val all = ShortcutText.suggestions(isMac())
        val items = if (token.isEmpty()) all else {
            val t = token.lowercase()
            all.filter { it.lowercase().startsWith(t) } + all.filter { !it.lowercase().startsWith(t) && it.lowercase().contains(t) }
        }
        // Nothing left to suggest once the word is complete
        if (items.isEmpty() || (!showAll && items.size == 1 && items[0].equals(token, true))) return hidePopup()
        model.replaceAll(items)
        list.selectedIndex = 0
        list.ensureIndexIsVisible(0)

        if (popup?.isVisible != true) {
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(JBScrollPane(list), null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setCancelOnClickOutside(true)
                .setMinSize(JBUI.size(field.width.coerceAtLeast(180), 10))
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

    /** Replaces the word under the cursor with [name]; after a modifier a "+" is added so you can keep typing. */
    private fun accept(name: String) {
        val start = tokenStart()
        val end = field.caretPosition.coerceIn(start, field.text.length)
        val insert = name + if (ShortcutText.isModifierName(name)) "+" else ""
        field.text = field.text.substring(0, start) + insert + field.text.substring(end)
        field.caretPosition = start + insert.length
        hidePopup()
        field.requestFocusInWindow()
        if (insert.endsWith("+")) com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { updatePopup(showAll = true) }
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
    }

    // ---- TableCellEditor ---------------------------------------------------------------------------

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        userTyped = false
        field.text = value?.toString() ?: ""
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            field.requestFocusInWindow()
            field.selectAll()
        }
        return field
    }

    override fun getCellEditorValue(): Any = field.text

    /** One click starts editing; typing on a selected row too. */
    override fun isCellEditable(e: EventObject?): Boolean = e !is MouseEvent || e.clickCount >= 1

    override fun stopCellEditing(): Boolean { hidePopup(); return super.stopCellEditing() }
    override fun cancelCellEditing() { hidePopup(); super.cancelCellEditing() }
}
