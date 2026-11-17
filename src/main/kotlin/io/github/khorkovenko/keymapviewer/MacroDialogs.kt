package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

private const val NEW_MACRO = "\u0000new"
/** List entries for IntelliJ's own macros carry this prefix (plain entries are this plugin's macros). */
private const val IDE_PREFIX = "\u0001ide:"

/**
 * Searchable list "where the macro goes": ＋ New macro… or one of your existing macros (to re-record, edit or
 * overwrite it), plus the macro's name.
 */
class MacroSlotChooser(initialExisting: String?, initialName: String?) {
    private val names = MacroStore.getInstance().all().map { it.name }
    /** Macros made with IntelliJ's own recorder (Edit | Macros); editing one saves a copy as a plugin macro. */
    private val ideMacros = IdeMacros.all().associateBy { it.name }
    private val search = SearchTextField(false)
    private val model = CollectionListModel<String>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 7
        cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == NEW_MACRO) {
                    icon = AllIcons.General.Add
                    append("New macro…", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                } else if (value != null && value.startsWith(IDE_PREFIX)) {
                    val m = ideMacros[value.removePrefix(IDE_PREFIX)]
                    icon = AllIcons.Ide.Macro.Recording_1
                    append(value.removePrefix(IDE_PREFIX))
                    append("   IntelliJ macro · ${m?.steps?.size ?: 0} steps", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else if (value != null) {
                    icon = AllIcons.Ide.Macro.Recording_1
                    append(value)
                    MacroStore.getInstance().get(value)?.let { append("   ${it.steps.size} steps", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
            }
        }
    }
    private val nameField = JBTextField(24)
    private var nameEdited = false

    /** Called when the selection changes (to enable buttons that need an existing macro). */
    var onSelectionChange: () -> Unit = {}

    /** The existing plugin macro selected in the list, or null for a new one (or an IntelliJ macro). */
    val existing: String? get() = list.selectedValue?.takeIf { it != NEW_MACRO && !it.startsWith(IDE_PREFIX) }
    /** The IntelliJ macro selected in the list, if any (its steps can be copied into a new plugin macro). */
    val ideMacro: Macro? get() = list.selectedValue?.takeIf { it.startsWith(IDE_PREFIX) }?.let { ideMacros[it.removePrefix(IDE_PREFIX)] }
    val name: String get() = nameField.text.trim()

    init {
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val sel = list.selectedValue
            if (sel != null && sel.startsWith(IDE_PREFIX)) {
                // A copy of an IntelliJ macro becomes a plugin macro; avoid clashing with an existing plugin macro
                val base = sel.removePrefix(IDE_PREFIX)
                nameField.text = if (base !in names) base else generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in names }
                nameEdited = false
            } else if (sel != null && sel != NEW_MACRO) { nameField.text = sel; nameEdited = false }
            else if (sel == NEW_MACRO && !nameEdited) nameField.text = freeName()
            onSelectionChange()
        }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (nameField.hasFocus()) nameEdited = true }
        })
        filter()
        list.setSelectedValue(initialExisting?.takeIf { it in names } ?: NEW_MACRO, true)
        if (initialName != null) nameField.text = initialName
    }

    private fun freeName() = generateSequence(1) { it + 1 }.map { "Macro $it" }.first { it !in names }

    private fun filter() {
        val q = search.text.trim()
        val keep = list.selectedValue
        model.replaceAll(listOf(NEW_MACRO) + names.filter { q.isEmpty() || it.contains(q, ignoreCase = true) } +
            ideMacros.keys.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.map { IDE_PREFIX + it })
        list.setSelectedValue(keep?.takeIf { it in model.items } ?: NEW_MACRO, true)
    }

    fun validate(): ValidationInfo? = when {
        name.isEmpty() -> ValidationInfo("Enter a name for the macro", nameField)
        existing == null && name in names -> ValidationInfo("A macro with this name exists; select it in the list to overwrite it", nameField)
        existing != null && name != existing && name in names -> ValidationInfo("Another macro already uses this name", nameField)
        else -> null
    }

    val preferredFocus: JComponent get() = search

    val component: JComponent
        get() = JPanel(BorderLayout(0, 4)).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { preferredSize = JBUI.size(420, 140); minimumSize = JBUI.size(200, 100) }, BorderLayout.CENTER)
            add(FormBuilder.createFormBuilder().addLabeledComponent("Name:", nameField).panel, BorderLayout.SOUTH)
        }
}

