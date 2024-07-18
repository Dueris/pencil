import io.papermc.paperweight.tasks.DownloadServerJar
import io.papermc.paperweight.tasks.ExtractFromBundler
import io.papermc.paperweight.tasks.RemapJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

val MINECRAFT_VERSION = "1.21"
val VER_FILE = "./cache/data/versions.ver"
val CACHE_DIR = "./cache/pencil"
val DECOMPILER_VERSION = "1.10.1"

plugins {
    id("java")
    id("io.papermc.paperweight.core") version "1.7.2-SNAPSHOT" apply true
    id("io.github.goooler.shadow") version "8.1.7"
    kotlin("jvm")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
}

group = "io.github.dueris"
version = "1.0-SNAPSHOT"

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

repositories {
    mavenCentral()
    mavenLocal()
    maven(paperMavenPublicUrl) {
//        content {
//            onlyForConfigurations(
//                configurations.paperclip.name,
//                spigotDecompiler.name,
//            )
//        }
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net")
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }
}

dependencies {
    api("io.sigpipe:jbsdiff:1.0")

    paramMappings("net.fabricmc:yarn:1.21+build.1:mergedv2")
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")

}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.github.dueris.Bundler",
            "Bundler-Format" to "1.0",
            "Manifest-Version" to "1.0"
        )
    }
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")

    paramMappingsRepo = paperMavenPublicUrl
    remapRepo = paperMavenPublicUrl
    decompileRepo = paperMavenPublicUrl
}

tasks.test {
    useJUnitPlatform()
}

tasks.create("createServerJar") {
    dependsOn("shadowJar")
    doLast {
        val file = rootDir.resolve("build/libs/pencil-1.0-SNAPSHOT-all.jar").toPath().toFile()
        val newFile = File(rootDir.resolve("build/libs/pencil-1.0-SNAPSHOT-all.jar").toString().replace("pencil-1.0-SNAPSHOT-all", "pencil-$MINECRAFT_VERSION-bundler"))

        if (file.exists()) {
            if (file.renameTo(newFile)) {
                println("File renamed successfully")
            } else {
                println("Failed to rename file")
            }
        } else {
            println("File does not exist")
        }
    }
}

tasks.create("initPatches") {
    doLast {
        val submodulePath = rootDir.resolve("Pencil-Server")
        val commits = getSubmoduleCommits()

        commits.reversed().forEachIndexed { index, commit ->
            if (index == 0) return@forEachIndexed
            println("Found commit $commit, building...")
            runCommand("git|format-patch|-${index}|-o|../patches|".plus(commit), submodulePath)
        }
    }
}

tasks.create("genSources").dependsOn("genSource")

tasks.create("genSource") {
    dependsOn("setupEnvironment")
    doLast {
        moveFile("./Pencil-Server/src/main/java/META-INF", "./Pencil-Server/src/main/resources")
        moveFile("./.gradle/caches/bundler/version.json", "./src/main/resources")
        moveFile("./.gradle/caches/resources/data", "./Pencil-Server/src/main/resources")
        moveFile("./.gradle/caches/resources/assets", "./Pencil-Server/src/main/resources")
        moveFile("./.gradle/caches/resources/version.json", "./Pencil-Server/src/main/resources")
        moveFile("./.gradle/caches/resources/flightrecorder-config.jfc", "./Pencil-Server/src/main/resources")
        safelyDeleteFile("./.gradle/caches/resources")

        runCommand("git|add|.", File("Pencil-Server"))
        runCommand("git|commit|-m|Initial Source", File("Pencil-Server"))

        rootDir.resolve("patches").listFiles()?.forEach {
            println("Found commit $it, applying...")
            val input = it.name.replace("-", " ")
            val removedSuffix = input.removeSuffix(".patch")
            val result = removedSuffix.substring(5, minOf(100, removedSuffix.length))
            runCommand("git|apply|${it}|--3way|--ignore-whitespace", rootDir.resolve("Pencil-Server"))
            runCommand("git|add|.", rootDir.resolve("Pencil-Server"))
            runCommand("git|commit|-m|$result", rootDir.resolve("Pencil-Server"))
        }

        println("Patches applied successfully")
    }
}

tasks.create("setupEnvironment") {
    dependsOn("remapJar")
    doLast {
        File("Pencil-Server").deleteRecursively()
        println("Preparing for source gen...")
        runCommand("git|init", File("Pencil-Server"))
        runCommand("git|rm|-rf|.", File("Pencil-Server"))
        println("Downloading decompiler...")
        downloadFileFromUrl(
            "https://github.com/Vineflower/vineflower/releases/download/$DECOMPILER_VERSION/vineflower-$DECOMPILER_VERSION.jar",
            "./.gradle/caches/decompiler/decompiler.jar"
        )
        println()
        println("Starting decompile...")
        val stopWatch = StopWatch()
        stopWatch.start()

        val remapJarTask = tasks.getByName<RemapJar>("remapJar")
        val downloadServerJarTask = tasks.getByName<DownloadServerJar>("downloadServerJar")
        val jarFile = File(
            remapJarTask.outputJar.asFile.get().parentFile.parentFile.parentFile.resolve("decompiler")
                .resolve("decompiler.jar").absolutePath
        )

        val jarFilePath = jarFile.absolutePath
        val remapJarOutput = remapJarTask.outputJar.asFile.get().absolutePath
        val downloadServerJarOutput = downloadServerJarTask.outputJar.asFile.get().absolutePath
        val extractFromBundlerOutput = tasks.getByName<ExtractFromBundler>("extractFromBundler").serverJar

        runDecompile(jarFilePath, remapJarOutput, "./Pencil-Server/src/main/java/")
        runDecompile(jarFilePath, downloadServerJarOutput, "./.gradle/caches/bundler")
        runDecompile(jarFilePath, extractFromBundlerOutput.asFile.get().absolutePath, "./.gradle/caches/resources")

        stopWatch.stop()
        println()
        println("Finished decompile in ${stopWatch.elapsedTime} ms")
        buildSourceRepo()

        if (!isCommandAvailable("bsdiff")) else {
            throw RuntimeException("bsdiff command is required")
        }
    }
}

