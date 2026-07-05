// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("sync") {
    group = "ide"
    description = "Dummy sync task to satisfy IDE run configurations"
}
