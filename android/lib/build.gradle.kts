plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        ndk {
            // The device is arm64; keep the native build small/fast.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Without this, the debug APK builds whisper.cpp in Debug (no -O3),
                // which makes transcription several times slower.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
        }
    }

    ndkVersion = "25.1.8937393"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
