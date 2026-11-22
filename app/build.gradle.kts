import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uk.co.bigirl.sender"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "uk.co.bigirl.sender"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha01"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures { compose = true }

    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")

    // RootEncoder's documented all-protocol library includes RTMP, RTSP, SRT and UDP.
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

val buildNativeTransportsForAndroid by tasks.registering(Exec::class) {
    val nativeSource = layout.projectDirectory.dir("src/main/cpp")
    val nativeBuild = layout.buildDirectory.dir("native-transports-android")
    val nativeOutput = layout.buildDirectory.dir("generated/jniLibs")
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")

    inputs.files(
        nativeSource.file("meson.build"),
        nativeSource.file("native_rist_sender.c"),
        nativeSource.file("native_srt_bonded_sender.c"),
        nativeSource.file("native_srtla_bridge.c"),
        nativeSource.file("srt-android/CMakeLists.txt"),
        layout.projectDirectory.dir("src/main/rust/srtla-android"),
        layout.projectDirectory.file("scripts/build-native-android.py"),
        layout.projectDirectory.file("scripts/patch-srt-android.py"),
    )
    outputs.dir(nativeOutput)
    doFirst {
        val ndk = ndkHome.orNull
            ?: error("ANDROID_NDK_HOME must point to Android NDK 27.2.12479018")
        commandLine(
            "python3",
            layout.projectDirectory.file("scripts/build-native-android.py").asFile.absolutePath,
            "--ndk",
            ndk,
            "--source",
            nativeSource.asFile.absolutePath,
            "--build",
            nativeBuild.get().asFile.absolutePath,
            "--output",
            nativeOutput.get().asFile.absolutePath,
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildNativeTransportsForAndroid)
}
