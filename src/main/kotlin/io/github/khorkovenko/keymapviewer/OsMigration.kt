package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.impl.KeymapImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.event.InputEvent
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

/**
 * Moves a keymap to another OS in one step: a new keymap is based on the target OS's built-in keymap and
 * receives the source's shortcuts, with Ctrl ↔ ⌘ swapped when going between macOS and Windows/Linux.
 */
object OsMigration {

    data class Result(val keymap: Keymap, val moved: Int, val removedConflicts: Int, val skipped: Int)

    fun migrate(project: Project, source: Keymap): Result? {
        val sourceOs = KeymapData.detectTargetOs(source)
        val ownIds = ownActionIds(source)
        val dlg = MigrateDialog(project, source, sourceOs, ownIds.size)
        if (!dlg.showAndGet()) return null

        val target = dlg.targetOs
        val base = KeymapEditing.builtInFor(target) ?: return null
        val swap = sourceOs.isMac != target.isMac
        val keymap = base.deriveKeymap(dlg.keymapName)

        val ids: Collection<String> = if (dlg.onlyChanges) ownIds else {
            // Full copy: start from nothing so only the source's shortcuts are present
            base.actionIdList.toList().forEach { keymap.removeAllActionShortcuts(it) }
            source.actionIdList
        }

        var moved = 0
        var skipped = 0
        val added = ArrayList<Pair<String, KeyboardShortcut>>()
        for (id in ids) {
            keymap.removeAllActionShortcuts(id) // also keeps "removed" overrides as removed
            for (sc in source.getShortcuts(id)) {
                val out = translate(sc, swap)
                if (out == null) { skipped++; continue }
                keymap.addShortcut(id, out)
                moved++
                if (out is KeyboardShortcut) added += id to out
            }
        }

        // Your shortcuts win: built-in shortcuts of the target OS that collide with them are removed
        var removedConflicts = 0
        if (dlg.onlyChanges && dlg.removeConflicts) {
            val mine = ids.toSet()
            for ((id, sc) in added) {
                keymap.getConflicts(id, sc).forEach { (otherId, list) ->
                    if (otherId == id || otherId in mine) return@forEach
                    list.forEach { keymap.removeShortcut(otherId, it); removedConflicts++ }
                }
            }
        }

        KeymapEditing.register(keymap, dlg.makeActive)
        DoubleModifierStore.getInstance().copyKeymap(source.name, keymap.name, swapCtrlMeta = swap)
        return Result(keymap, moved, removedConflicts, skipped)
    }

    /** Actions the keymap itself changes (not inherited). A keymap without a parent "changes" everything. */
    private fun ownActionIds(keymap: Keymap): List<String> {
        val impl = keymap as? KeymapImpl
        if (keymap.parent == null || impl == null) return emptyList()
        return keymap.actionIdList.filter { impl.hasOwnActionId(it) }
    }

    private fun translate(sc: Shortcut, swap: Boolean): Shortcut? = when (sc) {
        is KeyboardShortcut -> if (!swap) sc else KeyboardShortcut(swapCtrlMeta(sc.firstKeyStroke), sc.secondKeyStroke?.let(::swapCtrlMeta))
        is MouseShortcut -> if (!swap) sc else MouseShortcut(sc.button, swapMask(sc.modifiers), sc.clickCount)
        else -> null // keyboard gestures (double-Ctrl…) can't be carried over
    }

    private fun swapCtrlMeta(stroke: KeyStroke): KeyStroke =
        KeyStroke.getKeyStroke(stroke.keyCode, swapMask(stroke.modifiers), stroke.isOnKeyRelease)

    private fun swapMask(modifiers: Int): Int {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        val meta = InputEvent.META_DOWN_MASK
        // Keep only the extended masks (the legacy ones are derived from them)
        val ex = modifiers and (ctrl or meta or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or
            InputEvent.ALT_GRAPH_DOWN_MASK or InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON2_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK)
        var out = ex and (ctrl or meta).inv()
        if (ex and ctrl != 0) out = out or meta
        if (ex and meta != 0) out = out or ctrl
        return out
    }
}

