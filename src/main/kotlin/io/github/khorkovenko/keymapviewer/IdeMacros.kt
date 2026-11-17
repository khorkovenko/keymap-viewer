package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.JDOMUtil
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.KeyStroke

/**
 * Reads the macros made with IntelliJ's own macro recorder (Edit | Macros). Its API is internal, so they are
 * read from the file the IDE saves them in: <config>/options/macros.xml. The IDE writes that file when it saves
 * settings, so a macro recorded a moment ago may only appear after the IDE has saved (e.g. on focus loss or restart).
 */
object IdeMacros {

    private val file: Path get() = Path.of(PathManager.getOptionsPath(), "macros.xml")

    fun all(): List<Macro> {
        val f = file
        if (!Files.isRegularFile(f)) return emptyList()
        val root = runCatching { JDOMUtil.load(f) }.getOrNull() ?: return emptyList()
        val component = root.getChildren("component").firstOrNull { it.getAttributeValue("name") == "ActionMacroManager" }
            ?: return emptyList()
        return component.getChildren("macro").mapNotNull { m ->
            val name = m.getAttributeValue("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val steps = m.children.mapNotNull { e ->
                when (e.name) {
                    "action" -> e.getAttributeValue("id")?.let { MacroStep.Action(it) }
                    "typing" -> (e.text?.takeIf { it.isNotEmpty() } ?: e.getAttributeValue("text"))
                        ?.replace("&#x20;", " ")?.takeIf { it.isNotEmpty() }?.let { MacroStep.Typing(it) }
                    // IntelliJ really spells it "shortuct": a key press such as "ENTER" or "control S"
                    "shortuct", "shortcut" -> e.getAttributeValue("text")
                        ?.let { KeyStroke.getKeyStroke(it.replace("control", "ctrl")) }
                        ?.let { MacroStep.Key(it) }
                    else -> null
                }
            }
            Macro(name, steps)
        }.sortedBy { it.name.lowercase() }
    }
}
