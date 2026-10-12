import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "de.hoennig"
version = "0.12.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        // jlink cuts the bundle's runtime out of this JDK, native libraries included: Temurin is
        // built against an old glibc and starts on Hostsharing's hosts (Werkator, PR#62)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

repositories {
    mavenCentral()
}

val jacksonVersion = "2.22.3"
val trixnityVersion = "5.9.2"

dependencies {
    // no web framework: the JDK's HttpServer and HttpClient are enough for a localhost dashboard
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    // Matrix with the user's own account, end-to-end encrypted rooms included (werkregie PR#8)
    implementation("de.connect2x.trixnity:trixnity-client:$trixnityVersion")
    implementation("de.connect2x.trixnity:trixnity-client-cryptodriver-vodozemac:$trixnityVersion")
    implementation("de.connect2x.trixnity:trixnity-client-repository-exposed:$trixnityVersion")
    // the client's store: Trixnity's Exposed repositories need R2DBC, which SQLite does not offer
    implementation("io.r2dbc:r2dbc-h2:1.1.0.RELEASE")
    // Trixnity's Ktor version; the Java engine uses the JDK's HttpClient, as the other sources do
    implementation("io.ktor:ktor-client-java:3.5.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.20")

    testImplementation("io.kotest:kotest-runner-junit5:6.2.5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.5")
}

application {
    mainClass = "de.hoennig.werkregie.dashboard.MainKt"
    applicationName = "werkregie-dashboard"
    // sqlite-jdbc and vodozemac load their native libraries
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.processResources {
    // the footer shows the version, as Werkator's does
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("werkregie.properties") { expand("version" to version) }
}

// ---- the runtime bundle for a host without Java: own JRE + jars + launcher (as Werkator's, ADR 0006) ----

val bundleRuntime = tasks.register<Exec>("bundleRuntime") {
    description = "Cuts a minimal JRE for the runtime bundle with jlink."
    val output = layout.buildDirectory.dir("bundle-runtime")
    val jdk = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath }
    outputs.dir(output)
    doFirst {
        delete(output)
        executable = jdk.get().file("bin/jlink").asFile.absolutePath
    }
    args(
        // jdeps: Jackson needs java.desktop (java.beans); jdk.crypto.ec for TLS to Werkator and Gitea;
        // Trixnity, Ktor and coroutines need java.logging, java.management, java.instrument and jdk.unsupported
        "--add-modules", "java.base,java.desktop,java.net.http,java.sql,jdk.httpserver,jdk.crypto.ec," +
            "java.logging,java.management,java.instrument,jdk.unsupported",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", output.get().asFile.absolutePath,
    )
}

val runtimeBundle = tasks.register<Tar>("runtimeBundle") {
    description = "Packs werkregie-dashboard-runtime-linux-x64.tar.gz: jre/, lib/, bin/."
    group = "distribution"
    archiveFileName = "werkregie-dashboard-runtime-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    compression = Compression.GZIP
    // reproducible archives normalize file modes since Gradle 9; jlink's executables must keep theirs
    useFileSystemPermissions()
    into("werkregie-dashboard") {
        into("jre") { from(bundleRuntime) }
        into("lib") { from(tasks.jar, configurations.runtimeClasspath) }
        into("bin") {
            from("src/bundle/bin")
            filePermissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
