plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

android {
    namespace = "org.highbridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.highbridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("/usr/bin/python3.12")
        pip {
            // RNS falls back to its built-in pure-Python crypto when the "cryptography" package is absent.
            options("--no-deps")
            install("rns==1.6.1")
            install("lxmf==1.3.1")
            install("pyserial==3.5")
        }
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
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20") { isTransitive = false }
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("no.nordicsemi.android:dfu:2.5.0")

    testImplementation("junit:junit:4.13.2")
}

android.lint {
    abortOnError = false
    checkReleaseBuilds = false
    disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion")
}
