package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.keymap.impl.KeymapImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.KeyStrokeAdapter
import org.jdom.Element
import java.io.File
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/** Creating keymaps: copies, empty keymaps, and editable copies of read-only built-in keymaps. */
object KeymapEditing {

    private val manager: KeymapManagerEx get() = KeymapManagerEx.getInstanceEx()

    private fun nameTaken(name: String) =
        manager.allKeymaps.any { it.name.equals(name, true) || it.presentableName.equals(name, true) }

    fun uniqueName(base: String): String {
        if (!nameTaken(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { !nameTaken(it) }
    }

    internal fun register(keymap: Keymap, makeActive: Boolean): Keymap {
        manager.schemeManager.addScheme(keymap)
        if (makeActive) manager.activeKeymap = keymap
        return keymap
    }

    /**
     * Copy of [source]. For a built-in keymap this creates a child that inherits from it (how IntelliJ itself does it);
     * for a custom keymap [Keymap.deriveKeymap] makes an independent copy of all its shortcuts.
     */
    fun copy(project: Project, source: Keymap): Keymap? {
        val dlg = NewKeymapDialog(project, "Copy Keymap", uniqueName("${source.presentableName} copy"), askOs = false,
            note = "A copy of “${source.presentableName}”. Designed for ${KeymapData.detectTargetOs(source).label}.")
        if (!dlg.showAndGet()) return null
        val copy = register(source.deriveKeymap(dlg.keymapName), dlg.makeActive)
        DoubleModifierStore.getInstance().copyKeymap(source.name, copy.name)
        return copy
    }

    /**
     * Keymap with no shortcuts at all. It is based on the built-in keymap of the chosen OS (with every shortcut
     * cleared), so the plugin and the IDE still know which OS it is designed for.
     */
    fun createEmpty(project: Project): Keymap? {
        val dlg = NewKeymapDialog(project, "New Empty Keymap", uniqueName("My keymap"), askOs = true,
            note = "Starts with no shortcuts. Double-click actions in the table to assign them.")
        if (!dlg.showAndGet()) return null
        val base = builtInFor(dlg.os) ?: run {
            Messages.showErrorDialog(project, "No built-in keymap for ${dlg.os.label} was found.", "Keymap Viewer")
            return null
        }
        val empty = base.deriveKeymap(dlg.keymapName)
        base.actionIdList.toList().forEach { empty.removeAllActionShortcuts(it) }
        return register(empty, dlg.makeActive)
    }

    // ---- keymap files (same XML format IntelliJ uses in its keymaps folder) ------------------------

    /**
     * Writes [keymap] as an IntelliJ keymap XML file: its parent's name plus only the shortcuts it changes
     * (an `<action>` with no children means "shortcuts removed"). A keymap without a parent is written in full.
     * Built by hand rather than with KeymapImpl.writeScheme(), which would mark unsaved changes as saved.
     */
    fun writeXml(keymap: Keymap, file: File) {
        val impl = keymap as? KeymapImpl
        val root = Element("keymap").setAttribute("version", "1").setAttribute("name", keymap.name)
        keymap.parent?.let { root.setAttribute("parent", it.name) }
        val ids = keymap.actionIdList
            .filter { impl == null || keymap.parent == null || impl.hasOwnActionId(it) }
            .sorted()
        for (id in ids) {
            val action = Element("action").setAttribute("id", id)
            for (sc in keymap.getShortcuts(id)) {
                when (sc) {
                    is KeyboardShortcut -> action.addContent(Element("keyboard-shortcut").apply {
                        setAttribute("first-keystroke", KeyStrokeAdapter.toString(sc.firstKeyStroke))
                        sc.secondKeyStroke?.let { setAttribute("second-keystroke", KeyStrokeAdapter.toString(it)) }
                    })
                    is MouseShortcut -> action.addContent(
                        Element("mouse-shortcut").setAttribute("keystroke", KeymapUtil.getMouseShortcutString(sc)))
                    else -> {} // keyboard gestures (double-Ctrl etc.) are skipped
                }
            }
            root.addContent(action)
        }
        file.writeText(JDOMUtil.write(root))
    }

    /**
     * Reads an IntelliJ keymap XML file and adds it as a new custom keymap.
     * If the file names a parent keymap that exists here, the new keymap inherits from it; otherwise it is based
     * on the built-in keymap for the same OS.
     */
    fun importXml(project: Project, file: File): Keymap? {
        val root = try {
            JDOMUtil.load(file.toPath())
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "“${file.name}” can't be read as XML:\n${e.message}", "Import Keymap")
            return null
        }
        if (root.name != "keymap") {
            Messages.showErrorDialog(project, "“${file.name}” is not an IntelliJ keymap file (no <keymap> element).", "Import Keymap")
            return null
        }
        val fileKeymapName = root.getAttributeValue("name")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val parentName = root.getAttributeValue("parent")
        val parent = parentName?.let { manager.getKeymap(it) }
        val os = parent?.let(KeymapData::detectTargetOs)
            ?: parentName?.let(KeymapData::osFromName)
            ?: KeymapData.osFromName(fileKeymapName)
            ?: TargetOs.WINDOWS_LINUX
        val actions = root.getChildren("action")

        val basedOn = when {
            parent != null -> "Based on “${parent.presentableName}”."
            parentName != null -> "Its parent keymap “$parentName” isn't installed here, so it will be based on the built-in ${os.label} keymap."
            else -> "Full keymap (no parent) for ${os.label}."
        }
        val dlg = NewKeymapDialog(project, "Import Keymap", uniqueName(fileKeymapName), askOs = false,
            note = "<html>${actions.size} action(s) from ${file.name}.<br>$basedOn</html>")
        if (!dlg.showAndGet()) return null

        val keymap = if (parent != null) {
            parent.deriveKeymap(dlg.keymapName)
        } else {
            val base = builtInFor(os) ?: manager.getKeymap("\$default") ?: run {
                Messages.showErrorDialog(project, "No built-in keymap was found to base the import on.", "Import Keymap")
                return null
            }
            base.deriveKeymap(dlg.keymapName).also { k ->
                // Without a known parent, start from nothing so only the file's shortcuts are used
                if (parentName == null) base.actionIdList.toList().forEach { k.removeAllActionShortcuts(it) }
            }
        }

        var skipped = 0
        for (action in actions) {
            val id = action.getAttributeValue("id") ?: continue
            keymap.removeAllActionShortcuts(id) // an empty <action> means "no shortcuts"
            for (sc in action.children) {
                val shortcut: Shortcut? = try {
                    when (sc.name) {
                        "keyboard-shortcut" -> KeyStrokeAdapter.getKeyStroke(sc.getAttributeValue("first-keystroke") ?: "")
                            ?.let { first ->
                                val secondText = sc.getAttributeValue("second-keystroke")
                                val second = secondText?.let(KeyStrokeAdapter::getKeyStroke)
                                if (secondText != null && second == null) null else KeyboardShortcut(first, second)
                            }
                        "mouse-shortcut" -> KeymapUtil.parseMouseShortcut(sc.getAttributeValue("keystroke") ?: "")
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
                if (shortcut != null) keymap.addShortcut(id, shortcut) else skipped++
            }
        }
        register(keymap, dlg.makeActive)
        if (skipped > 0) {
            Messages.showWarningDialog(project, "$skipped shortcut(s) in the file couldn't be read and were skipped.", "Import Keymap")
        }
        return keymap
    }

    internal fun builtInFor(os: TargetOs): Keymap? {
        val preferred = when (os) {
            TargetOs.MAC -> listOf("Mac OS X 10.5+", "Mac OS X")
            TargetOs.LINUX -> listOf("Default for XWin", "Default for GNOME", "Default for KDE")
            else -> listOf("\$default")
        }
        return preferred.firstNotNullOfOrNull { manager.getKeymap(it) }
            ?: manager.allKeymaps.firstOrNull { !it.canModify() && KeymapData.detectTargetOs(it) == os }
    }

    /**
     * Deletes a custom keymap after confirmation. Built-in keymaps can't be deleted, and neither can a keymap
     * other custom keymaps are based on (they would lose their shortcuts).
     * If it is the active keymap, the IDE switches to the keymap it was based on.
     * @return the keymap to show next, or null if nothing was deleted.
     */
    fun delete(project: Project, keymap: Keymap): Keymap? {
        if (!keymap.canModify()) {
            Messages.showInfoMessage(project, "“${keymap.presentableName}” is built into the IDE and can't be deleted.", "Delete Keymap")
            return null
        }
        val children = manager.allKeymaps.filter { it.parent?.name == keymap.name }
        if (children.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "“${keymap.presentableName}” can't be deleted because these keymaps are based on it:\n" +
                    children.joinToString("\n") { "  • ${it.presentableName}" } + "\n\nDelete them first.",
                "Delete Keymap",
            )
            return null
        }
        val wasActive = keymap.name == manager.activeKeymap.name
        val fallback = keymap.parent ?: builtInFor(KeymapData.detectTargetOs(keymap)) ?: manager.getKeymap("\$default")
        val activeNote = if (wasActive) "\n\nIt is your active keymap, so the IDE will switch to “${fallback?.presentableName}”." else ""
        val answer = Messages.showYesNoDialog(
            project,
            "Delete keymap “${keymap.presentableName}” and all its shortcuts? This can't be undone.$activeNote",
            "Delete Keymap",
            "Delete", "Cancel", Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return null
        if (wasActive && fallback != null) manager.activeKeymap = fallback
        manager.schemeManager.removeScheme(keymap)
        DoubleModifierStore.getInstance().removeKeymap(keymap.name)
        return fallback ?: manager.activeKeymap
    }

    /**
     * Built-in keymaps are read-only. Offers to create an editable copy (like Settings | Keymap does)
     * and returns the keymap to edit, or null if the user declined.
     */
    fun ensureEditable(project: Project, keymap: Keymap): Keymap? {
        if (keymap.canModify()) return keymap
        val name = uniqueName("${keymap.presentableName} (custom)")
        val answer = Messages.showYesNoDialog(
            project,
            "“${keymap.presentableName}” is a built-in keymap and can't be changed.\n\n" +
                "Create an editable copy named “$name”? It inherits everything from “${keymap.presentableName}”, " +
                "and your changes are stored only in the copy.",
            "Built-in Keymap",
            "Create Copy", "Cancel", Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return null
        val copy = register(keymap.deriveKeymap(name), makeActive = keymap.name == manager.activeKeymap.name)
        DoubleModifierStore.getInstance().copyKeymap(keymap.name, copy.name)
        return copy
    }
}

/** Asks for a keymap name (and the target OS for an empty keymap). */
private class NewKeymapDialog(
    project: Project,
    dialogTitle: String,
    defaultName: String,
    private val askOs: Boolean,
    private val note: String,
) : DialogWrapper(project) {

    private val nameField = JBTextField(defaultName, 28)
    private val osCombo = ComboBox(arrayOf(TargetOs.MAC, TargetOs.WINDOWS_LINUX, TargetOs.LINUX)).apply {
        renderer = SimpleListCellRenderer.create { label, os, _ -> label.text = os?.label ?: "" }
        selectedItem = when {
            ShortcutFormatter.currentOsIsMac -> TargetOs.MAC
            com.intellij.openapi.util.SystemInfo.isWindows -> TargetOs.WINDOWS_LINUX
            else -> TargetOs.LINUX
        }
    }
    private val activeBox = JBCheckBox("Make it the active keymap")

    val keymapName: String get() = nameField.text.trim()
    val os: TargetOs get() = osCombo.selectedItem as TargetOs
    val makeActive: Boolean get() = activeBox.isSelected

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent {
        val b = FormBuilder.createFormBuilder().addLabeledComponent("Name:", nameField)
        if (askOs) b.addLabeledComponent("Designed for:", osCombo)
        b.addComponentToRightColumn(activeBox)
        b.addComponent(JBLabel(note).apply { foreground = UIUtil.getContextHelpForeground() })
        return b.panel.apply { border = JBUI.Borders.empty(4) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = when {
        keymapName.isEmpty() -> ValidationInfo("Enter a name", nameField)
        KeymapEditing.uniqueName(keymapName) != keymapName -> ValidationInfo("A keymap with this name already exists", nameField)
        else -> null
    }
}
