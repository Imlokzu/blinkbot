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
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
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
            implementation("io.github.huarangmeng:latex-renderer:1.5.6")
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
        if (providers.gradleProperty("blinkIosUiFixtures").orNull == "true") {
            iosMain.get().kotlin.srcDir("src/iosUiTestFixtures/kotlin")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-okhttp:3.3.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // Desktop Material3 uses 0.7; retain the Instant ABI used by shared 0.6 code.
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
                compileOnly(files(rootProject.layout.buildDirectory.dir("desktop-runtime/jcef-api/classes")))
            }
        }
        val desktopTest by getting {
            dependencies { implementation(compose.desktop.uiTestJUnit4) }
        }
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

val prepareDesktopRuntime by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("desktopApp/scripts/prepare-runtime.py"))
    outputs.dir(rootProject.layout.buildDirectory.dir("desktop-runtime/jcef-api/classes"))
    outputs.dir(rootProject.layout.buildDirectory.dir("desktop-runtime/jbrsdk_jcef-25.0.4.1-osx-aarch64-b635.70/Contents/Home"))
}
tasks.named("compileKotlinDesktop") { dependsOn(prepareDesktopRuntime) }

tasks.named<Test>("desktopTest") {
    exclude("**/DesktopEditorIntegrationTest*")
}

tasks.register<JavaExec>("desktopBrowserTest") {
    dependsOn(prepareDesktopRuntime, "desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    executable = rootProject.layout.buildDirectory.file("desktop-runtime/jbrsdk_jcef-25.0.4.1-osx-aarch64-b635.70/Contents/Home/bin/java").get().asFile.absolutePath
    mainClass.set("org.junit.runner.JUnitCore")
    args("me.waveio.claudebot.ui.DesktopEditorIntegrationTest")
    jvmArgs("--add-modules=jcef", "--enable-native-access=jcef,ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    systemProperty("blink.nativeBrowserTests", "true")
    systemProperty("blink.browserLog", "/tmp/blink-macos-cef-test.log")
}
