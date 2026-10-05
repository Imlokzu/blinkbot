plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "me.waveio.claudebot"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.waveio.claudebot"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // This owner-distributed build updates earlier development installs.
            // Store distribution must use the owner's production signing setup.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("benchmark") {
            initWith(getByName("release"))
            // The external Compose fixture needs APIs that R8 legitimately
            // removes from the shipped app. Benchmark without a debugger,
            // retaining those APIs; smoke the optimized release separately.
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = providers.gradleProperty("uiTestBuildType").getOrElse("debug")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Keep app/test runtime versions aligned for optimized instrumentation.
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    // Compose Multiplatform 1.10.3 resolves Android UI artifacts to this version.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.5")
    androidTestImplementation("androidx.compose.foundation:foundation-layout:1.10.5")
    androidTestImplementation("io.ktor:ktor-client-mock:3.3.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.5")
    if (providers.gradleProperty("uiTestBuildType").orNull == "benchmark") {
        add("benchmarkImplementation", "androidx.compose.ui:ui-test-manifest:1.10.5")
    }
}
