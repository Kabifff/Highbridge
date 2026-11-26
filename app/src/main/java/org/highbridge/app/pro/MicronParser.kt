// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Lightweight NomadNet Micron (.mu) renderer.
 * Fixes: links are `[label`address] (the old check looked for "`:" and missed them), bold/italic/
 * underline toggles are applied instead of stripped, `F/`B color codes are skipped instead of shown
 * as junk, and the link color comes from the app theme instead of a hard-coded blue.
 * Tap handling: links are tagged "micron" with the address; use ClickableText / LinkAnnotation.
 */
object MicronParser {
    private val LINK = Regex("`\\[([^`\\]]*)`([^\\]]*)]")

    fun parseToAnnotatedString(micronText: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
        micronText.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> Unit
                line.startsWith(">>>") -> withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) { appendLine(line.removePrefix(">>>").trim()) }
                line.startsWith(">>") -> withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) { appendLine(line.removePrefix(">>").trim()) }
                line.startsWith(">") -> withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) { appendLine(line.removePrefix(">").trim()) }
                line.startsWith("-") && line.length <= 2 -> appendLine("────────────────────────")
                else -> { appendInline(line, linkColor); append('\n') }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInline(line: String, linkColor: Color) {
        var bold = false; var italic = false; var underline = false
        var i = 0
        fun style() = SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
        while (i < line.length) {
            val c = line[i]
            if (c != '`' || i + 1 >= line.length) { withStyle(style()) { append(c) }; i++; continue }
            val link = LINK.find(line, i)
            if (link != null && link.range.first == i) {
                val label = link.groupValues[1].ifEmpty { link.groupValues[2] }
                pushStringAnnotation("micron", link.groupValues[2])
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                pop()
                i = link.range.last + 1; continue
            }
            when (line[i + 1]) {
                '!' -> { bold = !bold; i += 2 }
                '*' -> { italic = !italic; i += 2 }
                '_' -> { underline = !underline; i += 2 }
                '`' -> { bold = false; italic = false; underline = false; i += 2 }
                'F', 'B' -> i += if (line.getOrNull(i + 2) == 'T') 9 else 5 // `Fxxx / `FTxxxxxx color: skip
                'f', 'b', 'c', 'l', 'r', 'a' -> i += 2 // reset color / alignment: ignore
                else -> { withStyle(style()) { append(c) }; i++ }
            }
        }
    }
}