private class MigrateDialog(
    project: Project,
    private val source: Keymap,
    private val sourceOs: TargetOs,
    ownCount: Int,
) : DialogWrapper(project) {

    private val machineOs = when {
        SystemInfo.isMac -> TargetOs.MAC
        SystemInfo.isWindows -> TargetOs.WINDOWS_LINUX
        else -> TargetOs.LINUX
    }
    private val osCombo = ComboBox(arrayOf(TargetOs.MAC, TargetOs.WINDOWS_LINUX, TargetOs.LINUX)).apply {
        renderer = SimpleListCellRenderer.create { label, os, _ -> label.text = os?.label ?: "" }
        // Default: this machine's OS if the keymap is for another one, otherwise the "other" family
        selectedItem = when {
            machineOs != sourceOs -> machineOs
            sourceOs.isMac -> TargetOs.WINDOWS_LINUX
            else -> TargetOs.MAC
        }
    }
    private val nameField = JBTextField(28)
    private var nameEdited = false
    private val changesRadio = JBRadioButton("Only my changes ($ownCount actions), on top of the target OS's default keymap")
    private val fullRadio = JBRadioButton("Every shortcut (exact copy of this layout)")
    private val conflictsBox = JBCheckBox("My shortcuts win: remove the target OS's defaults that collide with them", true)
    private val activeBox = JBCheckBox("Make it the active keymap")
    private val translationLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    val targetOs: TargetOs get() = osCombo.selectedItem as TargetOs
    val keymapName: String get() = nameField.text.trim()
    val onlyChanges: Boolean get() = changesRadio.isSelected
    val removeConflicts: Boolean get() = conflictsBox.isSelected
    val makeActive: Boolean get() = activeBox.isSelected

    init {
        title = "Migrate Keymap to Another OS"
        setOKButtonText("Migrate")
        ButtonGroup().apply { add(changesRadio); add(fullRadio) }
        // A built-in keymap has no "changes": copy everything
        if (ownCount > 0) changesRadio.isSelected = true else { fullRadio.isSelected = true; changesRadio.isEnabled = false }
        changesRadio.addActionListener { syncState() }
        fullRadio.addActionListener { syncState() }
        // Suggest activating it only when it's meant for the OS this IDE runs on
        osCombo.addActionListener { activeBox.isSelected = targetOs == machineOs; syncState() }
        activeBox.isSelected = targetOs == machineOs
        nameField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (nameField.hasFocus()) nameEdited = true }
        })
        syncState()
        init()
    }

    private fun syncState() {
        if (!nameEdited) nameField.text = KeymapEditing.uniqueName("${source.presentableName} (${targetOs.label})")
        conflictsBox.isEnabled = changesRadio.isSelected
        translationLabel.text = if (sourceOs.isMac != targetOs.isMac)
            "<html>Shortcuts are translated: ${if (sourceOs.isMac) "⌘ → Ctrl, ⌃ → Meta" else "Ctrl → ⌘, Meta → ⌃"}. " +
                "Alt/⌥ and Shift stay the same.</html>"
        else "Same modifier keys on both systems: shortcuts are copied unchanged."
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("From:", JBLabel("${source.presentableName} — designed for ${sourceOs.label}"))
            .addLabeledComponent("To:", osCombo)
            .addComponentToRightColumn(translationLabel)
            .addLabeledComponent("New keymap:", nameField)
            .addVerticalGap(6)
            .addLabeledComponent("Take:", changesRadio)
            .addComponentToRightColumn(fullRadio)
            .addComponentToRightColumn(conflictsBox)
            .addVerticalGap(6)
            .addComponentToRightColumn(activeBox)
            .panel.apply { border = JBUI.Borders.empty(4) }

    override fun getPreferredFocusedComponent(): JComponent = osCombo

    override fun doValidate(): ValidationInfo? = when {
        keymapName.isEmpty() -> ValidationInfo("Enter a name", nameField)
        KeymapEditing.uniqueName(keymapName) != keymapName -> ValidationInfo("A keymap with this name already exists", nameField)
        KeymapEditing.builtInFor(targetOs) == null -> ValidationInfo("No built-in keymap for ${targetOs.label} is installed", osCombo)
        else -> null
    }
}
