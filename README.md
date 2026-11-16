# Keymap Viewer

An IntelliJ Platform plugin for browsing, editing and moving keymaps between operating systems. It works in IntelliJ IDEA, PyCharm, WebStorm and the other JetBrains IDEs, version 2025.1 or later.

## Features

- **Browse any keymap**, built-in or your own. See which OS it is designed for (macOS, Windows / Linux, Linux) and what it inherits from.
- **Search** actions, action IDs and shortcuts. Show only assigned actions, or every action so all keymaps list the same rows.
- **Edit shortcuts:**
  - Double-click a row and press the keys. They are translated to the keymap's OS (Ctrl ↔ ⌘).
  - Or click the Shortcut cell and type a shortcut, with suggestions for every key name.
  - Supported shortcut types:
    - mouse shortcuts: Ctrl+Click, Ctrl+Double Click, Alt+Right Click, Ctrl+Wheel Up
    - two-step shortcuts: Ctrl+K, Ctrl+K
    - double modifiers: Double Shift, Double Ctrl
- **Resolve conflicts:** remove the shortcut from the other actions, review them one by one (reassign / remove / keep), keep both, or undo.
- **Manage keymaps:**
  - copy a keymap, create an empty one, or delete one
  - migrate a keymap to another OS in one step
- **Markdown:**
  - view the keymap as rendered Markdown, grouped by menu (Edit, View, Navigate…)
  - export the assigned shortcuts to a `.md` file
- **Keymap files:** import and export keymaps in IntelliJ's own `.xml` format.

Open it from **View | Tool Windows | Keymap Viewer**.

> Double-modifier shortcuts (Double Ctrl, Double Shift…) can't be stored in IntelliJ keymap files, so the plugin keeps them itself. They work while the plugin is installed and aren't included in exported `.xml` files.

## Build and run

Requirements: nothing to install beforehand. The Gradle wrapper downloads Gradle, and JDK 21 is downloaded automatically.

```
./gradlew runIde        # starts a sandbox IDE with the plugin installed
./gradlew buildPlugin   # builds the installable zip into build/distributions/
./gradlew verifyPlugin  # checks compatibility with recent IDE versions
```

To install a build in your own IDE: Settings | Plugins | ⚙ | Install Plugin from Disk…, then pick the zip.

## Project layout

```
build.gradle.kts                      IntelliJ Platform Gradle Plugin config (target IDE, signing, publishing)
src/main/resources/META-INF/plugin.xml   plugin ID, description, tool window, startup activity
src/main/kotlin/io/github/khorkovenko/keymapviewer/
    KeymapPanel.kt              the tool window UI
    KeymapData.kt               reads keymaps, detects the OS, builds the table rows
    ActionCategories.kt         groups actions by main menu for the Markdown view
    ShortcutFormatter.kt        shortcut text in macOS / Windows / Linux notation, Ctrl ↔ ⌘ translation
    ShortcutText.kt             parses typed shortcuts ("Ctrl+K, Ctrl+K", "Double Shift", "Ctrl+Click")
    ShortcutCellEditor.kt       type-in Shortcut cell with key-name suggestions
    ShortcutCaptureField.kt     records pressed keys, double taps and mouse clicks
    ShortcutInput.kt / ShortcutEditDialog.kt   the "press the keys" dialog
    ConflictResolver.kt / KeymapEditSession.kt  conflict dialogs and undo
    KeymapEditing.kt            copy / new / delete keymaps, XML import and export
    OsMigration.kt              migrate a keymap to another OS
    DoubleModifiers.kt          stores and runs double-modifier shortcuts
    MarkdownExporter.kt / MarkdownPreview.kt   Markdown export and rendered view
```

## Publishing (for maintainers)

1. Set `version` and `changeNotes` in `build.gradle.kts`.
2. Run `./gradlew verifyPlugin`, then `./gradlew buildPlugin`.
3. **First release:** upload `build/distributions/keymap-viewer-<version>.zip` at https://plugins.jetbrains.com/plugin/add. JetBrains reviews new plugins, usually within a few working days.
4. **Later releases:** create a token at https://plugins.jetbrains.com/author/me/tokens, set it as `PUBLISH_TOKEN`, then run `./gradlew publishPlugin`.

Signing is optional but recommended:

```
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Put the file contents in the environment variables `CERTIFICATE_CHAIN` (chain.crt), `PRIVATE_KEY` (private.pem) and `PRIVATE_KEY_PASSWORD`, then run `./gradlew signPlugin`. Never commit these files; `.gitignore` already excludes them.

## License

[MIT](LICENSE)
