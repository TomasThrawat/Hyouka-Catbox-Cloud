plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tomasthrawat.hyoukacatbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tomasthrawat.hyoukacatbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"

        val defaultAccountApiKey = System.getenv("HYOUKA_ACCOUNT_API_KEY").orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_ACCOUNT_API_KEY", "\"$defaultAccountApiKey\"")
    }

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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
