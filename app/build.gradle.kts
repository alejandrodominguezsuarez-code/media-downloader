plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ejemplo.mediadownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ejemplo.mediadownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("com.github.yausername.youtubedl-android:library:0.14.0")
    implementation("com.github.yausername.youtubedl-android:ffmpeg:0.14.0")
}
