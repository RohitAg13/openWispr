// AGP 8.9 is the first release tested against compileSdk 36; 8.7.3 built it but warned it was
// untested, which is not a thing to ship a Play release on. 8.9 in turn needs Gradle 8.11.1
// (see gradle/wrapper/gradle-wrapper.properties).
plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.library") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
