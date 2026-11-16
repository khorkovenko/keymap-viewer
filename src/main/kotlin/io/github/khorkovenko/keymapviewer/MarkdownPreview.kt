package io.github.khorkovenko.keymapviewer

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.time.LocalDate

/**
 * Renders the same content as [MarkdownExporter] the way a Markdown viewer would show it (headings, tables,
 * code spans), as HTML for a Swing JEditorPane. Search matches are highlighted.
 */
object MarkdownPreview {

    /** Anchor of the first search match, for scrolling to it. */
    const val FIRST_MATCH = "firstMatch"

    fun toHtml(summary: KeymapSummary, query: String, totalRows: Int, note: String? = null): String {
        val fg = hex(UIUtil.getLabelForeground())
        val muted = hex(UIUtil.getContextHelpForeground())
        val border = hex(JBColor.border())
        val headerBg = hex(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.08))
        val codeBg = hex(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.12))
        val hit = hex(
            EditorColorsManager.getInstance().globalScheme
                .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor ?: JBColor.YELLOW
        )
        val font = UIUtil.getLabelFont()
        val mono = EditorColorsManager.getInstance().globalScheme.editorFontName

        var matchIndex = 0
        /** Escapes [raw] for HTML and wraps every occurrence of the query in a highlight span. */
        fun hl(raw: String): String {
            if (query.isEmpty()) return esc(raw)
            val out = StringBuilder()
            var i = 0
            while (true) {
                val at = raw.indexOf(query, i, ignoreCase = true)
                if (at < 0) { out.append(esc(raw.substring(i))); break }
                out.append(esc(raw.substring(i, at)))
                val anchor = if (matchIndex++ == 0) "<a name='$FIRST_MATCH'></a>" else ""
                out.append("$anchor<span style='background-color:$hit'>").append(esc(raw.substring(at, at + query.length))).append("</span>")
                i = at + query.length
            }
            return out.toString()
        }

        fun code(text: String) = "<code style='font-family:\"$mono\"; background-color:$codeBg'>&nbsp;${hl(text)}&nbsp;</code>"
        fun shortcuts(s: String) =
            if (s.isEmpty()) "<span style='color:$muted'>—</span>"
            else s.split(ShortcutFormatter.SEPARATOR).joinToString(" <span style='color:$muted'>or</span> ") { code(it) }

        val line = "border-bottom-width:1px; border-bottom-style:solid; border-bottom-color:$border"
        val th = "style='background-color:$headerBg; text-align:left; padding:6px; border-bottom-width:2px; border-bottom-style:solid; border-bottom-color:$border'"
        val td = "style='padding:6px; vertical-align:middle; $line'"
        return buildString {
            append("<html><body style='font-family:\"${font.family}\"; font-size:${font.size}pt; color:$fg; margin:8px'>")
            if (query.isNotEmpty()) {
                append("<p style='color:$muted'>Search “${esc(query)}”: ${summary.rows.size} of $totalRows rows</p>")
            }
            note?.let { append("<p style='color:$muted'><i>${esc(it)}</i></p>") }
            append("<h1 style='font-size:${font.size + 8}pt; margin-bottom:6px'>Keymap: ${esc(summary.name)}</h1>")

            append("<table border='0' cellspacing='0' cellpadding='0' width='100%'>")
            append("<tr><th $th>Property</th><th $th>Value</th></tr>")
            fun prop(k: String, v: String) = append("<tr><td $td>$k</td><td $td>$v</td></tr>")
            prop("Designed for", "<b>${esc(summary.targetOs.label)}</b>")
            prop("Type", if (summary.isCustom) "Custom (user-defined)" else "Built-in")
            prop("Inheritance", summary.inheritance.joinToString(" → ") { esc(it) })
            prop("Actions with shortcuts", summary.assignedCount.toString())
            if (summary.includesUnassigned) prop("Actions listed", "${summary.rows.size} (including unassigned)")
            prop("Exported", LocalDate.now().toString())
            append("</table>")

            append("<h2 style='font-size:${font.size + 4}pt; margin-top:14px; margin-bottom:6px'>Shortcuts</h2>")
            if (summary.rows.isEmpty()) {
                append("<p style='color:$muted'>Nothing to show.</p>")
            } else {
                val blocks = summary.rowsByCategory()
                if (blocks.size > 1) {
                    append("<p>")
                    append(blocks.joinToString(" &nbsp;·&nbsp; ") { (cat, rows) ->
                        "<a href='#${blockAnchor(cat)}'>${esc(cat)}</a> <span style='color:$muted'>(${rows.size})</span>"
                    })
                    append("</p>")
                }
                blocks.forEach { (cat, rows) ->
                    append("<h3 style='font-size:${font.size + 2}pt; margin-top:12px; margin-bottom:4px'>")
                    append("<a name='${blockAnchor(cat)}'></a>${esc(cat)} <span style='color:$muted; font-weight:normal'>(${rows.size})</span></h3>")
                    append("<table border='0' cellspacing='0' cellpadding='0' width='100%'>")
                    append("<tr><th $th>Shortcut</th><th $th>Action</th><th $th>Action ID</th></tr>")
                    rows.forEach { r ->
                        append("<tr><td $td>${shortcuts(r.shortcuts)}</td><td $td>${hl(r.actionName)}</td><td $td>${code(r.actionId)}</td></tr>")
                    }
                    append("</table>")
                }
            }
            append("</body></html>")
        }
    }

    private fun blockAnchor(category: String) = "block-" + category.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    private fun hex(c: java.awt.Color) = "#" + ColorUtil.toHex(c)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
