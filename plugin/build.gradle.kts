plugins {
    id("java")
    kotlin("jvm")
    idea
}

group = "io.github.dueris"
version = "1.21"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project("::pencil-server"))
}
