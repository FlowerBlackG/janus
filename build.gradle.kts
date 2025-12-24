plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
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
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-protobuf
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.9.0")

    implementation(kotlin("reflect"))

    // https://mvnrepository.com/artifact/org.json/json
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.github.flowerblackg.janus.MainKt")
}

tasks.test {
    useJUnitPlatform()
}