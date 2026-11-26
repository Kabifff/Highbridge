// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.squareup.wire")
}

android {
    namespace = "org.highbridge.protocol.meshtastic"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api(project(":core-protocol"))
    api("com.squareup.wire:wire-runtime:5.3.11")
    testImplementation("junit:junit:4.13.2")
}

wire {
    kotlin {
        android = true
    }
}
