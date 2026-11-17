package io.github.khorkovenko.keymapviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.CollectionListModel
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Records what you do in the IDE: every action you run (by shortcut, menu or toolbar), the text you type in
 * editors (consecutive characters become one "Type …" step) and tool windows you open.
 * Recording can be paused; steps can also be added or removed by hand while recording.
 */
object MacroRecorder {
    var isRecording = false
        private set
    var isPaused = false
        private set

    private val steps = ArrayList<MacroStep>()
    private var connection: MessageBusConnection? = null
    private var projectConnection: MessageBusConnection? = null
    private var onChange: (List<MacroStep>) -> Unit = {}

    /** The recording window: nothing done inside it (typing a step, clicking its buttons) is recorded. */
    var ignoreInside: Component? = null

    /** Actions that only move focus between windows; not worth replaying. */
    private val ignored = setOf("EditorEscape", "FocusEditor", "HideActiveWindow", "HideAllWindows")

    private fun shouldSkip(): Boolean {
        if (isPaused || MacroPlayer.isPlaying) return true
        val inside = ignoreInside ?: return false
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(focus, inside)
    }

    /** @param onAbort called when the recording can't go on (its project is closing); the caller should stop it. */
    fun start(project: Project, onAbort: () -> Unit = {}, onChange: (List<MacroStep>) -> Unit) {
        if (isRecording) return
        steps.clear()
        this.onChange = onChange
        isRecording = true
        isPaused = false
        connection = ApplicationManager.getApplication().messageBus.connect().apply {
            subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    if (shouldSkip()) return
                    // Actions without a key press or click behind them are run by the IDE itself
                    // (e.g. the Database window running "Data Source Properties" while it opens): not yours
                    if (event.inputEvent == null) return
                    val id = ActionManager.getInstance().getId(action) ?: return // our own buttons have no ID
                    if (id in ignored) return
                    steps += MacroStep.Action(id)
                    notifyChanged()
                }

                override fun beforeEditorTyping(c: Char, dataContext: DataContext) {
                    if (shouldSkip()) return
                    val last = steps.lastOrNull()
                    if (last is MacroStep.Typing) steps[steps.size - 1] = MacroStep.Typing(last.text + c)
                    else steps += MacroStep.Typing(c.toString())
                    notifyChanged()
                }
            })
            // Otherwise the recorder would stay "recording" (and Create Macro disabled) after the project is gone
            subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosing(closing: Project) { if (closing === project) onAbort() }
            })
        }
        // Opening a tool window by clicking its side-bar button is not an action, so listen for it separately
        projectConnection = project.messageBus.connect().apply {
            subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (shouldSkip()) return
                    // Already recorded when it was opened with its shortcut (Activate...ToolWindow action)
                    val activateId = "Activate" + toolWindow.id.replace(" ", "") + "ToolWindow"
                    val last = steps.lastOrNull()
                    if (last is MacroStep.Action && last.actionId == activateId) return
                    if (last is MacroStep.ToolWindow && last.id == toolWindow.id) return
                    steps += MacroStep.ToolWindow(toolWindow.id)
                    notifyChanged()
                }
            })
        }
    }

    fun pause() { isPaused = true; notifyChanged() }
    fun resume() { isPaused = false; notifyChanged() }

    /** Adds a step typed by hand. */
    fun add(step: MacroStep) { steps += step; notifyChanged() }

    fun removeAt(index: Int) {
        if (index in steps.indices) { steps.removeAt(index); notifyChanged() }
    }

    val count: Int get() = steps.size

    private fun notifyChanged() {
        val snapshot = steps.toList()
        ApplicationManager.getApplication().invokeLater { onChange(snapshot) }
    }

    /** Stops recording and returns the recorded steps. */
    fun stop(): List<MacroStep> {
        connection?.disconnect()
        connection = null
        projectConnection?.disconnect()
        projectConnection = null
        isRecording = false
        isPaused = false
        ignoreInside = null
        return steps.toList()
    }
}

/**
 * The small window shown in the corner while recording:
 *  • the steps recorded so far (newest at the bottom); select one and press Remove (or Delete) to drop it
 *  • a field to add a step by hand (action name, "type: …", "window: …") with suggestions
 *  • Pause / Continue, Discard (throw the recording away) and Stop & Review (open the Save Macro window)
 * It doesn't take the focus when it appears, so your keys keep going to the editor.
 */
