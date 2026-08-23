plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

group = "com.example.hammerdroid"
version = "1.0.0"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.fazecast:jSerialComm:2.11.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.hammerdroid.desktop.MainKt")
    applicationName = "hammerdroid-linux"
}
