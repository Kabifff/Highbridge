// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.HbHeader

/** The GPL's "Appropriate Legal Notices" plus every bundled license text, readable offline. */
@Composable
fun LicensesScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val files = remember { ctx.assets.list("licenses")?.sortedWith(compareBy({ it != "THIRD_PARTY_NOTICES.md" }, { it != "GPL-3.0.txt" }, { it })).orEmpty() }
    var open by remember { mutableStateOf<String?>(null) }
    val current = open
    if (current != null) {
        val text = remember(current) { ctx.assets.open("licenses/$current").bufferedReader().use { it.readText() } }
        Column(Modifier.fillMaxSize()) {
            HbHeader(current.removeSuffix(".txt").removeSuffix(".md"), onBack = { open = null })
            Text(text, Modifier.verticalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(tr("Open-source licenses"), tr("Highbridge is free software under GPL-3.0-or-later"), onBack = { nav.popBackStack() }) }
        item {
            Text(LEGAL_NOTICE, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        }
        items(files) { f ->
            Text(when (f) {
                "THIRD_PARTY_NOTICES.md" -> "All components and their licenses"
                "GPL-3.0.txt" -> "GNU General Public License v3 (Highbridge)"
                else -> f.removeSuffix(".txt")
            }, Modifier.fillMaxWidth().clickable { open = f }.padding(horizontal = 20.dp, vertical = 14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

const val SOURCE_URL = "https://github.com/Kabifff/Highbridge"
const val LEGAL_NOTICE = "Highbridge  Copyright (C) 2026 Kabifff and the Highbridge contributors.\n\n" +
    "This program comes with ABSOLUTELY NO WARRANTY. It is free software, and you are welcome to " +
    "redistribute it under the terms of the GNU General Public License, version 3 or any later version.\n\n" +
    "Source code: $SOURCE_URL"
