plugins {
    id("com.android.application")
}

android {
    namespace = "com.planj.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.planj.phone"
        minSdk = 28
        targetSdk = 36
        versionCode = 34
        versionName = "0.23.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
