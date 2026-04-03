plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "app.bartering.dashboard_admin_compliance"
version = "0.0.1"

application {
    mainClass.set("app.bartering.dashboard_admin_compliance.ApplicationKt")
    applicationName = "admin_compliance"
}

kotlin {
    jvmToolchain(21)
}


dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.2.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.2.3")
    implementation("io.ktor:ktor-server-auth-jvm:3.2.3")
    implementation("io.ktor:ktor-server-sessions-jvm:3.2.3")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.2.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.2.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.2.3")

    implementation("io.ktor:ktor-client-core-jvm:3.2.3")
    implementation("io.ktor:ktor-client-cio-jvm:3.2.3")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.2.3")
    implementation("io.ktor:ktor-client-logging-jvm:3.2.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.2.3")
}

tasks.test {
    useJUnitPlatform()
}

// Compatibility task for IDE Kotlin build script model import.
// Newer Kotlin/Gradle versions may not create this task automatically.
tasks.register("prepareKotlinBuildScriptModel")
