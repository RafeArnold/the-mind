plugins {
    kotlin("jvm") version "1.9.22"
}

group = "uk.co.rafearnold"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(20)
}
