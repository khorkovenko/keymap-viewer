plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.khorkovenko.keymapviewer"
version = "1.0.0"

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
        changeNotes = """
            <p>First release:</p>
            <ul>
              <li>Browse all keymaps, see which OS each is for, search actions</li>
              <li>Edit shortcuts by pressing keys or typing them (with key-name suggestions); mouse, two-step and double-modifier shortcuts</li>
              <li>Conflict resolution, copy / new / delete keymaps, migrate a keymap to another OS</li>
              <li>Markdown view and export, keymap XML import and export</li>
            </ul>
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
