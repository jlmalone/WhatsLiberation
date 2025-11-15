import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "vision.salient"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.api-client:google-api-client-jackson2:2.2.0")
    implementation("com.google.http-client:google-http-client-jackson2:1.44.1")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")
    implementation("com.google.oauth-client:google-oauth-client-java6:1.34.1")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
    implementation("com.google.apis:google-api-services-people:v1-rev20250513-2.0.0")

    // Database dependencies (Phase C - Persistent Registry)
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.55.0")
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("vision.salient.cli.WhatsLiberationCliKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

kotlin {
    jvmToolchain(23)
}

val runSingleChatExport by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the WhatsLiberation CLI for the single chat export flow"
    mainClass.set("vision.salient.cli.WhatsLiberationCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("single-chat")
}

val runDryRun by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the WhatsLiberation CLI in dry-run mode"
    mainClass.set("vision.salient.cli.WhatsLiberationCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("single-chat", "--dry-run")
}

val runContactsAuth by tasks.registering(JavaExec::class) {
    group = "utility"
    description = "Launches the OAuth flow to generate a Google Contacts refresh token"
    mainClass.set("vision.salient.tools.ContactsTokenGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath
}


tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(23)) })
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
