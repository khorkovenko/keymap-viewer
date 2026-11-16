plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.khorkovenko.keymapviewer"
version = "1.0.1"

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
