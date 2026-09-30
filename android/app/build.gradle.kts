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
        versionCode = 44
        versionName = "0.29.0"
    }

    buildTypes {
        // The build to install: compiled ahead of time on the phone, so pages open at full
        // speed the first time. Signed with the same local key as debug builds, so it installs
        // over them and keeps all the data on the phone.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
