pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vendored sherpa-onnx AAR (on-device Parakeet/Moonshine STT). No official Maven
        // artifact exists; the prebuilt static-link AAR is dropped in app/libs. See
        // app/libs/REGENERATE.md.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "OpenWispr"
include(":app")
include(":lib")
include(":llm")
