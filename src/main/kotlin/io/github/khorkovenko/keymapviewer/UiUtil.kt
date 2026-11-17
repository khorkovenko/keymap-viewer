package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScreenUtil
import com.intellij.util.ui.JBUI
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JList

/** A list/combo-box renderer that shows [text] for each item (replaces the deprecated SimpleListCellRenderer.create). */
fun <T> textRenderer(text: (T) -> String): ColoredListCellRenderer<T> = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value != null) append(text(value))
    }
}

/**
 * Shows [popup] (or moves it, if already showing) right under [anchor], or right above it when there isn't room
 * below on the screen. The IDE would otherwise push it up over [anchor] and hide the text being typed.
 */
fun placeNextTo(popup: JBPopup, anchor: JComponent) {
    if (!anchor.isShowing) return
    val height = if (popup.isVisible) popup.size.height else popup.content.preferredSize.height + JBUI.scale(4)
    val origin = anchor.locationOnScreen
    val screen = ScreenUtil.getScreenRectangle(origin)
    val spaceBelow = screen.y + screen.height - (origin.y + anchor.height)
    val spaceAbove = origin.y - screen.y
    val point = if (height <= spaceBelow || spaceBelow >= spaceAbove) Point(origin.x, origin.y + anchor.height)
    else Point(origin.x, origin.y - height)
    if (popup.isVisible) popup.setLocation(point) else popup.showInScreenCoordinates(anchor, point)
}
