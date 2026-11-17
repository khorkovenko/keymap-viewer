package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Sorts actions into meaningful blocks for the Markdown view/export. The blocks follow the IDE's main menu
 * (File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, Git, Window, Help…); actions that are not in
 * any menu are placed by their ID (Editor, Debugger, Version Control, Tool Windows) or under "Other".
 */
class ActionCategories private constructor(
    private val byId: Map<String, String>,
    /** Block names in display order. */
    val order: List<String>,
) {
    fun of(actionId: String): String = byId[actionId] ?: fallback(actionId)

    private fun fallback(id: String): String = when {
        id.startsWith(MacroStore.PREFIX) -> MACROS
        id.startsWith("Editor") || id.startsWith("\$") -> EDITOR
        id.contains("Debug", true) || id.startsWith("XDebugger") || id.startsWith("Step") -> DEBUGGER
        id.startsWith("Vcs") || id.startsWith("Git") || id.startsWith("ChangesView") || id.startsWith("Diff") -> VCS
        id.contains("ToolWindow") -> TOOL_WINDOWS
        else -> OTHER
    }

    companion object {
        const val MACROS = "Macros"
        const val EDITOR = "Editor"
        const val DEBUGGER = "Debugger"
        const val VCS = "Version Control"
        const val TOOL_WINDOWS = "Tool Windows"
        const val OTHER = "Other"

        fun load(): ActionCategories {
            val manager = ActionManager.getInstance()
            val byId = HashMap<String, String>()
            val menuOrder = ArrayList<String>()

            fun collect(action: AnAction, category: String, depth: Int) {
                manager.getId(action)?.let { byId.putIfAbsent(it, category) }
                if (action is DefaultActionGroup && depth < 8) {
                    action.childActionsOrStubs.forEach { collect(it, category, depth + 1) }
                }
            }

            (manager.getAction("MainMenu") as? DefaultActionGroup)?.childActionsOrStubs?.forEach { top ->
                val name = top.templatePresentation.text?.replace("_", "")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (name !in menuOrder) menuOrder += name
                collect(top, name, 0)
            }
            val order = menuOrder + listOf(MACROS, EDITOR, DEBUGGER, VCS, TOOL_WINDOWS, OTHER).filter { it !in menuOrder }
            return ActionCategories(byId, order)
        }
    }
}
