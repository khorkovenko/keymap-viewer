# Changelog

All notable changes to Keymap Viewer are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.2] - 2026-11-16

### Fixed
- With "Show all actions" off, actions that use another action's shortcut were missing, so fewer actions were listed than with it on (for example 106 instead of 312). Both views and the Markdown export now list the same assigned actions.
- Keymaps without a parent and without an OS in their name (for example "Custom_w11") showed "Any OS (not detected)". They are now recognized from their shortcuts: mostly ⌘ means macOS, mostly Ctrl means Windows / Linux.

## [1.0.1] - 2026-11-16

### Changed
- Keymap actions are now **icon buttons with tooltips**: Make Active, Copy, Delete, New Empty, Export .md, Import, Export and Migrate to OS. They keep the same order in every layout and sit on the same line as the keymap drop-down.
- **Compact layout:**
  - A tool window docked at the bottom (wide) no longer hides the table. Rows never stretch, items are vertically centered, and margins are minimal.
  - In a wide panel the search box and the view checkboxes share one line. In a narrow panel the search box spans the full width with the checkboxes underneath.
- Double-modifier shortcuts (Double Ctrl / Shift / Alt) are now detected with standard key events and run through IntelliJ's public action API, instead of an IDE-internal class.
- Replaced APIs that are deprecated or scheduled for removal in IntelliJ 2026.2+.

### Added
- **Maximize** button in the tool window title bar, to fill the IDE window and restore it.
- **Open in Separate Window** button in the title bar. The window can be maximized, made fullscreen or moved to another monitor, and docked back with the same button.

### Fixed
- Keymaps whose parent is only recognizable by its display name (for example Sublime Text → Windows) showed "Any OS (not detected)". They now show the correct OS.

## [1.0.0] - 2026-11-16

First release.

- Browse all keymaps (built-in and custom), see which OS each is designed for and what it inherits from, and search actions and shortcuts.
- Edit shortcuts:
  - press the keys in a dialog (translated Ctrl ↔ ⌘ to the keymap's OS), or type them into the Shortcut cell with key-name suggestions
  - mouse shortcuts, two-step shortcuts (Ctrl+K, Ctrl+K) and double modifiers (Double Shift)
- Conflict resolution: remove the shortcut from the other actions, review them one by one, keep both, or undo.
- Copy, create, delete keymaps, and migrate a keymap to another OS.
- Markdown view (grouped by menu) and export, plus keymap XML import and export.
