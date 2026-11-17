package io.github.khorkovenko.keymapviewer

import com.intellij.ui.ColoredListCellRenderer
import javax.swing.JList

/** A list/combo-box renderer that shows [text] for each item (replaces the deprecated SimpleListCellRenderer.create). */
fun <T> textRenderer(text: (T) -> String): ColoredListCellRenderer<T> = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value != null) append(text(value))
    }
}
