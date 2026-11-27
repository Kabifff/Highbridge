// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing. The key never goes in git: put its details in keystore.properties (git-ignored,
// see keystore.properties.example) or in HB_KEYSTORE / HB_KEYSTORE_PASSWORD / HB_KEY_ALIAS /
// HB_KEY_PASSWORD environment variables. Without them, release builds come out unsigned (F-Droid
// signs its own builds).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val releaseKeystore = secret("storeFile", "HB_KEYSTORE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "org.highbridge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stpaulmesh.highbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = secret("storePassword", "HB_KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "HB_KEY_ALIAS")
            keyPassword = secret("keyPassword", "HB_KEY_PASSWORD")
        }
    }

    // Two editions from one codebase:
    //  full: Meshtastic, MeshCore and Reticulum (Python runtime; RNS downloaded with consent). GitHub releases.
    //  lite: Meshtastic and MeshCore only, with no Python and no downloaded code. Google Play and F-Droid.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") { dimension = "edition" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // F-Droid rejects the encrypted dependency block in APKs; Play may keep it in bundles.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") } }
    // Room schema files let the migration test rebuild old database versions.
    // (Robolectric reads assets from the app variant, so they ride along in debug builds only.)
    sourceSets { getByName("debug").assets.srcDir("$projectDir/schemas") }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Store screenshots: ./gradlew :app:testLiteDebugUnitTest --tests '*StoreScreenshots*' -Phb.shots
        unitTests.all { it.systemProperty("hb.shots", if (project.hasProperty("hb.shots")) "on" else "off"); it.maxHeapSize = "2g" }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-media"))
    implementation(project(":feature-bridge"))
    implementation(project(":protocol-meshtastic"))
    implementation(project(":protocol-meshcore"))
    implementation(project(":protocol-reticulum"))
    if (findProject(":rns-python") != null) "fullImplementation"(project(":rns-python"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("no.nordicsemi.android:dfu:2.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android.lint {
    abortOnError = false
    checkReleaseBuilds = false
    disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion")
}
