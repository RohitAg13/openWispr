plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voicerewriter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicerewriter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing is driven by environment variables so the keystore never lives in the
    // repo — CI decodes it from a secret and exports these. Locally (vars unset) the release
    // build is simply left unsigned; debug builds use the standard debug keystore as always.
    val releaseKeystore = System.getenv("OPENWISPR_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("OPENWISPR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENWISPR_KEY_ALIAS")
                keyPassword = System.getenv("OPENWISPR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // The on-device LLM loads ggml backend .so files from nativeLibraryDir at
        // runtime, so they must be extracted on install (matches extractNativeLibs=true).
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            // textproc's TextProcessor logs via android.util.Log; return defaults so
            // pure JVM unit tests don't need Robolectric just to no-op logging.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")

    implementation(project(":lib")) // on-device whisper.cpp
    implementation(project(":llm")) // on-device llama.cpp (ARM aichat)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2") // Silero VAD
}
