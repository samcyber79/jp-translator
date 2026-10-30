plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.jptranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.jptranslator"
        minSdk = 30          // Z플립3 이후 기종(Android 11+) 전부 포함
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // 개인용 서명 키. 항상 같은 키로 서명되므로 새 버전을 덮어쓰기 설치할 수 있습니다.
    // (Play 스토어에 올릴 거라면 별도의 비공개 키로 바꾸세요)
    signingConfigs {
        create("personal") {
            storeFile = file("personal.jks")
            storePassword = "jptranslator"
            keyAlias = "jptranslator"
            keyPassword = "jptranslator"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // 개인용 빌드: 경고 때문에 빌드가 멈추지 않도록
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // 일본어 OCR (모델이 APK에 포함 → 오프라인 동작)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // 기기 내 번역 (일본어/한국어 모델은 처음 한 번 다운로드)
    implementation("com.google.mlkit:translate:17.0.3")
}
