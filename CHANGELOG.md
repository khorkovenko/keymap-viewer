# Changelog

All notable changes to Keymap Viewer are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.6] - 2026-11-21

### Added
- **Search by keys:** a third checkbox next to the search field. Tick it and the search field records the key combination you press (Ctrl+Shift+F, Double Shift, Ctrl+Click…) instead of text, and the table lists the actions that use it. A single combination also finds two-step shortcuts that start with it. Double taps match both kinds a keymap can hold: this plugin's double modifiers and IntelliJ's own double-tap gestures. While it's on, "Show all actions" is on and "Show as Markdown" is off (both locked); unticking it restores them.

### Fixed
- Double-tapping Shift or Ctrl in a shortcut field inside the tool window opened Search Everywhere or Run Anything instead of being recorded.
- IntelliJ's own double-tap shortcuts (for example Find in Files on double Alt) were shown as "Alt Alt"; they now read "Double Alt" like the plugin's own.
- The checkboxes under the search field were cut off in a narrow tool window; they now wrap onto more lines, also right after the tool window opens.

## [1.0.5] - 2026-11-17

### Changed
- **Export** now asks for a folder and saves the selected keymap plus a `keymap-viewer-macros.xml` with all your macros. The keymap file also carries its double-modifier shortcuts (Double Ctrl…); IntelliJ ignores that part, so it is still a normal keymap file.
- **Import** accepts several files and folders at once. Each file is recognized by its content and added accordingly: keymap files become keymaps (with their double modifiers), macro files add their macros, and IntelliJ's own `macros.xml` adds its macros as plugin macros. Choosing an export folder brings back the keymap and all macros. If a macro with the same name but different steps already exists, you're asked whether to replace it.

### Fixed
- **OS detection:** keymaps whose name merely contains "mac" (for example "Emacs") were shown as macOS keymaps, and a keymap migrated from macOS to Windows could still show macOS. The built-in keymap a keymap is based on now decides first, then its shortcuts, and only then its name.
- **Missed shortcuts:** actions run with a mouse shortcut (for example Ctrl+Click for Go to Declaration) were counted as missed shortcuts.
- **Double modifiers:** Ctrl+Click twice in a row (or Shift/Alt) could trigger the action assigned to Double Ctrl. A mouse click between the two taps now cancels the double tap, also in the Edit Shortcut field.
- **Typing shortcuts in the table:** a shortcut containing `;` or `|` as its key (for example `Ctrl+;`) was split in two and couldn't be saved; `Ctrl+_` was read as `Ctrl+-`.
- **Macros:**
  - If the project was closed while recording, Create Macro stayed disabled until the IDE was restarted.
  - Pressing ↑/↓ in the action chooser when the search matched nothing caused an error.
  - Renaming or deleting a macro left its double-modifier shortcut behind; deleting one added an empty entry to every custom keymap.
  - Registering macros when several projects open at once could fail.
- The table now updates when shortcuts are changed in Settings | Keymap while the tool window is open, and keeps the selected row.
- Export (.md, keymap and macros) shows a message if a file can't be written instead of an error report.
- Keymap names containing `<` or `&` no longer break the info line.

### Compatibility
- Export and Migrate to OS no longer use an IDE implementation class (`KeymapImpl`) to find the shortcuts a keymap changes; they use the public keymap API, which makes the plugin safer for future IDE versions.
- Checked with JetBrains' Plugin Verifier against IntelliJ IDEA 2025.1–2026.3: compatible, no deprecated, experimental or internal API usage.

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
