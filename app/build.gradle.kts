import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "fold.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hondogarage.roadbook"
        minSdk = 29
        targetSdk = 35
        // every build is a newer version: yyMMddHH, so installing over an older APK is always an update
        val stamp = ZonedDateTime.now(ZoneOffset.UTC)
        versionCode = stamp.format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
        versionName = "0.1." + stamp.format(DateTimeFormatter.ofPattern("yyMMdd.HHmm"))
        ndk { abiFilters += listOf("arm64-v8a") }
        resValue("string", "app_name", "Roadbook")
        buildConfigField("String", "CATALOG_URL", "\"https://hondoroadbook.com/packs/catalog.json\"")
    }
    buildFeatures { buildConfig = true }
    // (Roadbook was one of two flavours until October 2026; the other, Fold — no network, no voice — was
    // retired once Roadbook did everything it did. The Kotlin package stays fold.app; the installed app id
    // has always been com.hondogarage.roadbook, so updates install over older builds.)
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true     // compressed native libs: the voice runtime is 31 MB raw
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    // Roadbook speaks with a bundled Piper voice through sherpa-onnx (offline, arm64 only)
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.7@aar")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // the share code: the camera (SCAN A CODE) and a QR reader; the codes themselves are drawn by the core
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
}

// The voice (Kokoro, Heart) is too big for git: tools/fetch-voice.sh puts it in assets/tts/kokoro before a build.
// A release without it would quietly fall back to the small Piper voice, so refuse to build one.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { check(file("src/main/assets/tts/kokoro/model.int8.onnx").exists()) { "The voice is missing: run tools/fetch-voice.sh first" } }
}
