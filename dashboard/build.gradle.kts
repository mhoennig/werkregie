import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "de.hoennig"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

repositories {
    mavenCentral()
}

val jacksonVersion = "2.21.7"

dependencies {
    // no web framework: the JDK's HttpServer and HttpClient are enough for a localhost dashboard
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("io.kotest:kotest-runner-junit5:6.1.5")
    testImplementation("io.kotest:kotest-assertions-core:6.1.5")
}

application {
    mainClass = "de.hoennig.werkregie.dashboard.MainKt"
    applicationName = "werkregie-dashboard"
    // sqlite-jdbc loads its native library
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.processResources {
    // the footer shows the version, as Werkator's does
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("werkregie.properties") { expand("version" to version) }
}

tasks.test {
    useJUnitPlatform()
}
