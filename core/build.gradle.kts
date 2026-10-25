plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }
dependencies {
    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform(); maxHeapSize = "2g"
    // real-data tests take their datasets from these; without them they report as skipped, not passed
    listOf("FOLD_GRAPH", "FOLD_SEARCH", "FOLD_MT", "FOLD_WY", "FOLD_DATA").forEach { k -> System.getenv(k)?.let { environment(k, it); inputs.property(k, it) } }
}
