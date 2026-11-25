plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val stableKeystoreFile = stableKeystorePath?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.pocketnotify.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketnotify.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.1"
    }

    signingConfigs {
        if (stableKeystoreFile != null) {
            create("stable") {
                storeFile = stableKeystoreFile
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "pocketnotify"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
            if (stableKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.browser:browser:1.8.0")
}
