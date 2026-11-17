package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

private const val BASE = DialogWrapper.NEXT_USER_EXIT_CODE

// Summary dialog exit codes
private const val SUMMARY_REMOVE_ALL = BASE
private const val SUMMARY_REVIEW = BASE + 1
private const val SUMMARY_KEEP = BASE + 2
private const val SUMMARY_UNDO = BASE + 3

// Step dialog exit codes
private const val STEP_REASSIGN = BASE + 10
private const val STEP_REMOVE = BASE + 11
private const val STEP_KEEP = BASE + 12
private const val STEP_REMOVE_REST = BASE + 13
private const val STEP_UNDO_ALL = BASE + 14

/** One action that also uses a shortcut we just assigned. */
data class Conflict(val actionId: String, val shortcut: Shortcut)

/**
 * Resolves conflicts after a shortcut was assigned.
 *
 * Step 1, a summary: "⌘⇧F is also used by 3 actions" with four choices:
 *   • Remove From Others: strip the shortcut from every conflicting action (most common, default button)
 *   • Review One by One…: go through the conflicts one at a time (step 2)
 *   • Keep Both: leave everything as is (IntelliJ allows duplicates; context decides which action wins)
 *   • Undo Assignment: roll back as if nothing happened
 *
 * Step 2, one window per conflict ("Conflict 2 of 5") with five choices:
 *   • Reassign and Next: give that action a new shortcut (new conflicts it causes are queued too)
 *   • Remove and Next: take the shortcut away from that action
 *   • Keep and Next: leave this one alone
 *   • Remove From All Remaining: remove it from this and every later conflict, then finish
 *   • Undo Everything: roll back the assignment and every change made while resolving
 * Closing the window stops reviewing and keeps the remaining conflicts unchanged.
 */
