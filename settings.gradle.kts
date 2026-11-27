pluginManagement {
    // Kotlin 2.3 metadata needs R8 8.13.19 or newer. AGP 8.7.3's bundled
    // shrinker is older, so override it for both local and CI release builds.
    // https://developer.android.com/build/kotlin-support
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.19")
        }
    }
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
    }
}

rootProject.name = "rechka"
include(":app")
