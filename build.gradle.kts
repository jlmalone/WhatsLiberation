plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "vision.salient"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("vision.salient.adb.HelloWorld")
}


dependencies {
    testImplementation(kotlin("test"))
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation(kotlin("stdlib"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}