// ---- 1. Create Macro: choose where it goes, then Record -------------------------------------------

/** Exit codes besides OK (= Record). */
const val MACRO_EDIT_EXIT = DialogWrapper.NEXT_USER_EXIT_CODE + 20
const val MACRO_DELETE_EXIT = DialogWrapper.NEXT_USER_EXIT_CODE + 21

class MacroStartDialog(project: Project) : DialogWrapper(project) {
    val chooser = MacroSlotChooser(null, null)

    private val editAction = object : AbstractAction("Edit Steps…") {
        override fun actionPerformed(e: ActionEvent) = close(MACRO_EDIT_EXIT)
    }
    private val deleteAction = object : AbstractAction("Delete") {
        override fun actionPerformed(e: ActionEvent) = close(MACRO_DELETE_EXIT)
    }

    init {
        title = "Create Macro"
        setOKButtonText("Record")
        setOKButtonIcon(AllIcons.Ide.Macro.Recording_1)
        chooser.onSelectionChange = { syncButtons() }
        init()
        syncButtons()
    }

    private fun syncButtons() {
        val hasExisting = chooser.existing != null
        val ide = chooser.ideMacro != null
        editAction.isEnabled = hasExisting || ide
        editAction.putValue(Action.NAME, if (ide) "Copy and Edit…" else "Edit Steps…")
        deleteAction.isEnabled = hasExisting // IntelliJ macros are deleted in Edit | Macros | Edit Macros
        setOKButtonText(when {
            hasExisting -> "Record Again"
            ide -> "Record as New"
            else -> "Record"
        })
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JBLabel("<html>Choose a new macro or one to re-record. After <b>Record</b>, use the IDE as usual: " +
            "every action you run, the text you type and the tool windows you open are recorded until you press " +
            "<b>Stop &amp; Review</b>.<br>Macros made with IntelliJ's own recorder (Edit | Macros) are listed too; " +
            "<b>Copy and Edit…</b> turns one into a macro you can edit here.</html>").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.NORTH)
        add(chooser.component, BorderLayout.CENTER)
        preferredSize = JBUI.size(460, preferredSize.height)
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(editAction, deleteAction)

    override fun getPreferredFocusedComponent(): JComponent = chooser.preferredFocus

    override fun doValidate(): ValidationInfo? = chooser.validate()
}

// ---- 2. After Stop: review and edit the steps, choose the shortcut --------------------------------

