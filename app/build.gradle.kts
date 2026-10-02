plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mietschedule.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mietschedule.app"
        minSdk = 24
        targetSdk = 35
        // versionCode ВСЕГДА растёт. При versionCode = 21 Android считает все
        // сборки одной и той же версией: `adb install -r` может молча оставить
        // старую, а обновление из магазина не предложат вовсе. Из-за этого
        // «поставил новый APK — баг остался». Нумерация по сборкам обязана
        // быть в versionCode, а не только в имени файла.
        versionCode = 21
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
        // BuildConfig нужен экрану «О программе» для показа версии и номера
        // сборки. В AGP 8+ он по умолчанию выключен и без этого флага не
        // генерируется — экран падает с «Unresolved reference 'BuildConfig'».
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // переживает перезагрузки и дозапуски, в отличие от AlarmManager/Handler.

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
