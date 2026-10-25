plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "fold.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "fold.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "product"
    productFlavors {
        create("fold") {
            dimension = "product"
            applicationId = "fold.app"
            resValue("string", "app_name", "Fold")
            buildConfigField("boolean", "ROADBOOK", "false")
            buildConfigField("String", "CATALOG_URL", "\"\"")
        }
        create("roadbook") {
            dimension = "product"
            applicationId = "com.hondogarage.roadbook"
            versionName = "0.1"
            resValue("string", "app_name", "Roadbook")
            buildConfigField("boolean", "ROADBOOK", "true")
            buildConfigField("String", "CATALOG_URL", "\"https://hondoroadbook.com/packs/catalog.json\"")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    packaging { resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
