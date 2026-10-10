import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Werkbaum #dash.matrix.sdk: does Trixnity run on the dashboard's JVM, with vodozemac rather than libolm?
// Run from dashboard/: ../gradlew -p spikes/matrix-sdk run --args="[SERVER_NAME]"
plugins {
    // the dashboard's Kotlin version: Trixnity is built with a newer one
    kotlin("jvm") version "2.2.21"
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
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

val trixnityVersion = "5.9.2"

dependencies {
    implementation("de.connect2x.trixnity:trixnity-client:$trixnityVersion")
    implementation("de.connect2x.trixnity:trixnity-client-cryptodriver-vodozemac:$trixnityVersion")
    // the bindings themselves, for the round trips; a client would only need the driver
    implementation("de.connect2x.trixnity:trixnity-vodozemac:$trixnityVersion")
    // Trixnity's Ktor version; the Java engine uses the JDK's HttpClient, as the dashboard does
    implementation("io.ktor:ktor-client-java:3.5.2")
}

application {
    mainClass = "SpikeKt"
    // vodozemac is loaded through JNI
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// the jars a client would add to the dashboard's bundle
tasks.register("classpathSize") {
    val jars = configurations.runtimeClasspath
    doLast {
        val files = jars.get().files
        println("runtime classpath: ${files.size} jars, ${files.sumOf { it.length() } / 1024 / 1024} MB")
        files.sortedByDescending { it.length() }.take(8).forEach { println("  ${it.length() / 1024} KB ${it.name}") }
    }
}

// the JDK modules those jars need, for the bundle's jlink --add-modules
tasks.register<Exec>("moduleDeps") {
    dependsOn(tasks.jar)
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val jars = configurations.runtimeClasspath
    val own = tasks.jar.flatMap { it.archiveFile }
    doFirst {
        val jdeps = launcher.get().metadata.installationPath.file("bin/jdeps").asFile
        val classpath = jars.get().files.joinToString(":")
        commandLine(jdeps, "--ignore-missing-deps", "--multi-release", "21", "--print-module-deps", "--class-path", classpath, own.get().asFile, *jars.get().files.toTypedArray())
    }
}
