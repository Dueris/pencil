import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

val MINECRAFT_VERSION = "1.21"
val VER_FILE = "./cache/data/versions.ver"
val CACHE_DIR = "./cache/pencil"

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


        println("Patches applied successfully")
    }
}

tasks.getByName("clean").dependsOn("cleanCache")
