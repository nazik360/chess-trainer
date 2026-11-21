plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chesstrainer"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.chesstrainer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Only 64-bit ABIs: covers essentially every phone sold since ~2018.
        // Add "armeabi-v7a" here if you need to support very old 32-bit devices
        // (you'd also need to extend app/src/main/cpp/CMakeLists.txt for it).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // c++_static (not c++_shared): stockfish-engine is a real
                // subprocess executable, not a JNI library Android dlopen()s,
                // so there's no benefit to a shared libc++.so and it's one
                // less thing that can go wrong (a missing/mismatched
                // libc++_shared.so alongside it at run time, which shows up
                // as a silent "failed to start the engine process").
                // Statically linking it makes the binary fully self-contained.
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        getByName("debug") {
            // Without this, each machine (and every fresh GitHub Actions
            // runner) auto-generates its OWN debug.keystore the first time
            // it builds - so every CI build before this one was signed with
            // a different, one-off key. Android refuses to install an
            // "update" whose signature doesn't match the already-installed
            // app, so every new APK required uninstalling the old one
            // first. Pointing debug signing at this one committed keystore
            // (committed on purpose - it's just the debug key, not a secret)
            // makes every future build sign identically, so new APKs can
            // install straight over the old one.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    packaging {
        // The compiled Stockfish binary is shipped as a "shared library" purely so
        // Android's packager extracts it into nativeLibraryDir with the exec bit set
        // (required since Android 10's W^X policy). We run it as a subprocess, we
        // never dlopen() it, so doNotStrip just protects the symbols we might want
        // for debugging.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
