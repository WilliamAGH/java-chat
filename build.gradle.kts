plugins {
    java
    id("org.springframework.boot") version "3.5.10"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.spotbugs") version "6.4.8"
    id("pmd")
}

val javaVersion = 21
val springAiVersion = "1.1.2"
val openaiVersion = "4.16.0"
val springDotenvVersion = "5.1.0"
val jsoupVersion = "1.22.1"
val fastutilVersion = "8.5.18"
val jtokkitVersion = "1.1.0"
val flexmarkVersion = "0.64.8"
val caffeineVersion = "3.2.3"
val grpcVersion = "1.78.0"
val pdfboxVersion = "3.0.6"
val findSecBugsVersion = "1.14.0"
val spotbugsVersion = "4.9.8"
val pmdVersion = "7.20.0"

springBoot {
    mainClass.set("com.williamcallahan.javachat.JavaChatApplication")
}

group = "com.williamcallahan"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
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
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
        mavenBom("io.grpc:grpc-bom:$grpcVersion")
    }
}

dependencies {
    // Logging - ensure consistent Logback + SLF4J stack
    implementation("org.springframework.boot:spring-boot-starter-logging")
    implementation("ch.qos.logback:logback-classic")

    // Jackson - explicitly include for all run modes
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // Spring AI
    implementation("org.springframework.ai:spring-ai-advisors-vector-store")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-qdrant")

    // OpenAI Java SDK for reliable streaming
    implementation("com.openai:openai-java:$openaiVersion")

    // Spring Boot .env file support
    implementation("me.paulschwarz:spring-dotenv:$springDotenvVersion")

    // HTML parsing
    implementation("org.jsoup:jsoup:$jsoupVersion")

    // High-performance collections
    implementation("it.unimi.dsi:fastutil:$fastutilVersion")

    // Token counting
    implementation("com.knuddels:jtokkit:$jtokkitVersion")

    // Markdown processing
    implementation("com.vladsch.flexmark:flexmark:$flexmarkVersion")
    implementation("com.vladsch.flexmark:flexmark-ext-tables:$flexmarkVersion")
    implementation("com.vladsch.flexmark:flexmark-ext-gfm-strikethrough:$flexmarkVersion")
    implementation("com.vladsch.flexmark:flexmark-ext-gfm-tasklist:$flexmarkVersion")
    implementation("com.vladsch.flexmark:flexmark-ext-autolink:$flexmarkVersion")

    // Caching
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")

    // gRPC (version managed by BOM)
    implementation("io.grpc:grpc-core")

    // PDF processing
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")

    // Configuration processor
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Development tools
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // macOS DNS resolver to avoid Netty warning on Mac
    // Version managed by Spring Boot; classifier for Apple Silicon
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
    runtimeOnly("io.netty:netty-resolver-dns-classes-macos")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // SpotBugs FindSecBugs plugin
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:$findSecBugsVersion")
}

// SpotBugs configuration
spotbugs {
    toolVersion.set(spotbugsVersion)
    // Match Maven behavior: lint errors don't fail the build
    // Use explicit `./gradlew spotbugsMain` to enforce
    ignoreFailures.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    excludeFilter.set(file("spotbugs-exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    // Disable buggy FindSecBugs CORS detector that crashes on Spring config
    omitVisitors.add("CorsRegistryCORSDetector")
    excludeFilter.set(file("spotbugs-exclude.xml"))
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(false)
    }
}

// PMD configuration
pmd {
    toolVersion = pmdVersion
    ruleSetFiles = files("pmd-ruleset.xml")
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

// Test configuration - base settings for all Test tasks
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
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
        "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true"
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
