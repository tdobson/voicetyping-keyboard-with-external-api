// AGP 9 has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android.
// AGP bundles an older KGP; putting a newer KGP on the buildscript classpath pins the Kotlin version.
buildscript {
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
