plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("com.gradleup.shadow") version "9.3.0"
}

group = "io.github.flowerblackg"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.flowerblackg.janus.MainKt")
}

tasks.test {
    useJUnitPlatform()
}