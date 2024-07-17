import io.papermc.paperweight.tasks.RemapJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

val MINECRAFT_VERSION = "1.21"
val VER_FILE = "./cache/data/versions.ver"
val CACHE_DIR = "./cache/pencil"
val DECOMPILER_VERSION = "1.10.1"

plugins {
    id("java")
    id("io.papermc.paperweight.core") version "1.7.2-SNAPSHOT" apply true
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
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("net.fabricmc:tiny-remapper:0.10.4")

    paramMappings("net.fabricmc:yarn:1.21+build.1:mergedv2")
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")
    paperclip("io.papermc:paperclip:3.0.3")
    implementation("org.vineflower:vineflower:1.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
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

tasks.create("initPatches") {
    doLast {
        val submodulePath = rootDir.resolve("Pencil-Server")
        val commits = getSubmoduleCommits(submodulePath.absolutePath)

        commits.reversed().forEachIndexed { index, commit ->
            if (index == 0) return@forEachIndexed
            println("Found commit $commit, building...")
            runCommand("git|format-patch|-${index}|-o|../patches|".plus(commit), submodulePath)
        }
    }
}

tasks.create("genSource") {
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
        val jarFile = File(
            remapJarTask.outputJar.asFile.get().parentFile.parentFile.parentFile.resolve("decompiler")
                .resolve("decompiler.jar").absolutePath
        )

        val processBuilder = ProcessBuilder(
            "java",
            "-jar",
            jarFile.absolutePath,
            "${remapJarTask.outputJar.asFile.get()}",
            "./Pencil-Server/src/main/java/"
        )
            .redirectErrorStream(true)
        val process = processBuilder.start()

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                println(line)
            }
        }

        val exitCode = process.waitFor()
        println("Process exited with code: $exitCode")
        stopWatch.stop()
        println()
        println("Finished decompile in ${stopWatch.elapsedTime} ms")
        buildSourceRepo()

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

fun runCommand(command: String, directory: File) {
    try {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val process = ProcessBuilder(*command.split("|").toTypedArray())
            .directory(directory)
            .redirectErrorStream(true)
            .start()

        val result = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        println(result)
    } catch (e: Exception) {
        throw e
    }
}

fun getSubmoduleCommits(submodulePath: String): List<String> {
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

    gitignoreFile.writeText("""
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
        """.trimIndent())

    buildGradleKtsFile.writeText("""
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

        """.trimIndent())

    gradlePropertiesFile.writeText("""
        # Project properties
        org.gradle.caching=true
        org.gradle.parallel=true
        org.gradle.vfs.watch=false
        """.trimIndent())

    println("Subproject source successfully built")
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