class MacroRecordingWindow(
    private val project: Project,
    private val macroName: String,
    private val onStop: (List<MacroStep>) -> Unit,
) {
    private val model = CollectionListModel<String>()
    private val list = JBList(model).apply {
        emptyText.text = "Run actions, type in an editor or open tool windows…"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 7
    }
    private val header = JBLabel()
    private val counter = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val stepInput = MacroStepInput(project) { MacroRecorder.add(it) }
    private var popup: JBPopup? = null

    private val removeButton = JButton("Remove", AllIcons.General.Remove).apply {
        toolTipText = "Remove the selected step (or press Delete in the list)"
        isEnabled = false
        addActionListener { removeSelected() }
    }
    private val pauseButton = JButton("Pause", AllIcons.Actions.Pause).apply {
        toolTipText = "Pause recording; what you do while paused is not recorded"
        addActionListener { togglePause() }
    }
    private val discardButton = JButton("Discard").apply {
        toolTipText = "Stop and throw the recording away"
        addActionListener { discard() }
    }
    private val stopButton = JButton("Stop & Review", AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop recording and open the Save Macro window to edit the steps and choose a shortcut"
        addActionListener { finish(keep = true) }
    }

    fun show() {
        list.addListSelectionListener { removeButton.isEnabled = list.selectedIndex >= 0 }
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) { removeSelected(); e.consume() }
            }
        })

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(removeButton); add(pauseButton); add(discardButton); add(stopButton)
        }
        val content = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(header, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(JBScrollPane(list).apply { preferredSize = JBUI.size(460, 150) }, BorderLayout.CENTER)
                add(stepInput.field, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(counter, BorderLayout.WEST)
                add(buttons, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        MacroRecorder.ignoreInside = content
        updateHeader()

        // Focusable (so the step field can be typed in) but it doesn't grab the focus when it appears
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(false)
            .setBelongsToGlobalPopupStack(false)
            .createPopup()

        MacroRecorder.start(project, onAbort = { finish(keep = false) }) { steps ->
            val keepSelection = list.selectedIndex
            model.replaceAll(steps.map { it.describe() })
            if (keepSelection in 0 until model.size) list.selectedIndex = keepSelection
            else if (model.size > 0) list.ensureIndexIsVisible(model.size - 1)
            updateHeader()
        }
        MacroRecorder.ignoreInside = content

        // Bottom-right corner of the IDE window
        val frame = WindowManager.getInstance().getFrame(project)?.rootPane
        if (frame != null) {
            val size = content.preferredSize
            popup!!.show(RelativePoint(frame, Point(frame.width - size.width - JBUI.scale(24), frame.height - size.height - JBUI.scale(56))))
        } else {
            popup!!.showInFocusCenter()
        }
    }

    private fun updateHeader() {
        val paused = MacroRecorder.isPaused
        header.icon = if (paused) AllIcons.Actions.Pause else AllIcons.Ide.Macro.Recording_1
        header.text = if (paused) "Paused: “$macroName” (nothing is recorded)" else "Recording “$macroName”"
        pauseButton.text = if (paused) "Continue" else "Pause"
        pauseButton.icon = if (paused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
        pauseButton.toolTipText = if (paused) "Continue recording" else "Pause recording; what you do while paused is not recorded"
        counter.text = "${MacroRecorder.count} step(s)" + if (paused) " · paused" else ""
    }

    private fun togglePause() {
        if (MacroRecorder.isPaused) MacroRecorder.resume() else MacroRecorder.pause()
        updateHeader()
    }

    private fun removeSelected() {
        val i = list.selectedIndex.takeIf { it >= 0 } ?: return
        MacroRecorder.removeAt(i)
    }

    private fun discard() {
        if (MacroRecorder.count > 0 && Messages.showYesNoDialog(project,
                "Throw away the ${MacroRecorder.count} recorded step(s) of “$macroName”?", "Discard Recording",
                "Discard", "Keep Recording", Messages.getWarningIcon()) != Messages.YES) return
        finish(keep = false)
    }

    private fun finish(keep: Boolean) {
        stepInput.hidePopup()
        val steps = MacroRecorder.stop()
        popup?.cancel()
        popup = null
        if (keep) onStop(steps)
    }
}
