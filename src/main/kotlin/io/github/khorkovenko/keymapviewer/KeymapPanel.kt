package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import javax.swing.JEditorPane
import com.intellij.openapi.application.ModalityState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class KeymapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // The drop-down holds keymap *names*, not Keymap objects: KeymapImpl.equals() compares the whole
    // shortcut map, so live objects in a Swing model break selection once a keymap is copied or edited.
    private val keymapModel = DefaultComboBoxModel<String>()
    private val keymapCombo = ComboBox(keymapModel).apply {
        renderer = textRenderer<String> { name ->
            (KeymapData.findKeymap(name)?.presentableName ?: name) +
                (if (name == KeymapData.activeKeymap().name) "  (active)" else "")
        }
    }
    private val showAllBox = JBCheckBox("Show all actions", PropertiesComponent.getInstance().getBoolean(SHOW_ALL_KEY, true)).apply {
        toolTipText = "On: every action, so all keymaps list the same rows. Off: only actions with a shortcut."
    }
    private val markdownBox = JBCheckBox("Show as Markdown", PropertiesComponent.getInstance().getBoolean(SHOW_MD_KEY, false)).apply {
        toolTipText = "Show the list below as rendered Markdown instead of a table. Search filters and highlights it too."
    }
    private val markdownView = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().build()
        background = UIUtil.getPanelBackground()
        // Block links at the top ("Edit (12) · View (8) …") jump to their section
        addHyperlinkListener { e ->
            if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && e.description?.startsWith("#") == true) {
                scrollToReference(e.description.substring(1))
            }
        }
    }
    private val viewCards = CardLayout()
    private val viewPanel = JPanel(viewCards)
    private var currentSummary: KeymapSummary? = null
    private val searchField = SearchTextField(false)
    private val infoLabel = JBLabel()
    private val statusLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val tableModel = ShortcutTableModel()
    private val sorter = TableRowSorter(tableModel)
    private val table = JBTable(tableModel).apply {
        rowSorter = sorter
        toolTipText = "Click a shortcut to type it · double-click a row to press the keys instead"
        putClientProperty("terminateEditOnFocusLost", true) // leaving the cell saves it
        surrendersFocusOnKeystroke = true
        columnModel.getColumn(0).preferredWidth = 160
        columnModel.getColumn(1).preferredWidth = 260
        columnModel.getColumn(2).preferredWidth = 220
    }

    private val toolbar = ActionManager.getInstance().createActionToolbar("KeymapViewer", DefaultActionGroup().apply {
        add(iconAction("Make Active", "Make the selected keymap the IDE's active keymap", AllIcons.Actions.Checked,
            enabled = { k, _ -> k.name != KeymapData.activeKeymap().name }) { makeActive() })
        add(iconAction("Copy Keymap…", "Create a new keymap as a copy of the selected one", AllIcons.Actions.Copy) { copyKeymap() })
        add(iconAction("Delete Keymap…", "Delete the selected custom keymap (built-in keymaps can't be deleted)", AllIcons.General.Remove,
            enabled = { k, _ -> k.canModify() }) { deleteKeymap() })
        add(iconAction("New Empty Keymap…", "Create a keymap with no shortcuts", AllIcons.General.Add) { newEmptyKeymap() })
        addSeparator()
        add(iconAction("Export .md…", "Save the assigned shortcuts as a Markdown file (needs at least one assigned shortcut)",
            AllIcons.FileTypes.Text, enabled = { _, s -> (s?.assignedCount ?: 0) > 0 }) { exportToFile() })
        add(iconAction("Import Keymap…", "Import keymap .xml files, macro files, or a whole export folder (each file is recognized automatically)", AllIcons.ToolbarDecorator.Import) { importKeymap() })
        add(iconAction("Export Keymap…", "Save the selected keymap and all macros into a folder", AllIcons.ToolbarDecorator.Export) { exportKeymap() })
        add(iconAction("Migrate to OS…", "Create a copy of the selected keymap for another OS (Ctrl ↔ ⌘ translated)",
            AllIcons.Actions.SwapPanels) { migrateKeymap() })
        addSeparator()
        add(iconAction("Create Macro…", "Record a macro (actions and typing), edit its steps and give it a shortcut",
            AllIcons.Ide.Macro.Recording_1, enabled = { _, _ -> !MacroRecorder.isRecording }) { createMacro() })
        addSeparator()
        add(ShowMissedShortcutsAction()) // red count badge while there are misses you haven't looked at
    }, true)

    /** A toolbar icon button; [enabled] decides from the selected keymap and its summary whether it can be clicked. */
    private fun iconAction(
        text: String, description: String, icon: javax.swing.Icon,
        enabled: (Keymap, KeymapSummary?) -> Boolean = { _, _ -> true },
        run: () -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val k = selectedKeymap
            e.presentation.isEnabled = k != null && enabled(k, currentSummary)
        }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private val searchRow = CompactRow(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply { border = JBUI.Borders.empty(2, 4) }
    // GridBagLayout centers the checkboxes vertically on the search field's line
    private val checkboxes = JPanel(java.awt.GridBagLayout()).apply {
        add(showAllBox, java.awt.GridBagConstraints().apply { insets = JBUI.insetsRight(8) })
        add(markdownBox, java.awt.GridBagConstraints())
    }
    private var searchStacked: Boolean? = null

    private fun layoutSearchRow(stacked: Boolean) {
        if (searchStacked == stacked) return
        searchStacked = stacked
        searchRow.removeAll()
        if (stacked) {
            searchRow.add(searchField, BorderLayout.NORTH)
            searchRow.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(checkboxes) }, BorderLayout.SOUTH)
        } else {
            searchRow.add(searchField, BorderLayout.CENTER)
            searchRow.add(checkboxes, BorderLayout.EAST)
        }
        searchRow.revalidate()
        searchRow.repaint()
    }

    private var suppressEvents = false

    init {
        // Icon buttons with tooltips (same order in every layout). They sit right after the drop-down and
        // wrap to the next line only if the tool window is very narrow.
        toolbar.targetComponent = this
        MissedShortcutStore.getInstance().addListener(this) { toolbar.updateActionsAsync() } // badge count
        // Drop-down + icon buttons on one line, vertically centered; the icons move to a second line only
        // when the tool window is too narrow for both.
        val controls = CompactRow(LineWrapLayout(JBUI.scale(6), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, 4, 0, 4)
            add(JBLabel("Keymap:")); add(keymapCombo)
            add(toolbar.component.apply { border = JBUI.Borders.empty() })
        }
        val info = CompactRow(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(2, 6, 0, 6)
            add(infoLabel, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.CENTER)
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(controls); add(info); add(searchRow)
        }
        layoutSearchRow(stacked = false)
        // Wide panel: search and checkboxes share a line. Narrow panel: checkboxes go under a full-width search bar.
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                layoutSearchRow(stacked = width < JBUI.scale(640))
                controls.revalidate() // the icon line may wrap or unwrap at the new width
            }
        })
        add(top, BorderLayout.NORTH)
        viewPanel.add(JBScrollPane(table), CARD_TABLE)
        viewPanel.add(JBScrollPane(markdownView), CARD_MD)
        add(viewPanel, BorderLayout.CENTER)

        keymapCombo.addActionListener { if (!suppressEvents) { statusLabel.text = ""; refresh() } }
        markdownBox.addActionListener {
            PropertiesComponent.getInstance().setValue(SHOW_MD_KEY, markdownBox.isSelected, false)
            updateView()
        }
        showAllBox.addActionListener {
            PropertiesComponent.getInstance().setValue(SHOW_ALL_KEY, showAllBox.isSelected, true)
            refresh()
        }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        table.columnModel.getColumn(0).cellEditor = ShortcutCellEditor(
            isMac = { currentSummary?.targetOs?.isMac ?: ShortcutFormatter.currentOsIsMac },
            onDoubleClick = { editSelectedRow() },
        )
        tableModel.onShortcutTyped = { row, text ->
            val actionId = tableModel.rows[row].actionId
            // Run after the table has finished editing (dialogs may open)
            ApplicationManager.getApplication().invokeLater { applyTypedShortcut(actionId, text) }
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !table.isEditing && table.rowAtPoint(e.point) >= 0) editSelectedRow()
            }
        })

        // Stay in sync when keymaps are added/removed/activated elsewhere (e.g. Settings | Keymap)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(KeymapManagerListener.TOPIC, object : KeymapManagerListener {
                override fun keymapAdded(keymap: Keymap) = scheduleReload()
                override fun keymapRemoved(keymap: Keymap) = scheduleReload()
                override fun activeKeymapChanged(keymap: Keymap?) = scheduleReload()
                // Shortcuts edited in Settings | Keymap (or by another plugin) while this panel is open
                override fun shortcutsChanged(keymap: Keymap, actionIds: Collection<String>, fromSettings: Boolean) {
                    if (keymap.name == keymapCombo.selectedItem) scheduleReload()
                }
            })

        reloadKeymaps(KeymapData.activeKeymap())
    }

    override fun dispose() {}

    private var reloadPending = false
    private fun scheduleReload() {
        if (reloadPending) return
        reloadPending = true
        ApplicationManager.getApplication().invokeLater {
            reloadPending = false
            if (table.isEditing) return@invokeLater // don't throw away what is being typed; the edit refreshes anyway
            val selectedAction = table.selectedRow.takeIf { it >= 0 }
                ?.let { tableModel.rows.getOrNull(table.convertRowIndexToModel(it))?.actionId }
            reloadKeymaps(selectedKeymap)
            selectedAction?.let(::selectAction)
        }
    }

    private val selectedKeymap: Keymap? get() = (keymapCombo.selectedItem as? String)?.let(KeymapData::findKeymap)

    /** Re-reads the keymap list (after copy/create/delete) and selects [select] (or the active keymap). */
    private fun reloadKeymaps(select: Keymap?) {
        val names = KeymapData.allKeymaps().map { it.name }
        val target = select?.name?.takeIf { it in names } ?: KeymapData.activeKeymap().name
        suppressEvents = true
        keymapModel.removeAllElements()
        names.forEach(keymapModel::addElement)
        keymapModel.selectedItem = target
        suppressEvents = false
        refresh()
    }

    private fun refresh(keepActionId: String? = null) {
        val keymap = selectedKeymap ?: return
        val summary = KeymapData.summarize(keymap, includeUnassigned = showAllBox.isSelected)
        currentSummary = summary
        tableModel.rows = summary.rows
        val count = if (summary.includesUnassigned) "${summary.assignedCount} assigned of ${summary.rows.size} actions"
        else "${summary.assignedCount} actions"
        infoLabel.text = "<html>Designed for: <b>${summary.targetOs.label}</b> &nbsp;·&nbsp; " +
            "${if (summary.isCustom) "Custom" else "Built-in (read-only)"} &nbsp;·&nbsp; " +
            "Inherits: ${summary.inheritance.joinToString(" → ") { StringUtil.escapeXmlEntities(it) }} &nbsp;·&nbsp; $count</html>"
        toolbar.updateActionsAsync() // re-evaluate which icon buttons are enabled
        applyFilter()
        keepActionId?.let(::selectAction)
    }

    private fun applyFilter() {
        val text = searchField.text.trim()
        sorter.rowFilter = if (text.isEmpty()) null else RowFilter.regexFilter("(?i)" + Regex.escape(text))
        updateView()
    }

    /** Shows the table or the Markdown text; the Markdown contains only the rows the search lets through. */
    private fun updateView() {
        if (!markdownBox.isSelected) {
            viewCards.show(viewPanel, CARD_TABLE)
            return
        }
        viewCards.show(viewPanel, CARD_MD)
        val summary = currentSummary ?: return
        val query = searchField.text.trim()
        val matching = if (query.isEmpty()) summary.rows else summary.rows.filter {
            it.shortcuts.contains(query, true) || it.actionName.contains(query, true) || it.actionId.contains(query, true)
        }

        // Swing's HTML view slows down sharply with size (≈0.4 s for 1,000 rows, ≈12 s for 9,000), so the
        // rendered view is capped. Assigned shortcuts always come first; search reaches every other row.
        val rows: List<ShortcutRow>
        var note: String? = null
        if (matching.size <= MAX_PREVIEW_ROWS) {
            rows = matching
        } else {
            val assigned = matching.filter { it.isAssigned }
            val keep = (assigned.take(MAX_PREVIEW_ROWS) +
                matching.asSequence().filter { !it.isAssigned }.take((MAX_PREVIEW_ROWS - assigned.size).coerceAtLeast(0))).toSet()
            rows = matching.filter { it in keep }
            note = "Showing ${rows.size} of ${matching.size} rows (assigned shortcuts first) to keep the view fast. " +
                "Type in the search box to find any other action."
        }
        val html = MarkdownPreview.toHtml(summary.copy(rows = rows), query, summary.rows.size, note)

        // Parse the HTML in the background so the panel stays clickable; only the newest request is shown
        val generation = ++renderGeneration
        val kit = markdownView.editorKit
        ApplicationManager.getApplication().executeOnPooledThread {
            val doc = kit.createDefaultDocument()
            kit.read(java.io.StringReader(html), doc, 0)
            ApplicationManager.getApplication().invokeLater({
                if (generation != renderGeneration || !markdownBox.isSelected) return@invokeLater
                markdownView.document = doc
                markdownView.caretPosition = 0
                if (query.isNotEmpty()) markdownView.scrollToReference(MarkdownPreview.FIRST_MATCH)
            }, ModalityState.any())
        }
    }

    private var renderGeneration = 0

    private fun selectAction(actionId: String) {
        val modelRow = tableModel.rows.indexOfFirst { it.actionId == actionId }.takeIf { it >= 0 } ?: return
        val viewRow = table.convertRowIndexToView(modelRow).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
    }

    // ---- editing ---------------------------------------------------------------------------------

    /** Saves a shortcut typed into the Shortcut cell (replaces the action's shortcuts with what was typed). */
    private fun applyTypedShortcut(actionId: String, text: String) {
        val original = selectedKeymap ?: return
        val os = KeymapData.detectTargetOs(original)
        val current = KeymapData.shortcutsOf(original, actionId)
        val currentText = current.joinToString(ShortcutFormatter.SEPARATOR) { ShortcutFormatter.format(it, os.isMac) }
        if (text.trim() == currentText.trim()) return

        val parsed = when (val r = ShortcutText.parse(text, os.isMac, current)) {
            is ShortcutText.Result.Error -> {
                Messages.showErrorDialog(project, "${r.message}\n\nExamples: ${ShortcutText.EXAMPLES}\n" +
                    "Several shortcuts: separate them with “or”.", "Can't Read Shortcut")
                reopenEditor(actionId, text)
                return
            }
            is ShortcutText.Result.Ok -> r.shortcuts
        }
        val name = KeymapData.actionName(actionId)
        if (parsed.isEmpty() && current.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(project, "Remove all shortcuts from “$name” ($currentText)?",
                "Remove Shortcuts?", "Remove", "Restore", Messages.getQuestionIcon())
            if (answer != Messages.YES) return refresh(actionId)
        }

        val keymap = KeymapEditing.ensureEditable(project, original) ?: return refresh(actionId)
        if (keymap.name != original.name) reloadKeymaps(keymap)

        val session = KeymapEditSession(keymap)
        val added = parsed.filter { it !in current }
        current.filter { it !in parsed }.forEach { session.remove(actionId, it) }
        added.forEach { session.add(actionId, it) }

        var undone = false
        for (sc in added) {
            val conflicts = ShortcutInput.conflictsOf(keymap, actionId, sc).flatMap { (id, list) -> list.map { Conflict(id, it) } }
            if (!ConflictResolver(project, session, os).resolve(actionId, sc, conflicts)) { undone = true; break }
        }
        statusLabel.text = when {
            undone -> "Change undone."
            parsed.isEmpty() -> "Removed all shortcuts from $name."
            else -> buildString {
                append("$name: ${parsed.joinToString(ShortcutFormatter.SEPARATOR) { ShortcutFormatter.format(it, os.isMac) }}")
                if (session.removedFromOthers > 0) append(" · removed from ${session.removedFromOthers} other action(s)")
                if (session.keptConflicts > 0) append(" · ${session.keptConflicts} conflict(s) kept")
                if (parsed.any(DoubleModifier::isDouble)) append(" · double modifiers work while this plugin is installed")
                append(".")
            }
        }
        refresh(actionId)
    }

    /** Opens the Shortcut cell again with the text that couldn't be read, so it can be corrected. */
    private fun reopenEditor(actionId: String, text: String) {
        val modelRow = tableModel.rows.indexOfFirst { it.actionId == actionId }.takeIf { it >= 0 } ?: return
        val viewRow = table.convertRowIndexToView(modelRow).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        if (table.editCellAt(viewRow, 0)) {
            (table.editorComponent as? javax.swing.text.JTextComponent)?.let { it.text = text; it.requestFocusInWindow() }
        }
    }

    private fun editSelectedRow() {
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val actionId = tableModel.rows[table.convertRowIndexToModel(viewRow)].actionId
        val original = selectedKeymap ?: return

        val keymap = KeymapEditing.ensureEditable(project, original) ?: return
        if (keymap.name != original.name) reloadKeymaps(keymap) // switched to the new editable copy
        val os = KeymapData.detectTargetOs(keymap)

        val dialog = ShortcutEditDialog(project, keymap, os, actionId)
        if (!dialog.showAndGet()) return refresh(actionId)

        val session = KeymapEditSession(keymap)
        val name = KeymapData.actionName(actionId)
        when (val result = dialog.result) {
            is EditResult.RemoveAll -> {
                session.removeAll(actionId)
                statusLabel.text = "Removed all shortcuts from $name."
            }
            is EditResult.Assign -> {
                val sc = result.shortcut
                if (result.replaceExisting) {
                    // Replace only shortcuts of the same kind: a new Ctrl+Click keeps the keyboard shortcut and vice versa
                    KeymapData.shortcutsOf(keymap, actionId)
                        .filter { if (sc is MouseShortcut) it is MouseShortcut else it is KeyboardShortcut }
                        .forEach { session.remove(actionId, it) }
                }
                session.add(actionId, sc)

                val conflicts = ShortcutInput.conflictsOf(keymap, actionId, sc)
                    .flatMap { (id, list) -> list.map { Conflict(id, it) } }
                // The dialog already asked what to do with actions that use it too
                val kept = ConflictResolver(project, session, os).resolve(conflicts, result.onConflict)
                statusLabel.text = if (!kept) "Change undone." else buildString {
                    append("Assigned ${ShortcutFormatter.format(sc, os.isMac)} to $name")
                    if (session.removedFromOthers > 0) append(" · removed from ${session.removedFromOthers} other action(s)")
                    if (session.reassigned > 0) append(" · reassigned ${session.reassigned}")
                    if (session.keptConflicts > 0) append(" · ${session.keptConflicts} conflict(s) kept")
                    append(".")
                }
            }
            null -> {}
        }
        refresh(actionId)
    }

    // ---- keymap management -----------------------------------------------------------------------

    private fun copyKeymap() {
        val source = selectedKeymap ?: return
        KeymapEditing.copy(project, source)?.let {
            reloadKeymaps(it)
            statusLabel.text = "Created “${it.presentableName}” from “${source.presentableName}”."
        }
    }

    private fun newEmptyKeymap() {
        KeymapEditing.createEmpty(project)?.let {
            reloadKeymaps(it)
            statusLabel.text = "Created empty keymap “${it.presentableName}”. Double-click an action to assign a shortcut." +
                if (showAllBox.isSelected) "" else " Tick “Show all actions” to see them."
        }
    }

    private fun deleteKeymap() {
        val keymap = selectedKeymap ?: return
        val name = keymap.presentableName
        KeymapEditing.delete(project, keymap)?.let {
            reloadKeymaps(it)
            statusLabel.text = "Deleted keymap “$name”."
        }
    }

    private fun makeActive() {
        val keymap = selectedKeymap ?: return
        KeymapData.makeActive(keymap)
        keymapCombo.repaint()
        statusLabel.text = "“${keymap.presentableName}” is now the IDE's active keymap."
        refresh()
    }

    // ---- export ----------------------------------------------------------------------------------

    /** Markdown export always lists only assigned shortcuts, whatever “Show all actions” is set to. */
    private fun exportToFile() {
        val keymap = selectedKeymap ?: return
        val summary = KeymapData.summarize(keymap, includeUnassigned = false)
        if (summary.assignedCount == 0) {
            Messages.showInfoMessage(project, "“${summary.name}” has no assigned shortcuts, so there is nothing to export.", "Export .md")
            return
        }
        val descriptor = FileSaverDescriptor("Export Keymap", "Save keymap as Markdown", "md")
        val safeName = summary.name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save("keymap-$safeName.md") ?: return

        try {
            target.file.writeText(MarkdownExporter.toMarkdown(summary))
        } catch (e: java.io.IOException) {
            Messages.showErrorDialog(project, "Can't write ${target.file.path}: ${e.message}", "Export .md")
            return
        }

        // Open the exported file in the editor so the user immediately sees the Markdown preview.
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target.file)?.let {
            OpenFileDescriptor(project, it).navigate(true)
        }
    }

    // ---- keymap files ----------------------------------------------------------------------------

    /** Keymap files, macro files or folders with them: each file is recognized by its content. */
    private fun importKeymap() {
        val descriptor = FileChooserDescriptorFactory.multiFilesOrDirs().withExtensionFilter("xml")
            .withTitle("Import Keymap or Macros")
            .withDescription("Choose keymap .xml files, a macros file, or a folder with them (an export folder)")
        val chosen = FileChooser.chooseFiles(descriptor, project, null).map(VfsUtilCore::virtualToIoFile)
        if (chosen.isEmpty()) return
        val r = KeymapTransfer.import(project, chosen) ?: return
        r.keymaps.lastOrNull()?.let { reloadKeymaps(it) }
        MacroStore.getInstance().registerActions()
        if (r.keymaps.isEmpty()) refresh(null)
        val parts = buildList {
            if (r.keymaps.isNotEmpty()) add("keymap(s) ${r.keymaps.joinToString(", ") { "“${it.presentableName}”" }}")
            if (r.macrosAdded > 0) add("${r.macrosAdded} new macro(s)")
            if (r.macrosReplaced > 0) add("${r.macrosReplaced} macro(s) replaced")
        }
        statusLabel.text = buildString {
            append(if (parts.isEmpty()) "Nothing new imported" else "Imported " + parts.joinToString(", "))
            if (r.macrosUnchanged > 0) append(" · ${r.macrosUnchanged} macro(s) already here")
            if (r.ignoredFiles.isNotEmpty()) append(" · ${r.ignoredFiles.size} other file(s) ignored")
            append(".")
        }
    }

    private fun migrateKeymap() {
        val source = selectedKeymap ?: return
        val r = OsMigration.migrate(project, source) ?: return
        reloadKeymaps(r.keymap)
        statusLabel.text = buildString {
            append("Created “${r.keymap.presentableName}” for ${KeymapData.detectTargetOs(r.keymap).label}: ${r.moved} shortcut(s) moved")
            if (r.removedConflicts > 0) append(" · ${r.removedConflicts} colliding default(s) removed")
            if (r.skipped > 0) append(" · ${r.skipped} gesture shortcut(s) skipped")
            append(".")
        }
    }

    // ---- macros ----------------------------------------------------------------------------------

    /** Create Macro: choose the macro → Record → (recording window) → Stop → review steps and shortcut → Save. */
    private fun createMacro() {
        val start = MacroStartDialog(project)
        start.show()
        val existing = start.chooser.existing
        when (start.exitCode) {
            com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE -> {
                val name = start.chooser.name
                MacroRecordingWindow(project, name) { steps -> saveMacro(existing, name, steps) }.show()
                statusLabel.text = "Recording “$name”… use the IDE as usual, then press Stop in the recording window."
            }
            MACRO_EDIT_EXIT -> {
                val ide = start.chooser.ideMacro
                if (ide != null) saveMacro(null, start.chooser.name, ide.steps) // a copy; IntelliJ's original is untouched
                else MacroStore.getInstance().get(existing ?: return)?.let { saveMacro(it.name, it.name, it.steps) }
            }
            MACRO_DELETE_EXIT -> {
                val name = existing ?: return
                if (Messages.showYesNoDialog(project, "Delete macro “$name” and its shortcuts in all keymaps?", "Delete Macro",
                        "Delete", "Cancel", Messages.getWarningIcon()) == Messages.YES) {
                    MacroStore.getInstance().remove(name)
                    statusLabel.text = "Deleted macro “$name”."
                    refresh()
                }
            }
        }
    }

    /** Opens the review dialog for recorded/edited steps and saves the macro and its shortcut. */
    private fun saveMacro(existing: String?, name: String, steps: List<MacroStep>) {
        val original = selectedKeymap ?: return
        val os = KeymapData.detectTargetOs(original)
        val dialog = MacroEditDialog(project, original, os, existing, name, steps)
        if (!dialog.showAndGet()) {
            statusLabel.text = if (existing == null) "Macro discarded." else "Macro “$existing” unchanged."
            return
        }
        val macro = Macro(dialog.chooser.name, dialog.steps)
        MacroStore.getInstance().save(macro, dialog.chooser.existing)

        val sc = dialog.shortcut
        val keymapForShortcut = if (sc != null || dialog.shortcutCleared) KeymapEditing.ensureEditable(project, original) else original
        var shortcutText = ""
        if (keymapForShortcut != null && (sc != null || dialog.shortcutCleared)) {
            if (keymapForShortcut.name != original.name) reloadKeymaps(keymapForShortcut)
            val current = KeymapData.shortcutsOf(keymapForShortcut, macro.actionId)
            if (sc == null || sc !in current) {
                val session = KeymapEditSession(keymapForShortcut)
                current.forEach { session.remove(macro.actionId, it) }
                if (sc != null) {
                    session.add(macro.actionId, sc)
                    val conflicts = ShortcutInput.conflictsOf(keymapForShortcut, macro.actionId, sc)
                        .flatMap { (id, list) -> list.map { Conflict(id, it) } }
                    ConflictResolver(project, session, os).resolve(macro.actionId, sc, conflicts)
                }
            }
            KeymapData.shortcutsOf(keymapForShortcut, macro.actionId).firstOrNull()?.let {
                shortcutText = " · shortcut ${ShortcutFormatter.format(it, os.isMac)}"
            }
        }
        statusLabel.text = "Saved macro “${macro.name}” (${macro.steps.size} steps)$shortcutText."
        refresh(macro.actionId)
    }

    /** Writes the selected keymap and all macros into a folder; Import on that folder brings both back. */
    private fun exportKeymap() {
        val keymap = selectedKeymap ?: return
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle("Export Keymap and Macros")
            .withDescription("Choose a folder: the selected keymap and all macros are saved there")
        val folder = FileChooser.chooseFile(descriptor, project, null)?.let(VfsUtilCore::virtualToIoFile) ?: return
        val written = KeymapTransfer.export(project, keymap, folder) ?: return
        statusLabel.text = "Exported ${written.joinToString(" and ") { it.name }} to ${folder.path}." +
            if (keymap.parent != null) " The keymap stores only changes on top of “${keymap.parent!!.presentableName}”." else ""
    }

    private companion object {
        const val SHOW_ALL_KEY = "io.github.khorkovenko.keymapviewer.showAllActions"
        const val SHOW_MD_KEY = "io.github.khorkovenko.keymapviewer.showAsMarkdown"
        const val MAX_PREVIEW_ROWS = 1500
        const val CARD_TABLE = "table"
        const val CARD_MD = "markdown"
    }

    private class ShortcutTableModel : AbstractTableModel() {
        private val columns = arrayOf("Shortcut", "Action", "Action ID")
        var rows: List<ShortcutRow> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = with(rows[row]) {
            when (column) { 0 -> shortcuts; 1 -> actionName; else -> actionId }
        }

        /** Called with the model row and the typed text when a Shortcut cell edit is committed. */
        var onShortcutTyped: (Int, String) -> Unit = { _, _ -> }

        override fun isCellEditable(row: Int, column: Int) = column == 0

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0 && row in rows.indices) onShortcutTyped(row, value?.toString() ?: "")
        }
    }
}

