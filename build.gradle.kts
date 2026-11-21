plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.khorkovenko.keymapviewer"
version = "1.0.6"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // The IDE the plugin is compiled against and launched with by `runIde`
        intellijIdea("2025.3")
    }
}

kotlin {
    jvmToolchain(21)
    // Don't generate Kotlin bridges for Java default methods (otherwise the verifier reports
    // overrides of deprecated/experimental ToolWindowFactory methods we never wrote)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"          // 2025.1+
            untilBuild = provider { null } // no upper bound
        }
        // Shown on the Marketplace page and in the IDE's update dialog (full history: CHANGELOG.md)
        changeNotes = """
            <p><b>1.0.6</b></p>
            <ul>
              <li>New: <b>Search by keys</b>. Tick it and press a key combination in the search field to list the actions
                  that use it (double taps match IntelliJ's own double-tap gestures too).
                  While it's on, all actions are shown as a table.</li>
              <li>Fixed: double-tapping Shift or Ctrl in a shortcut field inside the tool window opened Search Everywhere / Run Anything.</li>
              <li>IntelliJ's double-tap shortcuts read "Double Alt" instead of "Alt Alt".</li>
              <li>The checkboxes under the search field wrap onto more lines in a narrow tool window.</li>
            </ul>
            <p><b>1.0.5</b></p>
            <ul>
              <li>Export saves the selected keymap and all macros into a folder (double-modifier shortcuts included).</li>
              <li>Import accepts keymap files, macro files (also IntelliJ's macros.xml) or a whole folder; each file is
                  recognized automatically, so importing an export folder brings back the keymap and its macros.</li>
              <li>Fixed: keymaps such as "Emacs" were shown as macOS keymaps; OS detection now trusts the built-in keymap
                  a keymap is based on before its name.</li>
              <li>Fixed: mouse shortcuts (Ctrl+Click) were counted as missed shortcuts; Ctrl+Click twice could trigger Double Ctrl.</li>
              <li>Fixed: typing a shortcut with ";" or "|" as its key (Ctrl+;) in the table.</li>
              <li>Fixed: Create Macro stayed disabled after closing a project while recording; an error in the action chooser.</li>
              <li>The table updates when shortcuts change in Settings | Keymap.</li>
              <li>Better compatibility with future IDE versions: no IDE implementation classes for export and migration.</li>
            </ul>
            <p><b>1.0.4</b></p>
            <ul>
              <li>Fixed: when adding a macro step by hand near the bottom of the screen, the suggestion list covered the text
                  field. It now opens above the field when there is no room below. The same applies to shortcut suggestions in the table.</li>
            </ul>
            <p><b>1.0.3</b></p>
            <ul>
              <li>New: <b>macros</b>. Click the macro icon, pick a new or existing macro, press Record and use the IDE: a small
                  window lists every recorded action and typed text. Press Stop to review the steps (edit, reorder, add, remove),
                  then save the macro with a shortcut. Macros appear as "Macro: name" actions in the table and in Find Action.</li>
              <li>New: <b>missed shortcuts</b>. When you use the mouse for an action that has a shortcut, a red count badge appears
                  on the Keymap Viewer side-bar icon and on the Missed Shortcuts toolbar button, which lists what you missed.</li>
              <li>New: IntelliJ's own macros are listed in the macro chooser and can be copied into an editable plugin macro.</li>
              <li>New: Edit Shortcut asks right away what to do when another action already uses the shortcut: remove it from
                  the other action(s), keep both, or decide one by one.</li>
              <li>Fixed: shortcuts already assigned in the active keymap (for example Ctrl+C) can now be recorded in Edit Shortcut.</li>
            </ul>
            <p><b>1.0.2</b></p>
            <ul>
              <li>Fixed: with "Show all actions" off, actions that share another action's shortcut were missing, so the assigned count was too low</li>
              <li>Fixed: standalone keymaps without an OS in their name (e.g. "Custom_w11") are now recognized from their shortcuts</li>
            </ul>
            <p><b>1.0.1</b></p>
            <ul>
              <li>Icon buttons with tooltips for all keymap actions, on the same line as the keymap drop-down</li>
              <li>Compact layout: the table is always visible when the tool window is docked at the bottom; search and view options adapt to wide and narrow panels</li>
              <li>New title-bar buttons: Maximize, and Open in Separate Window (can be made fullscreen)</li>
              <li>Double modifiers (Double Ctrl / Shift / Alt) no longer use IDE-internal APIs</li>
              <li>Fixed: OS detection for keymaps such as Sublime Text</li>
            </ul>
            <p><b>1.0.0</b>: first release. Browse, search, edit, migrate and export keymaps.</p>
        """.trimIndent()
    }

    // Optional but recommended: sign the plugin (see README)
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Token from https://plugins.jetbrains.com/author/me/tokens
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides { recommended() }
    }
}
