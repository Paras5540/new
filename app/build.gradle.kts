plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.connectdesk.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.connectdesk.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Isolated test build: installs ALONGSIDE the production app (its
            // own applicationId) and flips on the in-app security self-test.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField("boolean", "TEST_MODE", "true")
        }
        release {
            isMinifyEnabled = false
            // Release builds can never run the probe suite: every check in
            // TestMode is gated on this flag.
            buildConfigField("boolean", "TEST_MODE", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // AGP 8 disables BuildConfig generation by default; ApiClient reads
    // BuildConfig.VERSION_NAME, so it must be switched on explicitly.
    buildFeatures {
        buildConfig = true
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
}
