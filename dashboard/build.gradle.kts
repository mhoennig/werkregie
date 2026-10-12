import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "de.hoennig"
version = "0.31.0"

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

// ---- the footer links the repository the build comes from ----

/**
 * The web page of a git remote: `git@host:owner/repo.git` or `https://user:token@host/owner/repo.git`
 * become `https://host/owner/repo`; null for a local path or a host alias without a domain.
 * Only these characters reach the page, so a remote can carry neither a token nor HTML into it.
 */
fun repositoryWebUrl(remote: String): String? {
    val scp = Regex("""[\w.-]+@([\w-]+(?:\.[\w-]+)+):(?!/)(.+)""").matchEntire(remote.trim())
    val uri: URI? = runCatching { URI(remote.trim()) }.getOrNull()
    val (host, path) = when {
        scp != null -> scp.groupValues[1] to scp.groupValues[2]
        uri != null && uri.scheme in setOf("https", "http", "ssh") && uri.host != null -> uri.host to uri.path
        else -> return null
    }
    val url = "https://$host/" + path.trim('/').removeSuffix(".git")
    return url.takeIf { Regex("""https://[\w-]+(\.[\w-]+)+(/[\w.~-]+)+""").matches(it) }
}

val repositoryUrl: String = runCatching {
    providers.exec {
        commandLine("git", "remote", "get-url", "origin")
        isIgnoreExitValue = true
    }.standardOutput.asText.get()
}.getOrNull()?.let(::repositoryWebUrl) ?: "https://git.javagil.de/mi/werkregie"

val checkRepositoryWebUrl = tasks.register("checkRepositoryWebUrl") {
    description = "Checks how the build turns a git remote into the repository link of the footer."
    val wrong = mapOf(
        "mih09-git@git.javagil.de:mi/werkregie.git" to "https://git.javagil.de/mi/werkregie",
        "ssh://git@git.javagil.de:2222/mi/werkregie.git" to "https://git.javagil.de/mi/werkregie",
        "https://claude-code:secret@git.javagil.de/mi/werkregie.git" to "https://git.javagil.de/mi/werkregie",
        "https://github.com/mhoennig/werkregie/" to "https://github.com/mhoennig/werkregie",
        "gitea:mi/werkregie.git" to null,
        "/srv/git/werkregie.git" to null,
        "file:///srv/git/werkregie.git" to null,
        "https://git.javagil.de/mi/a\"b" to null,
    ).map { (remote, expected) -> Triple(remote, expected, repositoryWebUrl(remote)) }
        .filter { (_, expected, actual) -> actual != expected }
        .map { (remote, expected, actual) -> "repositoryWebUrl(\"$remote\") = $actual, expected $expected" }
    doLast { check(wrong.isEmpty()) { wrong.joinToString("\n") } }
}
tasks.check { dependsOn(checkRepositoryWebUrl) }

tasks.processResources {
    // the server reports the version, the page's footer shows it, as Werkator's does
    val version = project.version.toString()
    inputs.property("version", version)
    inputs.property("repositoryUrl", repositoryUrl)
    filesMatching("werkregie.properties") { expand("version" to version) }
    // the page compares its own version with the server's and loads itself again when they differ
    filesMatching("static/index.html") { filter { it.replace("@repositoryUrl@", repositoryUrl).replace("@version@", version) } }
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
