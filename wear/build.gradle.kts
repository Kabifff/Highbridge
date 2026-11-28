// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
//
// Highbridge for Wear OS: recent chats, quick and voice replies, check-in and SOS, through the phone.
// It uses Google Play services' Wearable Data Layer, so it is built only for Google Play
// (F-Droid's recipe removes this folder). Same application ID and signing key as the phone app.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val releaseKeystore = secret("storeFile", "HB_KEYSTORE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "org.highbridge.wear"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.stpaulmesh.highbridge"
        minSdk = 30
        targetSdk = 36
        // Wear builds use their own version codes, above the phone's, as Google Play requires for one app ID.
        versionCode = 1_000_000 + 11
        versionName = "0.6.0"
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = secret("storePassword", "HB_KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "HB_KEY_ALIAS")
            keyPassword = secret("keyPassword", "HB_KEY_PASSWORD")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
