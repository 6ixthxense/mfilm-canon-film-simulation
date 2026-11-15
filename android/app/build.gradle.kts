import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// M-Film's version, as the camera build gets it (VERSION at the top of the repository)
val mfilmVersion = File(rootDir, "../VERSION").readText().trim()
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.mfilm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sixthxense.mfilm"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$mfilmVersion-android.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DMFILM_VERSION=$mfilmVersion-android")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // one key for every build, so a new APK installs over the last one. It is in the
    // repository and so not a secret: fine for trying it out, use your own key to publish
    signingConfigs {
        create("shared") {
            storeFile = file("mfilm-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
    androidResources {
        noCompress += listOf("LUT", "BIN")
    }
}

dependencies {
    val camerax = "1.5.3"           // 1.5: RAW (DNG) from ImageCapture
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // the app's own screens: Compose's drawing and layout, without Material (the look is the camera's, ui/)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
}
