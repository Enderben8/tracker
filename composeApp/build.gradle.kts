import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvm("desktop")

    android {
        namespace = "revision.composeapp"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "revision.app.MainKt"
        // The SQLite driver loads a native library; newer Java versions require this opt-in.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            // A Windows installer with its own Java inside, so users don't need Java installed.
            // Build it with:  gradlew :composeApp:packageMsi
            targetFormats(TargetFormat.Msi)
            packageName = "Revision Tracker"
            packageVersion = providers.gradleProperty("app.version").get()
            description = "Log GCSE revision sessions and see what to revise next"
            vendor = "Enderben8"
            licenseFile.set(rootProject.file("LICENSE"))

            // Java modules the bundled runtime must keep (from :composeApp:suggestRuntimeModules).
            // Leaving one out only shows up as a crash on a machine without Java installed.
            modules("java.instrument", "java.sql", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/app.ico"))
                menu = true
                menuGroup = "Revision Tracker"
                shortcut = true
                // Installs for the current user only, so it needs no administrator password.
                perUserInstall = true
                // Must never change: it's how Windows knows a new version replaces the old one.
                upgradeUuid = "3c1f6a52-8d0e-4f7b-9a41-6e2b5d9c7f18"
            }
        }
    }
}
