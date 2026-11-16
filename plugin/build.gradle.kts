// SPDX-License-Identifier: MPL-2.0

import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    java
    id("de.thetaphi.forbiddenapis") version "3.11"
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
| never commit anything from the tarball. To move to another release, you
| change engineVersion and the SHA-256 beside it, and plugin.xml follows.
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
        // The default timeouts never expire, so a stalled connection would hang the build with no output.
        val connection = URI(url.get()).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.getInputStream().use { input ->
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

val engineVersion = "4.6.0"
val engineTarball = "oie_unix_${engineVersion.replace('.', '_')}.tar.gz"

val downloadEngine = tasks.register<DownloadEngine>("downloadEngine") {
    url = "https://github.com/OpenIntegrationEngine/engine/releases/download/v$engineVersion/$engineTarball"
    sha256 = "7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea"
    tarball = File(gradle.gradleUserHomeDir, "caches/pontik-cluster/$engineTarball")
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

    // The engine's own Jackson and logging bridges clash with those Testcontainers brings, and no
    // test needs them.
    testImplementation(engineJars.filter { jar ->
        !jar.path.contains("/server-lib/jackson/") && !jar.name.startsWith("slf4j-")
            && !jar.name.startsWith("log4j-1.2-api")
    })
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
| Wall Clock
|--------------------------------------------------------------------------
|
| A lease never depends on this node's wall clock, so the build reads the
| compiled lease classes and fails on any call the signatures file lists,
| whichever way the source spells it. The tests may read the clock as
| they please, so their check is off.
|
*/

tasks.named<CheckForbiddenApis>("forbiddenApisMain") {
    signaturesFiles = files(rootProject.file("config/forbidden-apis/wall-clock.txt"))
    // include() would add to the task's default pattern for every class, so we replace it.
    setIncludes(listOf("dev/pontik/cluster/lease/**"))
}

tasks.named("forbiddenApisTest") {
    enabled = false
}

/*
|--------------------------------------------------------------------------
| Engine-Free Packages
|--------------------------------------------------------------------------
|
| The lease keeper and the fault registry never depend on the engine or on
| our own engine-facing packages. The build compiles them a second time on
| their own, with only the PostgreSQL driver on the classpath, so an import
| of either fails the build.
|
*/

val compileEngineFree = tasks.register<JavaCompile>("compileEngineFree") {
    source = fileTree("src/main/java") {
        include("dev/pontik/cluster/lease/**", "dev/pontik/cluster/fault/**")
    }
    classpath = engineDriver
    destinationDirectory = layout.buildDirectory.dir("engine-free")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(21) }
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

tasks.check {
    dependsOn(compileEngineFree)
}

/*
|--------------------------------------------------------------------------
| Packaging
|--------------------------------------------------------------------------
|
| The engine installs a plugin from a zip whose single top-level folder
| holds plugin.xml and its jars. A zip without that folder is accepted
| and installs nothing, so the packaging test checks the layout. The REST
| interface and the views it returns go in a shared jar, which the engine
| also loads where it registers the REST calls; everything else goes in
| the server jar. plugin.xml takes its engine version from the Engine
| section, so the two cannot drift apart. Both jars carry the license text
| in META-INF, so it travels with them wherever they are copied.
|
*/

val pluginVersion = version.toString()

val shared = listOf(
    "dev/pontik/cluster/api/ClusterServletInterface*",
    "dev/pontik/cluster/api/ChannelView*",
    "dev/pontik/cluster/api/NodeView*",
)

tasks.jar {
    archiveFileName = "pontik-cluster-server.jar"
    exclude(shared)
    metaInf { from(rootProject.file("LICENSE")) }
    manifest {
        attributes("Implementation-Title" to "Pontik Cluster", "Implementation-Version" to pluginVersion)
    }
}

val sharedJar = tasks.register<Jar>("sharedJar") {
    archiveFileName = "pontik-cluster-shared.jar"
    from(sourceSets.main.map { it.output }) {
        include(shared)
    }
    metaInf { from(rootProject.file("LICENSE")) }
    manifest {
        attributes("Implementation-Title" to "Pontik Cluster", "Implementation-Version" to pluginVersion)
    }
}

val pluginZip = tasks.register<Zip>("pluginZip") {
    archiveFileName = "pontik-cluster-$pluginVersion.zip"
    // Gradle does not track a filter's tokens, so without this an engine bump could leave the zip up to date.
    inputs.property("mirthVersion", engineVersion)
    into("pontik-cluster") {
        from(tasks.jar)
        from(sharedJar)
        from("src/dist/plugin.xml") {
            filter(
                ReplaceTokens::class,
                "tokens" to mapOf("version" to pluginVersion, "mirthVersion" to engineVersion),
            )
        }
    }
}

tasks.assemble {
    dependsOn(pluginZip)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(pluginZip)
    // Two tests read the README, so an edit to it runs them again.
    inputs.file(rootProject.file("README.md"))
    systemProperty("pontik.zip", pluginZip.get().archiveFile.get().asFile.path)
}
