package io.github.khorkovenko.keymapviewer

import java.time.LocalDate

object MarkdownExporter {

    fun toMarkdown(summary: KeymapSummary): String = buildString {
        appendLine("# Keymap: ${summary.name}")
        appendLine()
        appendLine("| Property | Value |")
        appendLine("|---|---|")
        appendLine("| Designed for | **${summary.targetOs.label}** |")
        appendLine("| Type | ${if (summary.isCustom) "Custom (user-defined)" else "Built-in"} |")
        appendLine("| Inheritance | ${summary.inheritance.joinToString(" → ") { esc(it) }} |")
        appendLine("| Actions with shortcuts | ${summary.assignedCount} |")
        if (summary.includesUnassigned) appendLine("| Actions listed | ${summary.rows.size} (including unassigned) |")
        appendLine("| Exported | ${LocalDate.now()} |")
        appendLine()
        appendLine("## Shortcuts")
        appendLine()
        val blocks = summary.rowsByCategory()
        if (blocks.size > 1) {
            appendLine(blocks.joinToString(" · ") { (cat, rows) -> "[$cat](#${anchor(cat)}) (${rows.size})" })
            appendLine()
        }
        blocks.forEach { (cat, rows) ->
            appendLine("### $cat")
            appendLine()
            appendLine("| Shortcut | Action | Action ID |")
            appendLine("|---|---|---|")
            rows.forEach { r ->
                appendLine("| ${code(r.shortcuts)} | ${esc(r.actionName)} | `${r.actionId}` |")
            }
            appendLine()
        }
    }

    /** GitHub-style heading anchor: "Version Control" → "version-control". */
    private fun anchor(s: String) = s.lowercase().replace(Regex("[^a-z0-9 -]"), "").trim().replace(' ', '-')

    private fun esc(s: String) = s.replace("|", "\\|").replace("\n", " ")

    /** Wrap each alternative shortcut in backticks: `Ctrl+C` or `Ctrl+Insert`; unassigned → — */
    private fun code(s: String): String {
        if (s.isEmpty()) return "—"
        return s.split(ShortcutFormatter.SEPARATOR).joinToString(" or ") { "`${it.replace("`", "'")}`" }
            .replace("|", "\\|")
    }
}