class MacroEditDialog(
    private val project: Project,
    private val keymap: Keymap,
    private val os: TargetOs,
    existing: String?,
    initialName: String,
    steps: List<MacroStep>,
) : DialogWrapper(project) {

    val chooser = MacroSlotChooser(existing, initialName)
    private val stepsModel = CollectionListModel(steps)
    private val stepsList = JBList(stepsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<MacroStep>() {
            override fun customizeCellRenderer(list: JList<out MacroStep>, value: MacroStep?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append("${index + 1}.  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value?.let { append(it.describe()) }
            }
        }
        emptyText.text = "No steps. Use + to add an action or text."
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) editStep() }
        })
    }
    private val ownerId = MacroStore.PREFIX + (existing ?: initialName)
    private val currentShortcuts: List<Shortcut> = KeymapData.shortcutsOf(keymap, ownerId)
    private val shortcutInput = ShortcutInput(keymap, os, ownerId,
        emptyHint = "No shortcut. You can still run it from Find Action (“Macro: …”) or assign one later in the table.")

    val steps: List<MacroStep> get() = stepsModel.items
    /** The shortcut to assign, or null for none. */
    val shortcut: Shortcut? get() = shortcutInput.shortcut
    /** True when the macro had a shortcut and the field was emptied (= remove it). */
    val shortcutCleared: Boolean get() = shortcut == null && currentShortcuts.isNotEmpty()

    init {
        title = "Save Macro"
        setOKButtonText("Save")
        currentShortcuts.firstOrNull()?.let(shortcutInput::preset)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val stepsPanel = ToolbarDecorator.createDecorator(stepsList)
            .setAddAction { button ->
                JBPopupFactory.getInstance().createPopupChooserBuilder(listOf("Run an action…", "Type text…", "Open a tool window…"))
                    .setItemChosenCallback { choice ->
                        when {
                            choice.startsWith("Run") -> addAction()
                            choice.startsWith("Type") -> addTyping()
                            else -> addToolWindow()
                        }
                    }
                    .createPopup()
                    .show(button.preferredPopupPoint)
            }
            .setEditAction { editStep() }
            .setRemoveAction { stepsList.selectedIndex.takeIf { it >= 0 }?.let { stepsModel.remove(it) } }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .setPreferredSize(JBUI.size(560, 220))
            .createPanel()
            .apply { minimumSize = JBUI.size(300, 140) }

        // Three stacked sections. BorderLayout (not FormBuilder) so the two lists always keep a usable height:
        // macro choice on top, the steps in the middle (gets any extra space), the shortcut at the bottom.
        val macroSection = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("Macro:"), BorderLayout.NORTH)
            add(chooser.component, BorderLayout.CENTER)
        }
        val stepsSection = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.emptyTop(10)
            add(JBLabel("Recorded steps (double-click to edit, + to add, arrows to reorder):"), BorderLayout.NORTH)
            add(stepsPanel, BorderLayout.CENTER)
            // Type a step by hand (with suggestions), inserted after the selected step
            add(MacroStepInput(project) { insert(it) }.field, BorderLayout.SOUTH)
        }
        val shortcutForm = FormBuilder.createFormBuilder()
        shortcutInput.addTo(shortcutForm, "Shortcut:")
        shortcutForm.addComponentToRightColumn(JBLabel("Saved in keymap “${keymap.presentableName}”.").apply {
            foreground = UIUtil.getContextHelpForeground()
        })
        val shortcutSection = shortcutForm.panel.apply { border = JBUI.Borders.emptyTop(10) }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(macroSection, BorderLayout.NORTH)
            add(stepsSection, BorderLayout.CENTER)
            add(shortcutSection, BorderLayout.SOUTH)
            preferredSize = java.awt.Dimension(JBUI.scale(640), preferredSize.height)
        }
    }

    private fun move(delta: Int) {
        val i = stepsList.selectedIndex
        val j = i + delta
        if (i < 0 || j !in 0 until stepsModel.size) return
        stepsModel.exchangeRows(i, j)
        stepsList.selectedIndex = j
    }

    private fun insert(step: MacroStep) {
        val at = (stepsList.selectedIndex + 1).takeIf { it > 0 } ?: stepsModel.size
        stepsModel.add(at, step)
        stepsList.selectedIndex = at
    }

    private fun addAction() {
        ActionChooserDialog(contentPanel).chosen()?.let { insert(MacroStep.Action(it)) }
    }

    private fun addToolWindow() {
        val manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val windows = manager.toolWindowIds.mapNotNull { manager.getToolWindow(it) }.sortedBy { it.stripeTitle.lowercase() }
        JBPopupFactory.getInstance().createPopupChooserBuilder(windows)
            .setTitle("Open Tool Window")
            .setRenderer(textRenderer { it.stripeTitle })
            .setNamerForFiltering { it.stripeTitle }
            .setItemChosenCallback { insert(MacroStep.ToolWindow(it.id)) }
            .createPopup()
            .showInCenterOf(stepsList)
    }

    private fun addTyping() {
        Messages.showInputDialog(contentPanel, "Text to type into the editor:", "Type Text", null)
            ?.takeIf { it.isNotEmpty() }?.let { insert(MacroStep.Typing(it)) }
    }

    private fun editStep() {
        val i = stepsList.selectedIndex.takeIf { it >= 0 } ?: return
        val updated = when (val s = stepsModel.getElementAt(i)) {
            is MacroStep.Typing -> Messages.showInputDialog(contentPanel, "Text to type into the editor:", "Edit Text", null, s.text, null)
                ?.takeIf { it.isNotEmpty() }?.let { MacroStep.Typing(it) }
            is MacroStep.Action -> ActionChooserDialog(contentPanel, s.actionId).chosen()?.let { MacroStep.Action(it) }
            is MacroStep.ToolWindow -> { addToolWindow(); null } // pick a replacement; remove the old one with −
            is MacroStep.Key -> {
                val mac = ShortcutFormatter.currentOsIsMac
                Messages.showInputDialog(contentPanel, "Key to press (e.g. Enter, Ctrl+S, Shift+Tab):", "Edit Key Press", null,
                    ShortcutFormatter.format(s.stroke, mac), null)
                    ?.let { (ShortcutText.parse(it, mac) as? ShortcutText.Result.Ok)?.shortcuts?.firstOrNull() }
                    ?.let { it as? com.intellij.openapi.actionSystem.KeyboardShortcut }
                    ?.takeIf { !DoubleModifier.isDouble(it) }
                    ?.let { MacroStep.Key(it.firstKeyStroke) }
            }
        } ?: return
        stepsModel.setElementAt(updated, i)
    }

    override fun getPreferredFocusedComponent(): JComponent = shortcutInput.preferredFocus

    override fun doValidate(): ValidationInfo? = chooser.validate()
        ?: if (stepsModel.isEmpty) ValidationInfo("The macro has no steps; add at least one", stepsList) else null
}

