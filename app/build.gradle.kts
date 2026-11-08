import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.agent.arena"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agent.arena"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.compose.foundation:foundation:1.10.3")
    implementation("androidx.compose.animation:animation:1.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Liquid Glass (https://github.com/Kyant0/AndroidLiquidGlass)
    implementation("io.github.kyant0:backdrop:1.0.6")
}
