// The build tools' own libraries (pulled in by the Android Gradle plugin), held at versions without known
// vulnerabilities until the plugin itself ships them. They run only while building, never on a phone.
buildscript {
    dependencies {
        constraints {
            classpath("io.netty:netty-bom:4.1.140.Final")
            listOf("codec", "codec-http", "codec-http2", "common", "handler", "handler-proxy", "buffer", "transport", "resolver", "codec-socks")
                .forEach { classpath("io.netty:netty-$it:4.1.140.Final") }
            classpath("org.bouncycastle:bcprov-jdk18on:1.87"); classpath("org.bouncycastle:bcpkix-jdk18on:1.87"); classpath("org.bouncycastle:bcutil-jdk18on:1.87")
            classpath("com.google.guava:guava:33.8.0-jre")
            classpath("org.apache.commons:commons-compress:1.28.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("com.android.library") version "8.13.2" apply false
}
