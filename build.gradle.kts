import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.31"
    kotlin("plugin.serialization") version "1.5.31"
    id("com.squareup.sqldelight") version "1.5.1"
    application
}

sqldelight {
    database("BotDatabase") { // This will be the name of the generated database class.
        packageName = "signallatexbot.db"
        schemaOutputDirectory = file("src/main/sqldelight/databases")
        verifyMigrations = true
        dialect = "sqlite:3.25"
    }
}

group = "me.user"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
    implementation("org.inthewaves.kotlin-signald:client-coroutines:0.14.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.69")
    implementation("org.scilab.forge:jlatexmath:1.0.7")
    implementation("com.github.ajalt.clikt:clikt:3.2.0")
    implementation("com.google.crypto.tink:tink:1.6.1")

    implementation("com.squareup.sqldelight:sqlite-driver:1.5.1")
    // transitive dependency of com.squareup.sqldelight:sqlite-driver anyway, included for config options
    implementation("org.xerial:sqlite-jdbc:3.36.0.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf("-Xopt-in=kotlin.time.ExperimentalTime")
    }
}

application {
    mainClass.set("signallatexbot.MainKt")
}