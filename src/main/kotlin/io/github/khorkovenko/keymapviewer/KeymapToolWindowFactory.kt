package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.ui.content.ContentFactory

class KeymapToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = KeymapPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel) // stops the keymap listener when the tool window content is closed
        toolWindow.contentManager.addContent(content)
        // Buttons in the tool window's title bar, next to ⋮ and —
        toolWindow.setTitleActions(listOf(OpenInWindowAction(toolWindow), MaximizeAction(project, toolWindow)))
    }
}

/** Maximizes the tool window over the editor area (and back). */
private class MaximizeAction(private val project: Project, private val toolWindow: ToolWindow) :
    DumbAwareAction("Maximize", "Fill the IDE window with Keymap Viewer", AllIcons.General.ExpandComponent) {

    private val manager get() = ToolWindowManager.getInstance(project)

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val docked = toolWindow.type == ToolWindowType.DOCKED || toolWindow.type == ToolWindowType.SLIDING
        e.presentation.isEnabledAndVisible = docked
        val maximized = docked && manager.isMaximized(toolWindow)
        e.presentation.icon = if (maximized) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
        e.presentation.text = if (maximized) "Restore Size" else "Maximize"
    }

    override fun actionPerformed(e: AnActionEvent) {
        manager.setMaximized(toolWindow, !manager.isMaximized(toolWindow))
    }
}

/** Moves Keymap Viewer into its own window (which can be made fullscreen) and back into the IDE. */
private class OpenInWindowAction(private val toolWindow: ToolWindow) :
    DumbAwareAction("Open in Separate Window", "Show Keymap Viewer in its own window, which can be maximized or made fullscreen",
        AllIcons.Actions.MoveToWindow) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val windowed = toolWindow.type == ToolWindowType.WINDOWED
        e.presentation.text = if (windowed) "Dock Back into IDE" else "Open in Separate Window"
        e.presentation.icon = if (windowed) AllIcons.Actions.MoveToBottomRight else AllIcons.Actions.MoveToWindow
    }

    override fun actionPerformed(e: AnActionEvent) {
        toolWindow.setType(if (toolWindow.type == ToolWindowType.WINDOWED) ToolWindowType.DOCKED else ToolWindowType.WINDOWED, null)
    }
}
