// 启用 Java 项目支持、可运行程序支持，以及打包 fat jar 的 Shadow 插件。
plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

// 指定项目使用 Java 21。Gradle 会尽量按这个版本来编译和运行。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// 告诉 Gradle 程序入口类是谁，`./gradlew run` 会从这里启动。
application {
    mainClass = "com.mewcode.MewCode"
}

// 依赖默认从 Maven Central 下载。
repositories {
    mavenCentral()
}

// 这里列出项目运行和测试所需的第三方库。
dependencies {
    // 终端 I/O（TUI 框架的底层驱动）
    implementation("org.jline:jline:3.28.0")

    // Markdown terminal rendering
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // LLM SDKs
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")

    // Web server (Remote mode)
    implementation("io.javalin:javalin:6.6.0")

    // Config & JSON
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

// 统一 Java 源码编译编码，避免不同系统下出现中文或字符串乱码。
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// 告诉 Gradle 测试使用 JUnit 5 平台。
tasks.test {
    useJUnitPlatform()
}

// 生成一个包含所有依赖的可执行 jar，方便分发和直接运行。
tasks.shadowJar {
    archiveBaseName = "mewcode"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

// 这些任务依赖关系表示：在生成发行包或启动脚本前，先准备好 fat jar。
tasks.distZip { dependsOn(tasks.shadowJar) }
tasks.distTar { dependsOn(tasks.shadowJar) }
tasks.startScripts { dependsOn(tasks.shadowJar) }
tasks.named("startShadowScripts") { dependsOn(tasks.jar) }
