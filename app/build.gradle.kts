plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.penkeyboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.penkeyboard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Same key on every build, so new versions install over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("penkeyboard.keystore")
            storePassword = "penkeyboard"
            keyAlias = "penkeyboard"
            keyPassword = "penkeyboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
