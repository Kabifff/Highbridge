// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.mik3y") } }
    }
}
rootProject.name = "Highbridge"
include(
    ":app", ":core-protocol", ":core-media", ":feature-bridge",
    ":protocol-meshtastic", ":protocol-meshcore", ":protocol-reticulum"
)
// The Python/Reticulum module is only for the full edition. Builders of the store edition
// (F-Droid) pass -Phb.liteOnly=true so no Python tooling is configured at all.
if (providers.gradleProperty("hb.liteOnly").orNull != "true") include(":rns-python")
