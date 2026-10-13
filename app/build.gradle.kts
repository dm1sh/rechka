import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config is read from keystore.properties at repo root.
// That file is gitignored (see keystore.properties.template for format).
// If the file is missing — release builds fall back to unsigned and print a
// warning, which is fine for CI / source-drop builds on other machines.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

// CI supplies one monotonically increasing version code to both distribution
// variants so users can switch between them by installing either APK.
val ciVersionCode = providers.gradleProperty("versionCode")
    .orNull
    ?.toIntOrNull()

// The large model is downloaded locally/inside CI and is never committed.
// scripts/download-model.sh verifies its SHA-256 before Gradle packages it.
val bundledGigaAmFiles = mapOf(
    "gigaam_v3_e2e_rnnt_encoder_int8.onnx" to 318_995_997L,
    "gigaam_v3_e2e_rnnt_decoder.onnx" to 4_600_058L,
    "gigaam_v3_e2e_rnnt_joint.onnx" to 2_712_896L,
    "gigaam_v3_e2e_rnnt_tokens.txt" to 13_353L,
)
val bundledGigaAmDirectory = rootProject.file("models/gigaam-v3")
val verifyBundledGigaAmModel by tasks.registering {
    inputs.files(bundledGigaAmFiles.keys.map { File(bundledGigaAmDirectory, it) })
    doLast {
        val invalid = bundledGigaAmFiles.filter { (name, expectedSize) ->
            val file = File(bundledGigaAmDirectory, name)
            !file.isFile || file.length() != expectedSize
        }.keys
        if (invalid.isNotEmpty()) {
            throw GradleException(
                "Full APK requires verified GigaAM files in ${bundledGigaAmDirectory.path}. " +
                    "Run `bash scripts/download-model.sh` first. Invalid/missing: ${invalid.joinToString()}"
            )
        }
    }
}

android {
    namespace = "ru.dm1sh.rechka"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.dm1sh.rechka"
        minSdk = 33
        targetSdk = 35
        versionCode = ciVersionCode ?: 21
        versionName = "1.2.0" + (ciVersionCode?.let { "+$it" } ?: "")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "model"
    productFlavors {
        create("light") {
            dimension = "model"
            buildConfigField("boolean", "BUNDLED_MODEL", "false")
        }
        create("full") {
            dimension = "model"
            buildConfigField("boolean", "BUNDLED_MODEL", "true")
        }
    }

    // For the full flavor, the verified source files downloaded by
    // scripts/download-model.sh are packaged at the root of APK assets.
    sourceSets {
        getByName("full") {
            assets.srcDir(bundledGigaAmDirectory)
        }
    }

    signingConfigs {
        // Keep the debug signing key stable across GitHub Actions runners.
        // Android refuses to update an APK signed by a different key even
        // when its application ID and versionCode are higher.
        create("stableDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "govorun-debug"
            keyAlias = "govorun-debug"
            keyPassword = "govorun-debug"
        }

        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Keep the development APK installable next to the store/release app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name.startsWith("mergeFull") && name.endsWith("Assets")) {
        dependsOn(verifyBundledGigaAmModel)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // sherpa-onnx v1.12.34: offline ASR engine used with GigaAM v3.
    // AAR must be present at app/libs/sherpa-onnx.aar — see scripts/download-sherpa-onnx.sh.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
