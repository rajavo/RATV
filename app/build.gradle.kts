plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ratv.app"
    compileSdk = 35

    // رقم البناء على GitHub يزيد مع كل نسخة جديدة.
    val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.ratv.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        vectorDrawables { useSupportLibrary = true }
        // The published APK keeps only the ARM libraries that phones and TV boxes use (half the
        // size); the emulator tests build without this flag and get every ABI.
        if (project.hasProperty("armOnly")) {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    // مفتاح توقيع ثابت حتى تُثبَّت كل نسخة جديدة فوق القديمة دون حذفها.
    // هذا المفتاح للتثبيت الشخصي فقط: أنشئ مفتاحاً خاصاً وسرياً قبل النشر على Google Play.
    signingConfigs {
        create("ratv") {
            storeFile = file("ratv-release.jks")
            storePassword = "kWNpud2bRYzxflZVErXBxIi1"
            keyAlias = "ratv"
            keyPassword = "kWNpud2bRYzxflZVErXBxIi1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("ratv")
        }
        debug {
            signingConfig = signingConfigs.getByName("ratv")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device translation of subtitles into the viewer's language.
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