tasks.create("buildBundler") {
    dependsOn(":pencil-server:build")
    doLast {
        if (!isCommandAvailable("bsdiff")) else {
            throw RuntimeException("bsdiff command is required")
        }

        runCommand(
            "bsdiff|.gradle/caches/paperweight/taskCache/extractFromBundler.jar|Pencil-Server/build/libs/pencil-server-1.21.jar|patch.patch",
            rootDir
        )
        moveFile(rootDir.resolve("patch.patch").absolutePath, "src/main/resources/versions")
    }
}

tasks.processResources {
    dependsOn("buildBundler")
}

fun isCommandAvailable(command: String): Boolean {
    return try {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor()
        process.exitValue() == 0
    } catch (e: Exception) {
        false
    }
}

fun runDecompile(jarPath: String, toDecompile: String, outputDir: String) {
    val processBuilder = ProcessBuilder(
        "java", "-jar", jarPath, toDecompile, outputDir
    ).redirectErrorStream(true)

    val process = processBuilder.start()

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { println(it) }
    }

    process.waitFor()
}

fun safelyDeleteFile(filePath: String): Boolean {
    val file = File(filePath)
    return try {
        if (file.exists()) {
            deleteRecursively(file)
        } else true
    } catch (e: Exception) {
        println("An error occurred while trying to delete the file: ${e.message}")
        false
    }
}

private fun deleteRecursively(file: File): Boolean {
    return if (file.isDirectory) {
        file.listFiles()?.forEach { child ->
            if (!deleteRecursively(child)) {
                return false
            }
        }
        file.delete()
    } else {
        file.delete()
    }
}

fun runCommand(command: String, directory: File) {
    try {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val process = ProcessBuilder(*command.split("|").toTypedArray())
            .directory(directory)
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

    } catch (e: Exception) {
        throw e
    }
}

fun getSubmoduleCommits(): List<String> {
    val processBuilder = ProcessBuilder("git", "log").directory(File("./Pencil-Server/"))
    val process = processBuilder.start()

    val result = mutableListOf<String>()
    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        reader.forEachLine {
            if (it.startsWith("commit ")) {
                result.add(it.split("commit ")[1].split(" ")[0])
            }
        }
    }

    process.waitFor()

    return result
}

fun downloadFileFromUrl(fileUrl: String, destinationPath: String) {
    val url = URL(fileUrl)
    val connection = url.openConnection() as HttpURLConnection

    connection.inputStream.use { input ->
        File(destinationPath).apply {
            parentFile.mkdirs()
            FileOutputStream(this).use { output ->
                input.copyTo(output)
            }
        }
    }
}

fun buildSourceRepo() {
    val projectDir = File("Pencil-Server")
    if (!projectDir.exists()) {
        projectDir.mkdirs()
    }

    val gitignoreFile = File(projectDir, ".gitignore")
    val buildGradleKtsFile = File(projectDir, "build.gradle.kts")
    val gradlePropertiesFile = File(projectDir, "gradle.properties")

    gitignoreFile.writeText(
        """
        # Ignore Gradle files
        /.gradle
        /build/
        
        # Ignore IntelliJ IDEA project files
        /.idea
        *.iml
        
        # Ignore Mac OS files
        .DS_Store
        
        # Ignore other unnecessary files
        *.log
        """.trimIndent()
    )

    buildGradleKtsFile.writeText(
        """
        plugins {
            id("java")
            kotlin("jvm")
            idea
        }
        
        group = "net.minecraft"
        version = "1.21"
        
        repositories {
            mavenCentral()
        }
        
        dependencies {
            val dependencyCache = projectDir.parentFile.resolve(".gradle").resolve("caches").resolve("paperweight").resolve("jars").resolve("minecraft")
            if (dependencyCache.exists() && dependencyCache.isDirectory) {
                dependencyCache.walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { file ->
                    implementation(files(file))
                }
            }
            implementation("org.jetbrains:annotations:24.0.0")
            implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
            implementation("com.google.code.findbugs:jsr305:3.0.2")
        
        }

        """.trimIndent()
    )

    gradlePropertiesFile.writeText(
        """
        # Project properties
        org.gradle.caching=true
        org.gradle.parallel=true
        org.gradle.vfs.watch=false
        """.trimIndent()
    )

    println("Subproject source successfully built")
}

fun moveFile(sourcePath: String, targetDirPath: String) {
    val sourceFile = File(sourcePath)
    val targetDir = File(targetDirPath)

    if (!targetDir.exists()) {
        targetDir.mkdirs()
    }

    val targetFile = File(targetDir, sourceFile.name)
    Files.move(
        rootDir.resolve(sourcePath).toPath(),
        rootDir.resolve(targetFile).toPath(),
        StandardCopyOption.REPLACE_EXISTING
    )
}

class StopWatch {
    private var startTime: Long = 0
    private var endTime: Long = 0
    private var running = false

    fun start() {
        startTime = System.currentTimeMillis()
        running = true
    }

    fun stop() {
        endTime = System.currentTimeMillis()
        running = false
    }

    val elapsedTime: Long
        get() = if (running) {
            System.currentTimeMillis() - startTime
        } else {
            endTime - startTime
        }
}

tasks.getByName("clean").dependsOn("cleanCache")
