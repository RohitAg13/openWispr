plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 36

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
                // NDK r25 links shared objects with 4 KB ELF segment alignment, so libwhisper*.so
                // would only run in Android 16's 16 KB page compatibility mode — and Play rejects
                // unaligned native code. r27+ aligns to 16 KB by default (the :llm module is on
                // r29 and already complies); this gets the same result without moving whisper.cpp
                // onto a newer NDK, which it has not been tested against.
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
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
