package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JComponent

/** What to do with other actions that already use the new shortcut. */
enum class ConflictChoice { REMOVE_FROM_OTHERS, KEEP_BOTH, REVIEW }

/** What the user decided in [ShortcutEditDialog]. */
sealed interface EditResult {
    data class Assign(
        val shortcut: Shortcut,
        val replaceExisting: Boolean,
        val onConflict: ConflictChoice = ConflictChoice.REMOVE_FROM_OTHERS,
    ) : EditResult
    data object RemoveAll : EditResult
}

/**
 * Opens with the action's current shortcut already in the field. You can:
 *  • press a new combination → Enter/Save stores it,
 *  • press Backspace to empty the field → Enter asks whether to remove the shortcut(s) or restore them,
 *  • press Esc to cancel without changes.
 */
class ShortcutEditDialog(
    private val project: Project,
    private val keymap: Keymap,
    private val os: TargetOs,
    private val actionId: String,
) : DialogWrapper(project) {

    private val actionName = KeymapData.actionName(actionId)
    private val currentAll = KeymapData.shortcutsOf(keymap, actionId)
    private val currentKeyboard = currentAll.filterIsInstance<KeyboardShortcut>()
    private val currentMouse = currentAll.filterIsInstance<MouseShortcut>()
    private val currentText = currentAll.joinToString(ShortcutFormatter.SEPARATOR) { ShortcutFormatter.format(it, os.isMac) }

    private val input: ShortcutInput = ShortcutInput(
        keymap, os, actionId,
        emptyHint = if (currentAll.isEmpty()) "Nothing pressed yet."
        else "Field is empty. Saving will ask whether to remove the current shortcut(s) or restore them.",
    ) { syncReplaceOptions(); syncConflictOptions() }

    // Shown only when another action already uses the shortcut in the field
    private val conflictTitle = JBLabel("Other actions using it:")
    private val moveRadio = JBRadioButton("Remove it from them and assign it here", true)
    private val keepRadio = JBRadioButton("Keep both (the current context decides which runs)")
    private val reviewRadio = JBRadioButton("Decide for each one after saving: remove, keep or give it a new shortcut")

    private val replaceRadio = JBRadioButton("Replace current shortcut(s)", true)
    private val addRadio = JBRadioButton("Add as an additional shortcut")

    var result: EditResult? = null
        private set

    init {
        title = "Edit Shortcut"
        ButtonGroup().apply { add(replaceRadio); add(addRadio) }
        ButtonGroup().apply { add(moveRadio); add(keepRadio); add(reviewRadio) }
        setOKButtonText("Save")
        (currentKeyboard.firstOrNull() ?: currentMouse.firstOrNull())?.let(input::preset)
        init()
        syncReplaceOptions()
        syncConflictOptions()
    }

    private fun syncConflictOptions() {
        val conflicts = input.conflicts
        listOf(conflictTitle, moveRadio, keepRadio, reviewRadio).forEach { it.isVisible = conflicts.isNotEmpty() }
        if (conflicts.isEmpty()) return
        val names = conflicts.keys.take(3).joinToString(", ") { KeymapData.actionName(it) }
        val more = if (conflicts.size > 3) " and ${conflicts.size - 3} more" else ""
        moveRadio.text = "Remove it from $names$more and assign it here"
        if (isShowing) pack() // make room for the options
    }

    private val conflictChoice get() = when {
        keepRadio.isSelected -> ConflictChoice.KEEP_BOTH
        reviewRadio.isSelected -> ConflictChoice.REVIEW
        else -> ConflictChoice.REMOVE_FROM_OTHERS
    }

    /** Existing shortcuts of the same kind (keyboard or mouse) as the one in the field. */
    private fun sameKind(sc: Shortcut?): List<Shortcut> = when (sc) {
        is MouseShortcut -> currentMouse
        null -> emptyList()
        else -> currentKeyboard
    }

    /** Replace/Add only matter when the action already has a shortcut of the kind being entered. */
    private fun syncReplaceOptions() {
        val existing = sameKind(input.shortcut)
        val kind = if (input.shortcut is MouseShortcut) "mouse" else "keyboard"
        replaceRadio.text = "Replace current $kind shortcut(s): ${existing.joinToString(", ") { ShortcutFormatter.format(it, os.isMac) }}"
        replaceRadio.isVisible = existing.isNotEmpty()
        addRadio.isVisible = existing.isNotEmpty()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Action:", JBLabel("<html><b>$actionName</b> <font color=gray>($actionId)</font></html>"))
            .addLabeledComponent("Keymap:", JBLabel("${keymap.presentableName} — designed for ${os.label}"))
            .addLabeledComponent("Current:", JBLabel(currentText.ifEmpty { "none" }))
            .addVerticalGap(8)
        input.addTo(builder, "Shortcut:")
        builder.addVerticalGap(4).addComponentToRightColumn(replaceRadio).addComponentToRightColumn(addRadio)
            .addVerticalGap(4).addComponentToRightColumn(conflictTitle)
            .addComponentToRightColumn(moveRadio).addComponentToRightColumn(keepRadio).addComponentToRightColumn(reviewRadio)
        return builder.panel.apply { border = JBUI.Borders.empty(4); preferredSize = JBUI.size(560, preferredSize.height) }
    }

    override fun getPreferredFocusedComponent(): JComponent = input.preferredFocus

    override fun createLeftSideActions(): Array<Action> {
        if (currentAll.isEmpty()) return emptyArray()
        return arrayOf(object : AbstractAction("Remove All Shortcuts") {
            override fun actionPerformed(e: ActionEvent) {
                result = EditResult.RemoveAll
                close(OK_EXIT_CODE)
            }
        })
    }

    override fun doOKAction() {
        val sc = input.shortcut
        if (sc != null) {
            result = EditResult.Assign(sc, replaceExisting = sameKind(sc).isNotEmpty() && replaceRadio.isSelected,
                onConflict = conflictChoice)
            return super.doOKAction()
        }

        // Field emptied and saved
        if (currentAll.isEmpty()) {           // nothing to remove: just close
            result = null
            return super.doOKAction()
        }
        when (Messages.showYesNoCancelDialog(
            project,
            "The shortcut field is empty.\n\nRemove all shortcuts from “$actionName” ($currentText), " +
                "or restore them and close?",
            "Remove Shortcuts?",
            "Remove", "Restore", "Back to Editing",
            Messages.getQuestionIcon(),
        )) {
            Messages.YES -> { result = EditResult.RemoveAll; super.doOKAction() }
            Messages.NO -> { result = null; close(CANCEL_EXIT_CODE) } // restore: nothing changes
            else -> input.preferredFocus.requestFocusInWindow()      // stay in the dialog
        }
    }
}
