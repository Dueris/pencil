import io.papermc.paperweight.tasks.RemapJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.org.apache.commons.lang3.time.StopWatch
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Paths

val MINECRAFT_VERSION = "1.21"
val VER_FILE = "./cache/data/versions.ver"
val CACHE_DIR = "./cache/pencil"
val DECOMPILER_VERSION = "1.10.1"

plugins {
    id("java")
    id("io.papermc.paperweight.core") version "1.7.1" apply true
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

tasks.create("genSource") {
    dependsOn("remapJar")
    doLast {
        println("Downloading decompiler...")
        println()
        downloadFileFromUrl("https://github.com/Vineflower/vineflower/releases/download/$DECOMPILER_VERSION/vineflower-$DECOMPILER_VERSION.jar", "./.gradle/caches/decompiler/decompiler.jar")

        println("Starting decompile...")
        val stopWatch = StopWatch()
        stopWatch.start()

        val remapJarTask = tasks.getByName<RemapJar>("remapJar")
        val jarFile = File(remapJarTask.outputJar.asFile.get().parentFile.parentFile.parentFile.resolve("decompiler").resolve("decompiler.jar").absolutePath)

        val processBuilder = ProcessBuilder("java", "-jar", jarFile.absolutePath, "${remapJarTask.outputJar.asFile.get()}", "./Pencil-Server/src/main/java/")
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

        println("Patches applied successfully")
    }
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
        }

        group = "net.minecraft"
        version = "$MINECRAFT_VERSION"

        repositories {
            mavenCentral()
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
