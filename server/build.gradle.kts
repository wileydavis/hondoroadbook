// The routing service behind hondoroadbook.com/api: the same core the phone runs, as a small HTTP server.
// No framework and no JSON library: the JDK's HttpServer and a few hundred lines. Packaged as one jar
// for the Dockerfile next to this file (a Cloudflare Container), or run anywhere with `java -jar`.
plugins { id("org.jetbrains.kotlin.jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}
application { mainClass.set("fold.server.MainKt") }
tasks.register<Jar>("fatJar") {
    archiveFileName.set("fold-server.jar")
    manifest { attributes["Main-Class"] = "fold.server.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
tasks.test { useJUnitPlatform() }
