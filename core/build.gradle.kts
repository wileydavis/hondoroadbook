// The navigation engine, shared by the Android app, the routing server, the packer and the iOS app:
// routing, following, cues, GPX, search, the navigation session. Pure Kotlin in commonMain; the few
// platform edges (memory-mapped files, Unicode decomposition) are expect/actual.
plugins { id("org.jetbrains.kotlin.multiplatform"); id("com.android.library") }

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    // the Apple targets compile only on a Mac; elsewhere Gradle skips them with a notice
    val ios = listOf(iosArm64(), iosSimulatorArm64())
    ios.forEach { t -> t.binaries.framework { baseName = "FoldCore"; isStatic = true } }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting
        // the JVM and Android share the ByteBuffer file mapping and java.text.Normalizer
        val jvmCommon by creating { dependsOn(commonMain) }
        val jvmMain by getting { dependsOn(jvmCommon) }
        val androidMain by getting { dependsOn(jvmCommon) }
        val jvmTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

android {
    namespace = "fold.core"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // lint's bundled Kotlin (2.2) cannot read 2.4 metadata and only prints noise for a library with no Android code
    lint { checkReleaseBuilds = false }
}

tasks.withType<Test> {
    useJUnitPlatform(); maxHeapSize = "2g"
    // real-data tests take their datasets from these; without them they report as skipped, not passed
    listOf("FOLD_GRAPH", "FOLD_SEARCH", "FOLD_MT", "FOLD_WY", "FOLD_DATA").forEach { k -> System.getenv(k)?.let { environment(k, it); inputs.property(k, it) } }
}
