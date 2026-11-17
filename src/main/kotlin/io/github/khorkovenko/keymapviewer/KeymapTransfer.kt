package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.LocalFileSystem
import org.jdom.Element
import java.io.File
import java.util.Base64
import javax.swing.KeyStroke

/**
 * Import and export of keymaps together with the plugin's macros.
 *
 * Import accepts any mix of files and folders. Each .xml file is recognized by its content:
 *  • an IntelliJ keymap file (<keymap>) → added as a keymap (with its double modifiers, if it carries them);
 *  • a Keymap Viewer macros file (<keymap-viewer-macros>) → its macros are added;
 *  • IntelliJ's own macros.xml (Edit | Macros) → its macros are added as plugin macros.
 * A folder is searched (not recursively) for such files. Macros are imported before keymaps, so imported
 * keymaps' macro shortcuts point at existing macros straight away.
 *
 * Export writes the selected keymap and all plugin macros into a folder.
 */
object KeymapTransfer {

    const val MACROS_FILE = "keymap-viewer-macros.xml"
    private const val MACROS_ROOT = "keymap-viewer-macros"

    private sealed interface Found {
        val file: File
        data class KeymapFile(override val file: File) : Found
        data class Macros(override val file: File, val macros: List<Macro>) : Found
    }

    data class ImportResult(
        val keymaps: List<Keymap>,
        val macrosAdded: Int,
        val macrosReplaced: Int,
        val macrosUnchanged: Int,
        val ignoredFiles: List<String>,
    )

    // ---- import ------------------------------------------------------------------------------------

    fun import(project: Project, chosen: List<File>): ImportResult? {
        val files = chosen.flatMap { f ->
            if (f.isDirectory) f.listFiles { x -> x.isFile && x.extension.equals("xml", true) }?.sortedBy { it.name }.orEmpty()
            else listOf(f)
        }.distinct()
        val ignored = mutableListOf<String>()
        val found = files.mapNotNull { f -> detect(f).also { if (it == null) ignored += f.name } }
        if (found.isEmpty()) {
            Messages.showWarningDialog(project,
                if (files.isEmpty()) "No .xml files were found in the selected folder."
                else "None of the selected files is a keymap or macros file:\n${ignored.joinToString("\n")}",
                "Import")
            return null
        }

        val (added, replaced, unchanged) = importMacros(project, found.filterIsInstance<Found.Macros>().flatMap { it.macros })
        val keymaps = found.filterIsInstance<Found.KeymapFile>().mapNotNull { KeymapEditing.importXml(project, it.file) }
        return ImportResult(keymaps, added, replaced, unchanged, ignored)
    }

    /** What kind of file this is, or null if it is none of the supported ones. */
    private fun detect(file: File): Found? {
        val root = runCatching { JDOMUtil.load(file.toPath()) }.getOrNull() ?: return null
        return when (root.name) {
            "keymap" -> Found.KeymapFile(file)
            MACROS_ROOT -> Found.Macros(file, readMacros(root))
            else -> IdeMacros.parse(root)?.let { Found.Macros(file, it) } // IntelliJ's macros.xml
        }
    }

    /** @return added, replaced, unchanged */
    private fun importMacros(project: Project, macros: List<Macro>): Triple<Int, Int, Int> {
        if (macros.isEmpty()) return Triple(0, 0, 0)
        val store = MacroStore.getInstance()
        val incoming = macros.associateBy { it.name }.values // the last one wins if a name appears twice
        val new = incoming.filter { store.get(it.name) == null }
        val same = incoming.filter { store.get(it.name)?.steps == it.steps }
        val different = incoming.filter { m -> store.get(m.name).let { it != null && it.steps != m.steps } }

        var replace = false
        if (different.isNotEmpty()) {
            val names = different.take(8).joinToString("\n") { "• ${it.name}" } + if (different.size > 8) "\n…" else ""
            replace = Messages.showYesNoDialog(project,
                "${different.size} macro(s) already exist here with different steps:\n$names\n\n" +
                    "Replace them with the imported versions? Their shortcuts stay.",
                "Import Macros", "Replace", "Keep Mine", Messages.getQuestionIcon()) == Messages.YES
        }
        new.forEach { store.save(it, null) }
        if (replace) different.forEach { store.save(it, null) }
        return Triple(new.size, if (replace) different.size else 0, same.size + if (replace) 0 else different.size)
    }

    // ---- export ------------------------------------------------------------------------------------

    /** Writes [keymap] and every plugin macro into [folder]. @return the files written, or null if cancelled. */
    fun export(project: Project, keymap: Keymap, folder: File): List<File>? {
        val safeName = keymap.presentableName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val keymapFile = File(folder, "$safeName.xml")
        val macros = MacroStore.getInstance().all()
        val macrosFile = File(folder, MACROS_FILE).takeIf { macros.isNotEmpty() }
        val targets = listOfNotNull(keymapFile, macrosFile)

        val existing = targets.filter { it.exists() }
        if (existing.isNotEmpty() && Messages.showYesNoDialog(project,
                "${existing.joinToString(", ") { it.name }} already exist(s) in ${folder.name}. Overwrite?",
                "Export", "Overwrite", "Cancel", Messages.getWarningIcon()) != Messages.YES) return null

        try {
            KeymapEditing.writeXml(keymap, keymapFile)
            macrosFile?.let { writeMacros(macros, it) }
        } catch (e: java.io.IOException) {
            Messages.showErrorDialog(project, "Can't write to ${folder.path}: ${e.message}", "Export")
            return null
        }
        LocalFileSystem.getInstance().refreshIoFiles(targets) // so the IDE's file views show the new files
        return targets
    }

    // ---- macros file format ------------------------------------------------------------------------

    private fun writeMacros(macros: List<Macro>, file: File) {
        val root = Element(MACROS_ROOT).setAttribute("version", "1")
        macros.forEach { m ->
            root.addContent(Element("macro").setAttribute("name", m.name).apply {
                m.steps.forEach { s ->
                    addContent(when (s) {
                        is MacroStep.Action -> Element("action").setAttribute("id", s.actionId)
                        // Base64 keeps spaces, tabs and line breaks exactly as typed
                        is MacroStep.Typing -> Element("typing").setAttribute("text", Base64.getEncoder().encodeToString(s.text.toByteArray()))
                        is MacroStep.ToolWindow -> Element("tool-window").setAttribute("id", s.id)
                        is MacroStep.Key -> Element("key").setAttribute("stroke", s.stroke.toString())
                    })
                }
            })
        }
        file.writeText(JDOMUtil.write(root))
    }

    private fun readMacros(root: Element): List<Macro> = root.getChildren("macro").mapNotNull { m ->
        val name = m.getAttributeValue("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Macro(name, m.children.mapNotNull { e ->
            when (e.name) {
                "action" -> e.getAttributeValue("id")?.let { MacroStep.Action(it) }
                "typing" -> e.getAttributeValue("text")
                    ?.let { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }?.let { MacroStep.Typing(it) }
                "tool-window" -> e.getAttributeValue("id")?.let { MacroStep.ToolWindow(it) }
                "key" -> e.getAttributeValue("stroke")?.let(KeyStroke::getKeyStroke)?.let { MacroStep.Key(it) }
                else -> null
            }
        })
    }
}
