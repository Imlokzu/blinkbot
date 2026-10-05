import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "ClaudeBot"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.38.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.3.3")
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.webkit:webkit:1.16.0")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
    }
}

android {
    namespace = "me.waveio.claudebot.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources { publicResClass = true; packageOfResClass = "me.waveio.claudebot.resources" }
