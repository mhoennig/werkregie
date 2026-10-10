// Lets Gradle download Temurin 21 where only another JDK is installed, e.g. in Werkator's build sandbox,
// so that the Adoptium pin in build.gradle.kts holds there too (as in Werkator, PR#62; version 1.0.0 for Gradle 9).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "werkregie-dashboard"
