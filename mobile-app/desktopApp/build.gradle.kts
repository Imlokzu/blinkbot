import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import groovy.json.JsonSlurper

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

val runtimeHome = providers.gradleProperty("blinkDesktopJavaHome")
    .orElse(providers.environmentVariable("BLINK_DESKTOP_JAVA_HOME"))
    .orElse(rootProject.layout.buildDirectory.dir("desktop-runtime/jbrsdk_jcef-25.0.4.1-osx-aarch64-b635.70/Contents/Home").map { it.asFile.absolutePath })

val prepareNativeHelper by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.file("native-resources/common/blink-keychain-helper")
    inputs.file("native/KeychainHelper.swift")
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("swiftc", "-O", "-target", "arm64-apple-macos13.0", "native/KeychainHelper.swift", "-o", output.get().asFile.absolutePath)
}
tasks.named("compileKotlin") { dependsOn(prepareNativeHelper) }
tasks.withType<AbstractJvmToolOperationTask>().configureEach {
    dependsOn(":shared:prepareDesktopRuntime", prepareNativeHelper)
}
tasks.matching { it.name == "checkRuntime" }.configureEach { dependsOn(":shared:prepareDesktopRuntime") }

val prepareShareHelper by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.file("native-resources/common/blink-share-helper")
    inputs.file("native/ShareHelper.swift")
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("swiftc", "-O", "-target", "arm64-apple-macos13.0", "native/ShareHelper.swift", "-o", output.get().asFile.absolutePath)
}
tasks.named("compileKotlin") { dependsOn(prepareShareHelper) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareNativeHelper, prepareShareHelper) }

val prepareMacosBundle by tasks.registering {
    dependsOn("createDistributable", ":shared:prepareDesktopRuntime", prepareNativeHelper, prepareShareHelper)
    doLast {
        val bundle = layout.buildDirectory.dir("compose/binaries/main/app/Blink.app").get().asFile
        check(bundle.isDirectory)
        copy {
            from(file(runtimeHome.get()).parentFile.resolve("Frameworks"))
            into(bundle.resolve("Contents/runtime/Contents/Frameworks"))
        }
        listOf("en", "uk").forEach { language ->
            val catalog = JsonSlurper().parse(rootProject.file("shared/src/commonMain/composeResources/files/locales/$language.json")) as Map<*, *>
            val value = requireNotNull(catalog["desktop.microphoneUsage"]).toString()
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val directory = bundle.resolve("Contents/Resources/$language.lproj").apply { mkdirs() }
            directory.resolve("InfoPlist.strings").writeText("\"NSMicrophoneUsageDescription\" = \"$value\";\n")
        }

        listOf("blink-keychain-helper", "blink-share-helper").forEach { name ->
            check(bundle.resolve("Contents/app/resources/$name").setExecutable(true, true))
        }
    }
}

tasks.matching { it.name == "packageDmg" }.configureEach { dependsOn(prepareMacosBundle) }

compose.desktop {
    application {
        mainClass = "me.waveio.claudebot.desktop.MainKt"
        javaHome = runtimeHome.get()
        jvmArgs("--add-modules=jcef", "--add-exports=jcef/org.cef=ALL-UNNAMED",
            "--enable-native-access=jcef,ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            targetFormats(TargetFormat.Dmg)
            packageName = "Blink"
            packageVersion = "1.0.0"
            description = "Blink conversations and file workspace"
            vendor = "Imlokzu"
            modules("java.desktop", "java.net.http", "java.management", "jdk.crypto.ec", "jdk.unsupported", "jdk.httpserver", "jcef")
            macOS {
                minimumSystemVersion = "13.0"
                bundleID = "me.waveio.blink.desktop"
                iconFile.set(rootProject.file("../blink-app/apps/desktop/branding/icon.icns"))
                infoPlist {
                    val strings = JsonSlurper().parse(rootProject.file("shared/src/commonMain/composeResources/files/locales/en.json")) as Map<*, *>
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key><string>${strings["desktop.microphoneUsage"]}</string>
                        <key>CFBundleURLTypes</key><array><dict><key>CFBundleURLSchemes</key><array><string>claudebot</string></array></dict></array>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.matching { it.name == "run" }.configureEach {
    if (this is JavaExec) systemProperty("blink.development.resources", layout.buildDirectory.dir("native-resources/common").get().asFile.absolutePath)
}
