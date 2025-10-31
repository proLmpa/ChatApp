kotlin {
    jvmToolchain(17)
}

plugins {
    kotlin("jvm") version "2.2.0"
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
        implementation(kotlin("stdlib"))

        testImplementation(kotlin("test"))
        // JUnit5
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")

        // Mockito + JUnit5 통합
        testImplementation("org.mockito:mockito-junit-jupiter:5.10.0")

        // Kotlin-friendly syntax (verify, whenever 등)
        testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")

        // final, static 클래스 mock 객체 생성을 위한 mockito-inline
        testImplementation("org.mockito:mockito-inline:5.2.0")
    }

    tasks.test {
        useJUnitPlatform()

        // Mockito가 java.base 모듈 내부 클래스(java.net, java.io 등)에 접근 가능하도록 허용
        jvmArgs(
            "--add-opens", "java.base/java.net=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
        )
    }
}