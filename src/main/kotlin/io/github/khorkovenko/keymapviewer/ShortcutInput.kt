package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Reusable "press a shortcut" block used by both the edit dialog and the conflict dialog:
 * capture field + Clear button, the Ctrl ↔ ⌘ translation option, a "will be saved as" preview
 * and a live warning listing which actions already use the combination.
 *
 * @param ownerActionId the action being edited; it is never reported as a conflict with itself.
 */
class ShortcutInput(
    private val keymap: Keymap,
    private val os: TargetOs,
    private val ownerActionId: String,
    private val emptyHint: String = "Nothing pressed yet.",
    private val onChange: () -> Unit = {},
) {
    private val needsTranslation = os != TargetOs.UNKNOWN && os.isMac != ShortcutFormatter.currentOsIsMac

    private val translateBox = JBCheckBox(
        if (os.isMac) "Translate Ctrl → ⌘ (you are on ${TargetOs.current}, this keymap is for macOS)"
        else "Translate ⌘ → Ctrl (you are on macOS, this keymap is for ${os.label})",
        true,
    ).apply { isVisible = needsTranslation }

    val captureField = ShortcutCaptureField { sc -> ShortcutFormatter.format(result(sc), os.isMac) }

    private val previewLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val twoStepBox = JBCheckBox("Two-step shortcut: the next combination you press becomes step 2 (e.g. Ctrl+K, Ctrl+K)")
    private val keysHint = JBLabel("<html>Backspace clears · Enter saves · Esc cancels<br>" +
        "Tap Ctrl, Shift or Alt twice for a double modifier · Ctrl+Click in the field for a mouse shortcut</html>").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val conflictLabel = JBLabel().apply { foreground = JBColor.ORANGE }

    /** The shortcut that will actually be stored in the keymap, or null if nothing was pressed. */
    val shortcut: Shortcut?
        get() = captureField.shortcut?.let(::result)

    /** action ID → its shortcut(s) that collide with [shortcut]. */
    val conflicts: Map<String, List<Shortcut>>
        get() = shortcut?.let { conflictsOf(keymap, ownerActionId, it) } ?: emptyMap()

    init {
        captureField.onChange = { update() }
        translateBox.addActionListener { captureField.rerender(); update() }
        twoStepBox.addActionListener { captureField.twoSteps = twoStepBox.isSelected; captureField.requestFocusInWindow() }
        update(notify = false) // the owner is still being constructed
    }

    /** Show an existing shortcut in the field (so it can be kept, changed, or cleared with Backspace). */
    fun preset(stored: Shortcut) {
        // translate() just swaps Ctrl and ⌘, so applying it again undoes it: result(raw) == stored
        captureField.setShortcut(result(stored))
    }

    private fun result(performed: Shortcut): Shortcut =
        if (needsTranslation && translateBox.isSelected) ShortcutFormatter.translate(performed, os.isMac) else performed

    private fun update(notify: Boolean = true) {
        val sc = shortcut
        previewLabel.text = if (sc == null) emptyHint
        else "Will be saved as ${ShortcutFormatter.format(sc, os.isMac)} for ${os.label}."

        val c = conflicts
        val builtIn = DoubleModifier.keyCodeOf(sc)?.let(DoubleModifier::builtInUse)
        if (c.isEmpty() && builtIn != null) {
            conflictLabel.icon = AllIcons.General.Information
            conflictLabel.text = "The IDE also uses this for $builtIn; both will run."
        } else if (c.isEmpty()) {
            conflictLabel.text = ""; conflictLabel.icon = null
        } else {
            val names = c.keys.take(3).joinToString(", ") { KeymapData.actionName(it) }
            val more = if (c.size > 3) " and ${c.size - 3} more" else ""
            conflictLabel.icon = AllIcons.General.Warning
            conflictLabel.text = "Already used by ${c.size} action(s): $names$more"
        }
        if (notify) onChange()
    }

    fun addTo(builder: FormBuilder, label: String): FormBuilder {
        val clear = JButton("Clear").apply { addActionListener { captureField.setShortcut(null); captureField.requestFocusInWindow() } }
        val row = JPanel(BorderLayout(6, 0)).apply { add(captureField, BorderLayout.CENTER); add(clear, BorderLayout.EAST) }
        return builder
            .addLabeledComponent(label, row)
            .addComponentToRightColumn(keysHint)
            .addComponentToRightColumn(twoStepBox)
            .addComponentToRightColumn(translateBox)
            .addComponentToRightColumn(previewLabel)
            .addComponentToRightColumn(conflictLabel)
    }

    val preferredFocus: JComponent get() = captureField

    companion object {
        /** Other actions that already use [shortcut] (keyboard: also prefix collisions of two-key shortcuts). */
        fun conflictsOf(keymap: Keymap, actionId: String, shortcut: Shortcut): Map<String, List<Shortcut>> = when {
            DoubleModifier.isDouble(shortcut) -> {
                val code = DoubleModifier.keyCodeOf(shortcut)
                DoubleModifierStore.getInstance().forKeymap(keymap.name)
                    .filter { (id, c) -> c == code && id != actionId }.mapValues { listOf(shortcut) }
            }
            else -> plainConflicts(keymap, actionId, shortcut)
        }

        private fun plainConflicts(keymap: Keymap, actionId: String, shortcut: Shortcut): Map<String, List<Shortcut>> = when (shortcut) {
            is KeyboardShortcut -> keymap.getConflicts(actionId, shortcut).filterKeys { it != actionId }.filterValues { it.isNotEmpty() }
            is MouseShortcut -> keymap.getActionIds(shortcut).filter { it != actionId }.associateWith { listOf<Shortcut>(shortcut) }
            else -> emptyMap()
        }
    }
}
