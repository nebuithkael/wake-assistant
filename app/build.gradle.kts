plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.wake"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.wake"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自用直接装 debug 包即可；要 release 就自己配签名。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 唤醒词：离线、低功耗，个人免费
    implementation("ai.picovoice:porcupine-android:3.0.1")

    // 语音转文字：离线 Vosk，中文小模型约 42 MB
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // 调 DeepSeek
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
