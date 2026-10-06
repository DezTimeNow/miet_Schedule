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
        // versionCode ВСЕГДА растёт. При повторном versionCode Android считает
        // все сборки одной и той же версией: `adb install -r` может молча
        // оставить старую, а обновление из магазина не предложат вовсе.
        // Из-за этого «поставил новый APK — баг остался». Нумерация по
        // сборкам обязана быть в versionCode, а не только в имени файла.
        //
        // Соглашение о номерах: сборка N соответствует релизу 0.N. Номер
        // после точки в теге GitHub обязан совпадать с versionCode, иначе
        // UpdateChecker перестаёт видеть обновления.
        versionCode = 67
        // Пользователю показываем семантическую строку: пре-релиз помечен
        // суффиксом -alpha, как это делают в сторах. «О программе» выводит
        // её вместе с номером сборки.
        versionName = "0.67.0-alpha"
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
    // Аналитика Appmetrica: считает установки (через install referrer),
    // активных пользователей и события. Без Google Play Services.
    implementation("io.appmetrica.analytics:analytics:8.6.0")


    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Сеть идёт через OkHttp напрямую (MietApi использует newCall + FormBody):
    // у запросов к miet.ru свой User-Agent и формат application/x-www-form-urlencoded,
    // а Retrofit поверх них всё равно не использовался. Зависимости на него и на
    // converter-gson удалены как мёртвые — в коде не было ни одного обращения.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Фоновая актуализация расписания. Без неё данные обновлялись только
    // при открытии приложения или нажатии «Обновить всё»: пользователь,
    // запускавший его раз в день, месяцами видел старый кэш.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
