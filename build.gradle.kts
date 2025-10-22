plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.23" apply false
}

allprojects {
    group = "com.example"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        val implementation by configurations
        implementation(kotlin("stdlib"))
    }
}