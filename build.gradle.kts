plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// Keep build outputs out of OneDrive so thousands of intermediate files don't get synced.
val localBuildRoot = System.getenv("LOCALAPPDATA")?.let { File(it, "poketrader-build") }
if (localBuildRoot != null) {
    allprojects {
        layout.buildDirectory.set(File(localBuildRoot, project.name))
    }
}
