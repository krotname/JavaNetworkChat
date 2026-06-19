import java.security.MessageDigest
import java.time.Instant

plugins {
    java
    application
    jacoco
    alias(libs.plugins.spotless)
    id("checkstyle")
    alias(libs.plugins.spotbugs)
}

group = "dev.krotname"
version = "1.6.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.awaitility)
    testCompileOnly(libs.spotbugs.annotations)
    testRuntimeOnly(libs.junit.platform.launcher)
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = file("config/checkstyle/checkstyle.xml")
}

application {
    mainClass.set("dev.krotname.networkchat.network.ChatServer")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val integrationTestSource = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}

val uiTestSource = sourceSets.create("uiTest") {
    java.srcDir("src/uiTest/java")
    resources.srcDir("src/uiTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
configurations["uiTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["uiTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.named<Checkstyle>("checkstyleIntegrationTest") {
    source = integrationTestSource.allJava
    classpath = integrationTestSource.compileClasspath
    configFile = file("config/checkstyle/checkstyle.xml")
}

tasks.named<Checkstyle>("checkstyleUiTest") {
    source = uiTestSource.allJava
    classpath = uiTestSource.compileClasspath
    configFile = file("config/checkstyle/checkstyle.xml")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests"
    group = "verification"
    testClassesDirs = integrationTestSource.output.classesDirs
    classpath = integrationTestSource.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    reports.html.required.set(false)
}

tasks.register<Test>("uiTest") {
    description = "Runs UI smoke tests"
    group = "verification"
    testClassesDirs = uiTestSource.output.classesDirs
    classpath = uiTestSource.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("integrationTest"))
    reports.html.required.set(false)
}

tasks.named("check") {
    dependsOn("integrationTest", "uiTest")
}

tasks.register<JacocoReport>("jacocoAllReport") {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"), tasks.named("uiTest"))
    executionData.setFrom(fileTree(layout.buildDirectory).include(
        "jacoco/test.exec",
        "jacoco/integrationTest.exec",
        "jacoco/uiTest.exec"
    ))
    sourceDirectories.setFrom(
        layout.files(
            "src/main/java/dev/krotname/networkchat/network",
            "src/main/java/dev/krotname/networkchat/protocol",
        )
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/java/main")) {
            include("dev/krotname/networkchat/network/**/*.class")
            include("dev/krotname/networkchat/protocol/**/*.class")
        }
    )
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn(tasks.named("jacocoAllReport"))
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"), tasks.named("uiTest"))
    executionData.setFrom(
        fileTree(layout.buildDirectory).include(
            "jacoco/test.exec",
            "jacoco/integrationTest.exec",
            "jacoco/uiTest.exec"
        )
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/java/main")) {
            include("dev/krotname/networkchat/network/**/*.class")
            include("dev/krotname/networkchat/protocol/**/*.class")
        }
    )
    sourceDirectories.setFrom(
        layout.files(
            "src/main/java/dev/krotname/networkchat/network",
            "src/main/java/dev/krotname/networkchat/protocol",
        )
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(
        "checkstyleIntegrationTest",
        "checkstyleUiTest",
        "jacocoTestCoverageVerification",
        "spotlessCheck",
        "spotbugsMain",
        "spotbugsTest"
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run chat server"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.krotname.networkchat.network.ChatServer")
    args = listOf("--port", "1500")
}

tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Run console client"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.krotname.networkchat.client.ConsoleChatClient")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runBotClient") {
    group = "application"
    description = "Run bot client"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.krotname.networkchat.client.BotChatClient")
}

tasks.register<JavaExec>("runGuiClient") {
    group = "application"
    description = "Run Swing client"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.krotname.networkchat.client.ClientGuiController")
}

tasks.register<JavaExec>("createAccount") {
    group = "application"
    description = "Print one accounts.csv row: --args=\"alice USER secret\""
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.krotname.networkchat.network.AccountTool")
}

val releasePackageName = "network-chat-${project.version}"
val releaseStagingDir = layout.buildDirectory.dir("release/staging/$releasePackageName")
val releaseDir = layout.buildDirectory.dir("release")

tasks.register<Sync>("prepareReleasePackage") {
    group = "distribution"
    description = "Prepare a runnable Windows release directory"
    dependsOn(tasks.named("jar"))
    into(releaseStagingDir)
    from(tasks.named<Jar>("jar")) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    from("scripts/release") {
        into("bin")
    }
    from(listOf("README.md", "README.en.md", "LICENSE", "SECURITY.md")) {
        into("docs")
    }
}

val packageWindowsZip = tasks.register<Zip>("packageWindowsZip") {
    group = "distribution"
    description = "Build a runnable Windows zip with server, console, bot, and GUI launchers"
    dependsOn(tasks.named("prepareReleasePackage"))
    archiveFileName.set("$releasePackageName-windows.zip")
    destinationDirectory.set(releaseDir)
    from(releaseStagingDir) {
        into(releasePackageName)
    }
}

val provenanceFile = layout.buildDirectory.file("release/provenance.json")

tasks.register("releaseProvenance") {
    group = "distribution"
    description = "Write local release provenance metadata for the Windows zip"
    dependsOn(packageWindowsZip)
    inputs.file(packageWindowsZip.flatMap { it.archiveFile })
    outputs.file(provenanceFile)
    doLast {
        val artifact = packageWindowsZip.get().archiveFile.get().asFile
        val sha256 = sha256(artifact)
        val content = """
            {
              "predicateType": "https://slsa.dev/provenance/v1",
              "buildType": "gradle:packageWindowsZip",
              "builder": {
                "id": "JavaNetworkChat Gradle build"
              },
              "subject": [
                {
                  "name": "${artifact.name}",
                  "digest": {
                    "sha256": "$sha256"
                  }
                }
              ],
              "metadata": {
                "project": "${project.name}",
                "version": "${project.version}",
                "buildStartedOn": "${Instant.now()}"
              }
            }
        """.trimIndent()
        provenanceFile.get().asFile.writeText(content)
    }
}

tasks.register("releaseChecksums") {
    group = "distribution"
    description = "Write SHA-256 checksums for release artifacts"
    dependsOn(packageWindowsZip, tasks.named("releaseProvenance"))
    val checksumsFile = layout.buildDirectory.file("release/checksums.txt")
    inputs.files(packageWindowsZip.flatMap { it.archiveFile }, provenanceFile)
    outputs.file(checksumsFile)
    doLast {
        val artifacts =
            listOf(
                packageWindowsZip.get().archiveFile.get().asFile,
                provenanceFile.get().asFile,
            )
        checksumsFile.get().asFile.writeText(
            artifacts.joinToString(System.lineSeparator()) {
                "${sha256(it)}  ${it.name}"
            } + System.lineSeparator()
        )
    }
}

tasks.register("releaseBundle") {
    group = "distribution"
    description = "Build release zip, checksums, and provenance metadata"
    dependsOn(packageWindowsZip, tasks.named("releaseProvenance"), tasks.named("releaseChecksums"))
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
