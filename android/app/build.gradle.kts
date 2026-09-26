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
        versionCode = 22
        versionName = "0.14.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
