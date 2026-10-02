plugins {
    id("com.android.application")
}

val stableSigningPassword = System.getenv("AI_BLOCKER_SIGNING_PASSWORD")
val stableKeystorePath = System.getenv("AI_BLOCKER_KEYSTORE_PATH")

android {
    namespace = "kr.school.aimodeblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.school.aimodeblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "3.2.0"
    }

    signingConfigs {
        create("stableRelease") {
            if (!stableSigningPassword.isNullOrBlank() &&
                !stableKeystorePath.isNullOrBlank()) {
                storeFile = file(stableKeystorePath)
                storePassword = stableSigningPassword
                keyAlias = "ai-blocker"
                keyPassword = stableSigningPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableRelease")
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
