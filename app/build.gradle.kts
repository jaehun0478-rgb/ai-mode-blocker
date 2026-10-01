plugins {
    id("com.android.application")
}

android {
    namespace = "kr.school.aimodeblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.school.aimodeblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "2.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