// ---- Pick an IDE action (for adding or changing a step) -------------------------------------------

private class ActionChooserDialog(parent: JComponent, private val initial: String? = null) : DialogWrapper(parent, true) {
    private data class Item(val id: String, val name: String) {
        override fun toString() = if (name == id) id else "$name   ($id)"
    }

    private val all: List<Item> by lazy {
        val manager = ActionManager.getInstance()
        manager.getActionIdList("")
            .filter { !it.startsWith(MacroStore.PREFIX) && manager.getActionOrStub(it) !is ActionGroup }
            .map { Item(it, KeymapData.actionName(it)) }
            .sortedBy { it.name.lowercase() }
    }
    private val search = SearchTextField(false)
    private val model = CollectionListModel<Item>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2 && selectedValue != null) doOKAction() }
        })
    }

    init {
        title = "Choose Action"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filter()
        })
        // ↑/↓ in the search box move through the list
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val d = when (e.keyCode) { KeyEvent.VK_DOWN -> 1; KeyEvent.VK_UP -> -1; else -> return }
                if (model.size == 0) return // nothing matches the search
                list.selectedIndex = (list.selectedIndex + d).coerceIn(0, model.size - 1)
                list.ensureIndexIsVisible(list.selectedIndex)
                e.consume()
            }
        })
        filter()
        initial?.let { id -> model.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { list.selectedIndex = it; list.ensureIndexIsVisible(it) } }
        init()
    }

    private fun filter() {
        val q = search.text.trim()
        val hits = if (q.isEmpty()) all else all.filter { it.name.contains(q, true) || it.id.contains(q, true) }
        model.replaceAll(hits.take(1000))
        if (model.size > 0) list.selectedIndex = 0
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 4)).apply {
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(480, 320) }, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = search

    fun chosen(): String? = if (showAndGet()) list.selectedValue?.id else null
}
