import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.7.10"
  kotlin("plugin.serialization") version "1.6.10"
  id("com.squareup.sqldelight") version "1.5.3"
  id("org.jlleitschuh.gradle.ktlint") version "10.2.1"
  id("org.jlleitschuh.gradle.ktlint-idea") version "10.2.1"
  application
}

ktlint {
  filter {
    exclude { element -> element.file.path.contains("generated/") }
  }
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

    /*
    mavenLocal {
        content {
            includeGroup("org.inthewaves.kotlin-signald")
        }
    }
     */
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
  implementation("org.inthewaves.kotlin-signald:client-coroutines:0.25.0+signald-0.17.0-10-4c7897e2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
  implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
  implementation("org.scilab.forge:jlatexmath:1.0.7")
  implementation("com.github.ajalt.clikt:clikt:3.4.0")
  implementation("com.google.crypto.tink:tink:1.6.1")

  implementation("com.squareup.sqldelight:sqlite-driver:1.5.3")
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
