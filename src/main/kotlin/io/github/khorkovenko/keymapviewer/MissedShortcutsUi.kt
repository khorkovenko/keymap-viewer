package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.text.DateFormat
import java.util.Date
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 * [base] with a red count badge ("1"…"9", "9+") in its top-right corner, like the IDE's notification badges.
 * Built as a [LayeredIcon] so the side bar can still scale it and recolor it when the button is selected.
 */
object CountBadgeIcon {
    val MISSED_ICON: Icon = IconLoader.getIcon("/icons/missedShortcuts.svg", CountBadgeIcon::class.java)

    private val cache = HashMap<Pair<Icon, Int>, Icon>()

    fun of(base: Icon, count: Int): Icon {
        if (count <= 0) return base
        val text = if (count > 9) "9+" else count.toString()
        return cache.getOrPut(base to minOf(count, 10)) {
            LayeredIcon(2).apply {
                setIcon(base, 0)
                setIcon(BadgeLayer(text), 1, SwingConstants.NORTH_EAST)
            }
        }
    }

    /** The red pill with the number; sizes are in logical pixels times [scale]. */
    private class BadgeLayer(private val text: String, private val scale: Float = 1f) : ScalableIcon {
        override fun getScale() = scale
        override fun scale(scaleFactor: Float): Icon = if (scaleFactor == scale) this else BadgeLayer(text, scaleFactor)
        override fun getIconHeight() = JBUIScale.scale(9f * scale).toInt()
        override fun getIconWidth() = maxOf(iconHeight, JBUIScale.scale((if (text.length > 1) 13f else 9f) * scale).toInt())

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val w = iconWidth
                val h = iconHeight
                g2.color = BADGE_COLOR
                g2.fillRoundRect(x, y, w, h, h, h)
                g2.font = JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(7.5f * scale))
                val fm = g2.fontMetrics
                g2.color = Color.WHITE
                g2.drawString(text, x + (w - fm.stringWidth(text) + 1) / 2, y + (h + fm.ascent - fm.descent) / 2)
            } finally {
                g2.dispose()
            }
        }
    }

    private val BADGE_COLOR = JBColor(0xE55765, 0xDB5C5C)
}

/** Toolbar button that opens the Missed Shortcuts dialog; shows how many misses you haven't looked at yet. */
class ShowMissedShortcutsAction : DumbAwareAction(
    "Missed Shortcuts", "Actions you ran with the mouse although they have a keyboard shortcut", CountBadgeIcon.MISSED_ICON,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val unseen = MissedShortcutStore.getInstance().unseen
        e.presentation.icon = CountBadgeIcon.of(CountBadgeIcon.MISSED_ICON, unseen)
        e.presentation.text = if (unseen > 0) "Missed Shortcuts ($unseen new)" else "Missed Shortcuts"
    }
    override fun actionPerformed(e: AnActionEvent) = MissedShortcutsDialog(e.project).show()
}

/** Lists the actions you ran with the mouse although they have a keyboard shortcut, most recent first. */
class MissedShortcutsDialog(project: Project?) : DialogWrapper(project) {

    private val store = MissedShortcutStore.getInstance()
    private val model = Model()
    private val table = JBTable(model).apply {
        setShowGrid(false)
        columnModel.getColumn(0).preferredWidth = 170
        columnModel.getColumn(1).preferredWidth = 260
        columnModel.getColumn(2).preferredWidth = 60
        columnModel.getColumn(3).preferredWidth = 150
        emptyText.text = "No missed shortcuts. Nice!"
    }

    private val clearAction = object : AbstractAction("Clear List") {
        override fun actionPerformed(e: ActionEvent) { store.clear(); reload() }
    }
    private val forgetAction = object : AbstractAction("Remove Selected") {
        override fun actionPerformed(e: ActionEvent) {
            table.selectedRows.map { model.rows[table.convertRowIndexToModel(it)].actionId }.forEach(store::remove)
            reload()
        }
    }

    init {
        title = "Missed Shortcuts"
        setOKButtonText("Close")
        table.autoCreateRowSorter = true
        table.selectionModel.addListSelectionListener { forgetAction.isEnabled = table.selectedRowCount > 0 }
        forgetAction.isEnabled = false
        reload()
        store.markSeen() // the red dot goes away once you've looked
        init()
    }

    private fun reload() {
        model.rows = store.all()
        clearAction.isEnabled = model.rows.isNotEmpty()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JBLabel("<html>Actions you ran with the mouse (menu, toolbar, side-bar button) that have a keyboard shortcut " +
            "in your active keymap. Try the shortcut next time!</html>").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { preferredSize = JBUI.size(660, 300) }, BorderLayout.CENTER)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun createLeftSideActions(): Array<Action> = arrayOf(clearAction, forgetAction)

    private inner class Model : AbstractTableModel() {
        private val columns = arrayOf("Shortcut", "Action", "Times", "Last missed")
        private val mac = KeymapData.detectTargetOs(KeymapData.activeKeymap()).isMac
        var rows: List<MissedShortcut> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 2) Integer::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any = with(rows[row]) {
            when (column) {
                0 -> store.shortcutOf(actionId)?.let { ShortcutFormatter.format(it, mac) } ?: "—"
                1 -> KeymapData.actionName(actionId)
                2 -> count
                else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastMillis))
            }
        }
    }
}
