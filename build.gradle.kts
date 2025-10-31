plugins {
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.github.rei0925.mfweb"
version = "1.0.2"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    testImplementation(kotlin("test"))
    compileOnly("com.github.Rei0925:MaguFinance:1.0.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.10.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}