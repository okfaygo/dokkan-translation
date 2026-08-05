plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment so the keystore and its
// passwords never live in the repo. Unset locally, which is the point:
// debug builds keep working with the local debug key and nothing here
// changes for day-to-day development.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "dev.fogo.dokkantranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fogo.dokkantranslate"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so each build outranks the last;
        // Android refuses to install an APK whose versionCode went backwards.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.4"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: R8 would need keep rules for ML Kit and
            // the reflective JSON paths, and a mis-shrunk release is a
            // runtime failure rather than a build error. Size is not a
            // problem for a sideloaded personal app.
            isMinifyEnabled = false
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // hosting Compose inside a WindowManager overlay needs these owners
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device Japanese OCR; bundled model (~15MB) so it works offline
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