class ConflictResolver(
    private val project: Project,
    private val session: KeymapEditSession,
    private val os: TargetOs,
) {
    private val keymap get() = session.keymap

    /** @return false if the user chose to undo everything. */
    fun resolve(assignedActionId: String, assigned: Shortcut, conflicts: List<Conflict>): Boolean {
        if (conflicts.isEmpty()) return true
        val summary = SummaryDialog(assignedActionId, assigned, conflicts)
        summary.show()
        return when (summary.exitCode) {
            SUMMARY_REMOVE_ALL -> { conflicts.forEach { session.remove(it.actionId, it.shortcut, true) }; true }
            SUMMARY_REVIEW -> reviewOneByOne(conflicts)
            SUMMARY_KEEP -> { session.keptConflicts += conflicts.size; true }
            else -> { session.undoAll(); false } // Undo Assignment, Esc or window closed
        }
    }

    /** Applies a choice already made in the edit dialog. @return false if the user chose to undo everything. */
    fun resolve(conflicts: List<Conflict>, choice: ConflictChoice): Boolean {
        if (conflicts.isEmpty()) return true
        return when (choice) {
            ConflictChoice.REMOVE_FROM_OTHERS -> { conflicts.forEach { session.remove(it.actionId, it.shortcut, true) }; true }
            ConflictChoice.KEEP_BOTH -> { session.keptConflicts += conflicts.size; true }
            ConflictChoice.REVIEW -> reviewOneByOne(conflicts)
        }
    }

    private fun reviewOneByOne(initial: List<Conflict>): Boolean {
        val queue = ArrayDeque(initial)
        val seen = initial.toMutableSet()
        var index = 0
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            index++
            val step = StepDialog(c, index, total = index + queue.size)
            step.show()
            when (step.exitCode) {
                STEP_REASSIGN -> {
                    val newShortcut = step.newShortcut ?: continue
                    session.reassign(c.actionId, c.shortcut, newShortcut)
                    // Cascade: the new shortcut may collide with other actions, so queue those too
                    ShortcutInput.conflictsOf(keymap, c.actionId, newShortcut).forEach { (id, list) ->
                        list.map { Conflict(id, it) }.filter { seen.add(it) }.forEach(queue::addLast)
                    }
                }
                STEP_REMOVE -> session.remove(c.actionId, c.shortcut, true)
                STEP_KEEP -> session.keptConflicts++
                STEP_REMOVE_REST -> {
                    session.remove(c.actionId, c.shortcut, true)
                    queue.forEach { session.remove(it.actionId, it.shortcut, true) }
                    queue.clear()
                }
                STEP_UNDO_ALL -> { session.undoAll(); return false }
                else -> { session.keptConflicts += 1 + queue.size; return true } // window closed: keep the rest
            }
        }
        return true
    }

    private fun fmt(sc: Shortcut) = ShortcutFormatter.format(sc, os.isMac)

    private fun customAction(name: String, dialog: DialogWrapper, code: Int, default: Boolean = false) =
        object : AbstractAction(name) {
            init { if (default) putValue(DialogWrapper.DEFAULT_ACTION, true) }
            override fun actionPerformed(e: ActionEvent) = dialog.close(code)
        }

    // ---------------------------------------------------------------------------------------------

    private inner class SummaryDialog(
        private val actionId: String,
        private val shortcut: Shortcut,
        private val conflicts: List<Conflict>,
    ) : DialogWrapper(project) {

        init {
            title = "Shortcut Conflict"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val header = JBLabel(
                "<html><b>${fmt(shortcut)}</b> is now assigned to <b>${KeymapData.actionName(actionId)}</b>,<br>" +
                    "but ${conflicts.size} other action(s) use it too:</html>"
            )
            val list = JBList(conflicts.map {
                "${KeymapData.actionName(it.actionId)}   —   ${fmt(it.shortcut)}   (${it.actionId})"
            }).apply { visibleRowCount = minOf(8, conflicts.size) }
            val hint = JBLabel("When shortcuts collide, the current context decides which action runs.").apply {
                foreground = UIUtil.getContextHelpForeground()
            }
            return JPanel(BorderLayout(0, 8)).apply {
                border = JBUI.Borders.empty(4)
                add(header, BorderLayout.NORTH)
                add(JBScrollPane(list), BorderLayout.CENTER)
                add(hint, BorderLayout.SOUTH)
                preferredSize = JBUI.size(560, preferredSize.height)
            }
        }

        override fun createActions(): Array<Action> = arrayOf(
            customAction("Remove From Others", this, SUMMARY_REMOVE_ALL, default = true),
            customAction("Review One by One…", this, SUMMARY_REVIEW),
            customAction("Keep Both", this, SUMMARY_KEEP),
        )

        override fun createLeftSideActions(): Array<Action> = arrayOf(
            customAction("Undo Assignment", this, SUMMARY_UNDO),
        )

    }

    // ---------------------------------------------------------------------------------------------

    private inner class StepDialog(
        private val conflict: Conflict,
        private val index: Int,
        private val total: Int,
    ) : DialogWrapper(project) {

        private val isLast = index == total
        private val next = if (isLast) "Finish" else "Next"
        private lateinit var reassignAction: Action
        private val input = ShortcutInput(keymap, os, conflict.actionId) {
            if (::reassignAction.isInitialized) reassignAction.isEnabled = newShortcut != null
        }

        val newShortcut: Shortcut? get() = input.shortcut

        init {
            title = "Resolve Conflict $index of $total"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val name = KeymapData.actionName(conflict.actionId)
            val builder = FormBuilder.createFormBuilder()
                .addComponent(JBLabel(
                    "<html><b>$name</b> <font color=gray>(${conflict.actionId})</font> also uses " +
                        "<b>${fmt(conflict.shortcut)}</b>.<br>Remove it, keep it, or give this action a different shortcut:</html>"
                ))
                .addVerticalGap(8)
            input.addTo(builder, "New shortcut:")
            builder.addVerticalGap(6).addComponent(JBLabel(
                "Closing this window keeps this and the remaining conflicts unchanged."
            ).apply { foreground = UIUtil.getContextHelpForeground() })
            return builder.panel.apply { border = JBUI.Borders.empty(4); preferredSize = JBUI.size(600, preferredSize.height) }
        }

        override fun getPreferredFocusedComponent(): JComponent = input.preferredFocus

        override fun createActions(): Array<Action> {
            reassignAction = customAction("Reassign and $next", this, STEP_REASSIGN).apply { isEnabled = false }
            val remaining = total - index
            return listOfNotNull(
                reassignAction,
                customAction("Remove and $next", this, STEP_REMOVE, default = true),
                customAction("Keep and $next", this, STEP_KEEP),
                if (remaining > 0) customAction("Remove From All Remaining (${remaining + 1})", this, STEP_REMOVE_REST) else null,
            ).toTypedArray()
        }

        override fun createLeftSideActions(): Array<Action> = arrayOf(
            customAction("Undo Everything", this, STEP_UNDO_ALL),
        )

    }
}
