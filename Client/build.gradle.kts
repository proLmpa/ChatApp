dependencies {
    implementation(project(":Share"))
}

tasks.jar {
    // 1. JAR 파일 내부에 실행 정보를 포함하는 MANIFEST.MF 설정
    manifest {
        // Kotlin의 main 함수가 포함된 클래스는 컴파일 시 'ClientKt'가 된다.
        attributes["Main-Class"] = "com.chat.client.ClientKt"
    }

    // 2. 모든 런타임 의존성 (Share 모듈, Kotlin 런타임 등)을 JAR에 포함
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // 서명 파일 충돌 방지
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}