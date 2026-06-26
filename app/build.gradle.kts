plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val releaseStoreFile = localProperties.getProperty("POMOTICK_RELEASE_STORE_FILE")
val releaseStorePassword = localProperties.getProperty("POMOTICK_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProperties.getProperty("POMOTICK_RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProperties.getProperty("POMOTICK_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.pomotick"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pomotick"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // Restrict resources to Wear OS dimensions
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // === Compose BOM (统一版本管理) ===
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))

    // === Compose for Wear OS ===
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-material3:1.0.0-alpha15")

    // === Compose 基础 ===
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    // === Compose Material 3 (稳定版，用于 Switch / Slider 等组件) ===
    implementation("androidx.compose.material3:material3:1.2.0")

    // === Wear OS ===
    implementation("androidx.wear:wear:1.3.0")

    // === Activity / Lifecycle / ViewModel ===
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // === Room (KSP) ===
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // === DataStore ===
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // === Coroutines ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // === 调试 ===
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === 测试 ===
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // v0.2.1: mockito-kotlin for AlarmManager / Context mock in TimerAlarmSchedulerTest
    // (temp 注释：Gradle 8.2 / AAPT2 在 Windows 上 stableIds.txt 写入不稳定，先注释掉减少 build 复杂度)
    // testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
