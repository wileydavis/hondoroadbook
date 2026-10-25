plugins { id("org.jetbrains.kotlin.jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.openstreetmap.osmosis:osmosis-core:0.49.2")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.49.2")
    implementation("org.openstreetmap.osmosis:osmosis-xml:0.49.2")
}
application { mainClass.set("fold.packer.MainKt"); applicationDefaultJvmArgs = listOf("-Xmx6g") }
tasks.register<Jar>("fatJar") {
    archiveFileName.set("fold-packer.jar")
    manifest { attributes["Main-Class"] = "fold.packer.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
