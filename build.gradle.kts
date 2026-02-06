plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotbugs)
    id("pmd")
    alias(libs.plugins.spotless)
}

// Tool versions - these are used for plugin configuration, not dependency resolution
// Keeping as constants since they configure build tools, not runtime dependencies
val spotbugsToolVersion = "4.9.8"
val pmdToolVersion = "7.20.0"
val palantirVersion = "2.85.0"

springBoot {
    mainClass.set("com.williamcallahan.javachat.JavaChatApplication")
}

group = "com.williamcallahan"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

repositories {
    mavenCentral()
}

// Exclude commons-logging globally to avoid conflicts with Spring's spring-jcl
configurations.all {
    exclude(group = "commons-logging", module = "commons-logging")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
        mavenBom("io.grpc:grpc-bom:${libs.versions.grpc.get()}")
    }
}

dependencies {
    // Logging - ensure consistent Logback + SLF4J stack
    implementation(libs.spring.boot.starter.logging)
    implementation(libs.logback.classic)

    // Jackson - explicitly include for all run modes
    implementation(libs.jackson.databind)

    // Spring Boot starters
    implementation(libs.bundles.spring.boot.web)

    // Spring AI
    implementation(libs.bundles.spring.ai)

    // OpenAI Java SDK for reliable streaming
    implementation(libs.openai.java)

    // Spring Boot .env file support
    implementation(libs.spring.dotenv)

    // HTML parsing
    implementation(libs.jsoup)

    // High-performance collections
    implementation(libs.fastutil)

    // Token counting
    implementation(libs.jtokkit)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)

    // Markdown processing
    implementation(libs.bundles.flexmark.all)

    // Caching
    implementation(libs.caffeine)

    // Qdrant Java gRPC client for hybrid vector operations
    implementation(libs.qdrant.client)

    // gRPC (version managed by BOM)
    implementation(libs.grpc.core)

    // PDF processing
    implementation(libs.pdfbox)

    // Configuration processor
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Development tools
    developmentOnly(libs.spring.boot.devtools)

    // macOS DNS resolver to avoid Netty warning on Mac
    // Version managed by Spring Boot; classifier for Apple Silicon
    runtimeOnly(variantOf(libs.netty.resolver.dns.native.macos) { classifier("osx-aarch_64") })
    runtimeOnly(libs.netty.resolver.dns.classes.macos)

    // Testing
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.junit.platform.launcher)

    // SpotBugs FindSecBugs plugin
    spotbugsPlugins(libs.findsecbugs.plugin)
}

// SpotBugs configuration
spotbugs {
    toolVersion.set(spotbugsToolVersion)
    // Match Maven behavior: lint errors don't fail the build
    // Use explicit `./gradlew spotbugsMain` to enforce
    ignoreFailures.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    excludeFilter.set(file("config/spotbugs/spotbugs-exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    // Disable buggy FindSecBugs CORS detector that crashes on Spring config
    omitVisitors.add("CorsRegistryCORSDetector")
    excludeFilter.set(file("config/spotbugs/spotbugs-exclude.xml"))
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(false)
    }
}

// PMD configuration
pmd {
    toolVersion = pmdToolVersion
    ruleSetFiles = files("config/pmd/pmd-ruleset.xml")
    // Match Maven behavior: lint errors don't fail the build
    // Use explicit `./gradlew pmdMain` to enforce
    isIgnoreFailures = true
}

tasks.withType<Pmd>().configureEach {
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat(palantirVersion)
        removeUnusedImports()
    }
}

// Test configuration - base settings for all Test tasks
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        // Suppress sun.misc.Unsafe deprecation warnings from gRPC/Netty (Qdrant client dependency)
        // See: https://netty.io/wiki/java-24-and-sun.misc.unsafe.html
        "--sun-misc-unsafe-memory-access=allow"
    )
}

// Unit test task - exclude integration tests
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Integration test task - only run integration-tagged tests
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

// bootRun JVM configuration
// Note: -Dorg.gradle.jvmargs in Makefile configures Gradle daemon, not the app.
// These jvmArgs configure the actual Spring Boot application process.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "-Xmx2g",
        "-Dio.netty.handler.ssl.noOpenSsl=true",
        "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true",
        // Suppress sun.misc.Unsafe deprecation warnings from gRPC/Netty (Qdrant client dependency)
        // See: https://netty.io/wiki/java-24-and-sun.misc.unsafe.html
        "--sun-misc-unsafe-memory-access=allow"
    )
    systemProperty("java.net.preferIPv4Stack", "true")
    // spring.devtools.restart.enabled is controlled by devtools presence on classpath
}

// Custom helper tasks for scripts
tasks.register("buildForScripts") {
    description = "Build application JAR for use in scripts (skips tests)"
    group = "build"
    dependsOn(tasks.bootJar)
    doLast {
        val jarFile = tasks.bootJar.get().archiveFile.get().asFile
        println("Built JAR: ${jarFile.absolutePath}")
    }
}

tasks.register<JavaExec>("runDocumentProcessor") {
    description = "Run DocumentProcessor CLI for batch ingestion"
    group = "application"
    mainClass.set("com.williamcallahan.javachat.cli.DocumentProcessor")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("spring.profiles.active", "default")
    
    // Pass DOCS_DIR from environment or use default
    val docsDir = System.getenv("DOCS_DIR") ?: "${project.rootDir}/data/docs"
    systemProperty("DOCS_DIR", docsDir)
}

val bootJarPath = tasks.bootJar.get().outputs.files.singleFile.absolutePath

tasks.register("printJarPath") {
    description = "Print the path to the built boot JAR"
    group = "help"
    dependsOn(tasks.bootJar)
    doLast {
        println(bootJarPath)
    }
}