/** A row for a vertical BoxLayout that is never stretched taller than it needs to be. */
private class CompactRow(layout: java.awt.LayoutManager) : JPanel(layout) {
    init { alignmentX = LEFT_ALIGNMENT }
    override fun getMaximumSize(): java.awt.Dimension = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Lays components out left to right, vertically centered on each line, and starts a new line only when the
 * next component doesn't fit. Unlike FlowLayout/WrapLayout it adds no extra space above or below the lines.
 */
private class LineWrapLayout(private val hgap: Int, private val vgap: Int) : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
    override fun removeLayoutComponent(comp: java.awt.Component?) {}

    private fun lines(target: java.awt.Container): List<List<java.awt.Component>> {
        val ins = target.insets
        val maxWidth = if (target.width <= 0) Int.MAX_VALUE else target.width - ins.left - ins.right
        val result = ArrayList<List<java.awt.Component>>()
        var line = ArrayList<java.awt.Component>()
        var lineWidth = 0
        for (c in target.components) {
            if (!c.isVisible) continue
            val w = c.preferredSize.width
            val needed = if (line.isEmpty()) w else lineWidth + hgap + w
            if (line.isNotEmpty() && needed > maxWidth) {
                result += line; line = arrayListOf(c); lineWidth = w
            } else {
                line += c; lineWidth = needed
            }
        }
        if (line.isNotEmpty()) result += line
        return result
    }

    override fun preferredLayoutSize(target: java.awt.Container): java.awt.Dimension {
        val ins = target.insets
        val ls = lines(target)
        val height = ls.sumOf { l -> l.maxOf { it.preferredSize.height } } + vgap * (ls.size - 1).coerceAtLeast(0)
        val width = ls.maxOfOrNull { l -> l.sumOf { it.preferredSize.width } + hgap * (l.size - 1) } ?: 0
        return java.awt.Dimension(width + ins.left + ins.right, height + ins.top + ins.bottom)
    }

    // Never forces the tool window wider: it wraps instead
    override fun minimumLayoutSize(target: java.awt.Container) = java.awt.Dimension(0, preferredLayoutSize(target).height)

    override fun layoutContainer(target: java.awt.Container) {
        val ins = target.insets
        var y = ins.top
        for (line in lines(target)) {
            val lineHeight = line.maxOf { it.preferredSize.height }
            var x = ins.left
            for (c in line) {
                val d = c.preferredSize
                c.setBounds(x, y + (lineHeight - d.height) / 2, d.width, d.height)
                x += d.width + hgap
            }
            y += lineHeight + vgap
        }
    }
}
