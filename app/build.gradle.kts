import java.util.Properties
import java.security.MessageDigest

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

// The model is downloaded locally/inside CI and is never committed. Verify
// content as well as size here, including when files were supplied manually.
val bundledGigaAmFiles = mapOf(
    "gigaam_v4_e2e_rnnt_encoder_int8.onnx" to (239_885_452L to "55a182f536b0c61eb1bb67214600967858ba4646fb68fe3bcb70549029a65ad8"),
    "gigaam_v4_e2e_rnnt_decoder.onnx" to (4_600_129L to "f665598c4e271dfe1641106751bf895dc02517c4ff98a35a2c2a7f2b6b990e17"),
    "gigaam_v4_e2e_rnnt_joint.onnx" to (2_712_897L to "0d7888efb5cc6a07d04c4bbe9a5fe87951d16abf687bc11fdfb2046cae753228"),
    "gigaam_v4_e2e_rnnt_tokens.txt" to (9_982L to "4af4f59269ff664ea6a55838b12fa0cb5e07bd5e019202b4d38b3fbc9275e923"),
)
val bundledGigaAmDirectory = rootProject.file("models/gigaam-v4")
val verifyBundledGigaAmModel by tasks.registering {
    inputs.files(bundledGigaAmFiles.keys.map { File(bundledGigaAmDirectory, it) })
    doLast {
        val invalid = bundledGigaAmFiles.filter { (name, expected) ->
            val file = File(bundledGigaAmDirectory, name)
            if (!file.isFile || file.length() != expected.first) {
                true
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } != expected.second
            }
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
        versionName = "1.2.4" + (ciVersionCode?.let { "+$it" } ?: "")
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

    // INT8 model weights compress poorly; store ONNX assets directly to avoid
    // an extra ~240 MB deflate buffer during full-APK builds and speed copying.
    androidResources {
        noCompress += "onnx"
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
    // sherpa-onnx 1.13.0: offline NeMo transducer engine for GigaAM v4.
    // AAR must be present at app/libs/sherpa-onnx.aar — see scripts/download-sherpa-onnx.sh.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    testImplementation("junit:junit:4.13.2")
}
