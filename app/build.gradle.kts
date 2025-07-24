plugins {
    id("com.android.application")
    id("com.google.gms.google-services") // 🔥 Firebase
}

android {
    namespace = "com.example.appbt"
    compileSdk = 34 // Usa 34 por ahora (35 aún no es estable al 100%)

    defaultConfig {
        applicationId = "com.example.appbt"
        minSdk = 24 // Android 7.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Solo si usas Kotlin
    // kotlinOptions {
    //     jvmTarget = "11"
    // }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.media3.common)
    implementation(libs.play.services.location)
    implementation(libs.recyclerview)

    // ✅ Firebase BoM - solo una vez
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))

    // Firebase Analytics, Auth y Firestore (sin versión individual, la maneja BoM)
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
