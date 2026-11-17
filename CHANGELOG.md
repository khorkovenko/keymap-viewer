# Changelog

All notable changes to Keymap Viewer are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.4] - 2026-11-17

### Fixed
- When adding a macro step by hand (in the recording window or the Save Macro dialog) near the bottom of the screen, the suggestion list opened on top of the text field and hid what you typed. It now opens above the field when there is no room below. Shortcut suggestions in the table's Shortcut cell behave the same way.

## [1.0.3] - 2026-11-17

### Added
- **Macros:** a new **Create Macro…** icon in the toolbar.
  1. Choose where the macro goes from a searchable list: **New macro…** or an existing macro (to re-record, edit its steps or delete it), and give it a name.
  2. Press **Record**. The dialog closes and a small recording window appears in the corner of the IDE. It lists, as you go:
     - every action you run (by shortcut, menu or toolbar)
     - the text you type in editors
     - tool windows you open, including by clicking their side-bar buttons

     Actions the IDE runs on its own (without a key press or click from you) are not recorded.

     The recording window has:
     - **Pause / Continue:** nothing is recorded while paused.
     - **Remove:** removes the selected step (or press Delete).
     - A field for **adding steps by hand**, with suggestions as you type: an action name or ID, `type: text` or `window: name`.
     - **Discard:** throws the recording away; it asks first if steps were recorded.
     - **Stop & Review:** opens the Save Macro window.
  3. Press **Stop**. The Save Macro dialog shows the macro choice, the recorded steps and a shortcut field.
     - Steps can be edited (double-click), added (an action from a searchable list, or text to type), removed and reordered.
     - The shortcut field works like the shortcut editor: press keys, a mouse click or a double modifier, and conflicts are checked.
  4. Saved macros become IDE actions named "Macro: <name>". They appear in the table (in a "Macros" block in the Markdown view) and in Find Action, and their shortcuts are stored in the selected keymap.
- **IntelliJ's own macros** (made with Edit | Macros) are listed in the macro chooser, marked "IntelliJ macro".
  - **Copy and Edit…** turns one into a plugin macro you can edit and give a shortcut; the original stays untouched.
  - **Record as New** records a new macro with that name.
  - They are read from the IDE's `options/macros.xml`, which the IDE writes when it saves settings.
- New step type **Press key** (for example Enter in a popup), used by IntelliJ macros. Add one by typing `key: Enter` or `key: Ctrl+S`.
- The plugin has its own macro recorder, because IntelliJ's built-in macro system is internal API. Macros are stored in `keymapViewerMacros.xml` and work while the plugin is installed.

- **Missed shortcuts:** a new **Missed Shortcuts** button at the end of the toolbar.
  - When you run an action with the mouse (menu, toolbar, context menu or a side-bar button) although your active keymap has a keyboard shortcut for it, a red count badge appears on that button and on the Keymap Viewer icon in the side bar.
  - Clicking the button opens the **Missed Shortcuts** dialog: shortcut, action, how many times you missed it and when, with Clear List and Remove Selected.
  - Opening the dialog clears the badge.

- **Edit Shortcut** now asks right away what to do when another action already uses the shortcut you pressed: remove it from the other action(s) and assign it here (default), keep both, or decide for each one after saving (remove, keep or give it a new shortcut).

### Fixed
- Shortcuts that are already assigned in the active keymap (for example Ctrl+C) couldn't be recorded in the Edit Shortcut field: the IDE ran the shortcut instead. The field now receives every key except plain Enter and Esc.

### Changed
- Replaced the last API scheduled for removal (drop-down list renderers). The plugin now passes JetBrains' compatibility check for IntelliJ 2025.1–2026.3 without warnings.

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
