plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shunp.vitmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shunp.vitmobile"
        minSdk = 26
        // targetSdk = 31 (Android 12) で Restricted Settings ガードを回避
        // Android 13+ のサイドロードアプリの Accessibility 有効化制限を無効化する
        targetSdk = 31
        versionCode = 21
        versionName = "0.3.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "vitmobile"
            keyPassword = "android"
        }
        // Release 署名は環境変数から（GitHub Actions で base64 デコードされた release.keystore を期待）
        // ローカルでは ~/.gradle/gradle.properties に同等のキーを置く運用も可
        create("release") {
            val storeFilePath = System.getenv("STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // STORE_FILE が設定されてる時のみ release 署名を使う
            if (System.getenv("STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
