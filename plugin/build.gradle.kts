// SPDX-License-Identifier: MPL-2.0

import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    java
}

version = "0.1.0-alpha"

/*
|--------------------------------------------------------------------------
| Engine
|--------------------------------------------------------------------------
|
| We compile against the jars of the one engine release we support, and
| against nothing else. The build downloads the release tarball once into
| your Gradle home, checks it against the SHA-256 the release publishes,
| and unpacks the engine's server libraries and the four connector jars we
| compile against under build/. A clean build reuses the download, and we
| never commit anything from the tarball.
|
*/

abstract class DownloadEngine : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val tarball: RegularFileProperty

    @TaskAction
    fun download() {
        val target = tarball.get().asFile

        if (target.isFile && sha256Of(target) == sha256.get()) {
            return
        }

        val partial = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")

        target.parentFile.mkdirs()
        URI(url.get()).toURL().openStream().use { input ->
            DigestInputStream(input, digest).use { checked ->
                partial.outputStream().use { output -> checked.copyTo(output) }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }

        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("The engine tarball has SHA-256 $actual, but we expect ${sha256.get()}.")
        }

        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            DigestInputStream(input, digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val downloadEngine = tasks.register<DownloadEngine>("downloadEngine") {
    url = "https://github.com/OpenIntegrationEngine/engine/releases/download/v4.6.0/oie_unix_4_6_0.tar.gz"
    sha256 = "7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea"
    tarball = File(gradle.gradleUserHomeDir, "caches/pontik-cluster/oie_unix_4_6_0.tar.gz")
}

val unpackEngine = tasks.register<Sync>("unpackEngine") {
    dependsOn(downloadEngine)
    from(provider { tarTree(downloadEngine.get().tarball.get().asFile) })
    include(
        "oie/server-lib/**",
        "oie/extensions/file/file-shared.jar",
        "oie/extensions/jdbc/jdbc-shared.jar",
        "oie/extensions/js/js-shared.jar",
        "oie/extensions/vm/vm-shared.jar",
    )
    eachFile {
        relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("engine/oie"))
}

val engineJars = fileTree(layout.buildDirectory.dir("engine/oie")) {
    include("server-lib/**/*.jar", "extensions/*/*-shared.jar")
    builtBy(unpackEngine)
}

val engineDriver = fileTree(layout.buildDirectory.dir("engine/oie")) {
    include("server-lib/database/postgresql-*.jar")
    builtBy(unpackEngine)
}

/*
|--------------------------------------------------------------------------
| Java
|--------------------------------------------------------------------------
|
| The plugin runs inside the engine, so it targets Java 8 whatever JDK
| builds it. The tests run on the JDK 21 toolchain and may use what
| JUnit and Testcontainers need from it.
|
*/

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    compileOnly(engineJars)

    testImplementation(engineDriver)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Xlint:-path", "-Werror"))
}

tasks.compileTestJava {
    options.release = 21
    options.encoding = "UTF-8"
}

/*
|--------------------------------------------------------------------------
| Packaging
|--------------------------------------------------------------------------
|
| The engine installs a plugin from a zip whose single top-level folder
| holds plugin.xml and the server jar. A zip without that folder is
| accepted and installs nothing, so the packaging test checks the layout.
|
*/

val pluginVersion = version.toString()

tasks.jar {
    archiveFileName = "pontik-cluster-server.jar"
    manifest {
        attributes("Implementation-Title" to "Pontik Cluster", "Implementation-Version" to pluginVersion)
    }
}

val pluginZip = tasks.register<Zip>("pluginZip") {
    archiveFileName = "pontik-cluster-$pluginVersion.zip"
    into("pontik-cluster") {
        from(tasks.jar)
        from("src/dist/plugin.xml") {
            filter(ReplaceTokens::class, "tokens" to mapOf("version" to pluginVersion))
        }
    }
}

tasks.assemble {
    dependsOn(pluginZip)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(pluginZip)
    systemProperty("pontik.zip", pluginZip.get().archiveFile.get().asFile.path)
}
