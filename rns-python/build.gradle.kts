// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
//
// The Python runtime and the Reticulum engine glue. Only the "full" app flavor (GitHub releases)
// depends on this module, so the store flavor ships with no Python and no code downloads.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "org.highbridge.rns"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("/usr/bin/python3.12")
        pip {
            // Only BSD-licensed pyserial is bundled. RNS and LXMF (Reticulum License, which adds use
            // restrictions the GPL doesn't allow) are downloaded by the user on first use: see RnsInstaller.
            // RNS falls back to its built-in pure-Python crypto when the "cryptography" package is absent.
            install("pyserial==3.5")
        }
    }
}

dependencies {
    implementation(project(":protocol-reticulum"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
