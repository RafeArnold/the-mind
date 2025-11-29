import com.github.gradle.node.npm.task.NpxTask
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.FileLoader
import java.io.FileWriter

buildscript {
  repositories {
    mavenCentral()
  }
  dependencies {
    classpath("io.pebbletemplates:pebble:4.0.0")
  }
}

plugins {
  kotlin("jvm") version "2.2.21"
  id("org.jmailen.kotlinter") version "5.3.0"
  id("com.github.node-gradle.node") version "7.1.0"
  id("com.github.johnrengelman.shadow") version "8.1.1"
  application
}

group = "uk.co.rafearnold"

application.mainClass = "uk.co.rafearnold.themind.MainKt"

repositories {
  mavenCentral()
}

dependencies {
  implementation(platform("org.http4k:http4k-bom:6.22.0.0"))
  implementation("org.http4k:http4k-core")
  implementation("org.http4k:http4k-server-jetty")
  implementation("org.http4k:http4k-template-core")
  implementation("org.http4k:http4k-format-jackson")

  implementation("io.pebbletemplates:pebble:4.0.0")

  implementation("org.sqids:sqids:0.1.0")

  implementation("ch.qos.logback:logback-classic:1.5.21")

  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation("com.microsoft.playwright:playwright:1.56.0")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
}

tasks.run.invoke {
  environment("HOT_RESOURCE_RELOADING", true)
}

tasks.check {
  dependsOn("installKotlinterPrePushHook")
}

tasks.processResources {
  dependsOn("buildCss")
  exclude("input/")
  exclude("template-variants/")
}

task("buildCss", NpxTask::class) {
  dependsOn("buildTemplateVariants")
  command = "@tailwindcss/cli"
  args = listOf(
    "-i", "./src/main/resources/input/index.css",
    "-o", "./src/main/resources/assets/index.min.css",
    "-m",
  )
  inputs.files(fileTree("./src/main/resources") { include("*.html") })
  inputs.file("./src/main/resources/input/index.css")
  outputs.file("./src/main/resources/assets/index.min.css")
  dependsOn("npmInstall")
}

/**
 * Some of the Tailwind CSS classes in the application's templates are generated
 * [dynamically](https://tailwindcss.com/docs/content-configuration#dynamic-class-names) at
 * runtime, so Tailwind would not detect that they are required when its build command is executed.
 * This task generates the variants of the templates that contain the CSS classes that Tailwind
 * needs to know about, thus ensuring that they will be included in the output CSS file.
 */
task("buildTemplateVariants") {
  doLast {
    val engine =
      PebbleEngine.Builder().loader(FileLoader().apply { prefix = "src/main/resources" }).build()
    val outputDir = "src/main/resources/template-variants"
    File(outputDir).mkdirs()
    repeat(2) {
      FileWriter("$outputDir/ws-game-$it.html").use { writer ->
        val context =
          mapOf(
            "model" to mapOf(
              "otherPlayers" to listOf(mapOf("cardCount" to 11 + it)),
              "cards" to (1..11 + it).toList(),
              "playedCards" to (1..99 + it).toList(),
            )
          )
        engine.getTemplate("ws-game.html").evaluate(writer, context)
      }
    }
  }
}
