pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android AAR on GitHub releases, not Maven Central (Roadbook's bundled voice)
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}
rootProject.name = "fold"
include(":core", ":packer", ":server", ":app")
