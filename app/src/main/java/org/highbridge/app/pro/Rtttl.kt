// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * RTTTL ("ring tone text transfer language"), the tune format Meshtastic radios play on their buzzer:
 *   name:d=4,o=5,b=120:e,e,f,8g.,p,c6
 * Each note is [duration]letter[#][.][octave][.]; p is a rest.
 */
object Rtttl {
    data class Note(val freqHz: Double, val ms: Int)
    data class Tune(val name: String, val notes: List<Note>) { val lengthMs get() = notes.sumOf { it.ms } }

    private val SEMITONE = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

    fun parse(text: String): Result<Tune> = runCatching {
        val parts = text.trim().split(':')
        require(parts.size == 3) { "A tune needs three parts separated by colons: name:settings:notes" }
        var d = 4; var o = 6; var b = 63
        parts[1].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { kv ->
            val v = kv.substringAfter('=').toIntOrNull() ?: error("Bad setting \"$kv\"")
            when (kv.substringBefore('=')) { "d" -> d = v; "o" -> o = v; "b" -> b = v }
        }
        require(b in 10..900) { "Tempo b= must be between 10 and 900" }
        val wholeMs = 4 * 60_000.0 / b
        val notes = parts[2].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { raw ->
            val m = Regex("^(\\d{1,2})?([a-gp])(#?)(\\.?)(\\d?)(\\.?)$").find(raw) ?: error("Can't read the note \"$raw\"")
            val dur = m.groupValues[1].toIntOrNull() ?: d
            require(dur in setOf(1, 2, 4, 8, 16, 32, 64)) { "Note length $dur isn't 1, 2, 4, 8, 16 or 32" }
            var ms = wholeMs / dur
            if (m.groupValues[4] == "." || m.groupValues[6] == ".") ms *= 1.5
            val letter = m.groupValues[2][0]
            val freq = if (letter == 'p') 0.0 else {
                val octave = m.groupValues[5].toIntOrNull() ?: o
                val semis = SEMITONE.getValue(letter) + (if (m.groupValues[3] == "#") 1 else 0) + (octave - 4) * 12 - 9   // relative to A4
                440.0 * 2.0.pow(semis / 12.0)
            }
            Note(freq, ms.toInt())
        }
        require(notes.isNotEmpty()) { "No notes" }
        Tune(parts[0].trim().ifEmpty { "Tune" }, notes)
    }

    /** 16-bit mono samples: a soft square-ish tone with tiny fades so notes don't click. */
    fun render(t: Tune, rate: Int = 22_050): ShortArray {
        val out = ShortArray(t.notes.sumOf { it.ms * rate / 1000 })
        var i = 0
        for (n in t.notes) {
            val count = n.ms * rate / 1000
            val fade = minOf(count / 6, rate / 200)
            for (k in 0 until count) {
                val env = when { fade == 0 -> 1.0; k < fade -> k.toDouble() / fade; k > count - fade -> (count - k).toDouble() / fade; else -> 1.0 }
                // Notes are separated slightly, like a buzzer, by silencing the last 8%.
                val gate = if (k > count * 0.92) 0.0 else 1.0
                val s = if (n.freqHz == 0.0) 0.0 else {
                    val ph = 2 * PI * n.freqHz * k / rate
                    sin(ph) + sin(3 * ph) / 3 + sin(5 * ph) / 5   // first three odd harmonics: buzzer-like but not harsh
                }
                out[i++] = (s * env * gate * 9000).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    /** Plays a tune on the phone and returns when it has finished. */
    suspend fun play(text: String, usage: Int = AudioAttributes.USAGE_MEDIA): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val pcm = render(parse(text).getOrThrow())
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(22_050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            try {
                track.write(pcm, 0, pcm.size); track.play()
                delay(pcm.size * 1000L / 22_050 + 100)
            } finally { track.release() }
        }
    }

    /**
     * Built-in tunes: public-domain melodies (all written before 1929) and simple alerts, transcribed for Highbridge.
     * Each fits in the 230 characters a Meshtastic radio stores.
     */
    val LIBRARY: List<Pair<String, String>> = listOf(
        "Beep" to "Beep:d=8,o=6,b=200:c",
        "Double beep" to "DoubleBeep:d=16,o=6,b=200:c,p,c",
        "Chirp" to "Chirp:d=32,o=6,b=200:c,e,g,c7",
        "Rising alert" to "Rising:d=16,o=5,b=180:c,e,g,c6,e6,g6,8c7",
        "Morse 73 (best regards)" to "Morse73:d=16,o=6,b=180:4a,4a,a,a,a,8p,a,a,a,4a,4a",
        "Morse CQ" to "MorseCQ:d=16,o=6,b=180:4a,a,4a,a,8p,4a,4a,a,4a",
        "Ode to Joy (Beethoven)" to "OdeToJoy:d=4,o=5,b=120:e,e,f,g,g,f,e,d,c,c,d,e,e.,8d,2d,e,e,f,g,g,f,e,d,c,c,d,e,d.,8c,2c",
        "Für Elise (Beethoven)" to "FurElise:d=8,o=5,b=125:e6,d#6,e6,d#6,e6,b,d6,c6,4a,p,c,e,a,4b,p,e,g#,b,4c6,p,e,e6,d#6,e6,d#6,e6,b,d6,c6,4a,p,c,e,a,4b,p,e,c6,b,2a",
        "In the Hall of the Mountain King (Grieg)" to "MountainKing:d=8,o=5,b=140:a4,b4,c,d,e,c,4e,d#,b4,4d#,d,a#4,4d,a4,b4,c,d,e,c,e,a,g,e,c,e,2g",
        "Morning Mood (Grieg)" to "MorningMood:d=8,o=5,b=100:g,e,d,c,d,e,g,e,d,c,d,e,d,e,g,e,g,a,e,a,g,e,d,4c",
        "William Tell Overture (Rossini)" to "WilliamTell:d=16,o=5,b=140:e,e,8e,e,e,8e,e,e,a,8b,c6,8p,e,e,8e,e,e,8e,e,a,c6,8b,8g,8p,e,e,8e,e,e,8e,e,e,a,8b,c6,8p,a,c6,8e6,d6,c6,8b,8a",
        "Minuet in G (Petzold)" to "MinuetG:d=4,o=5,b=140:d6,8g,8a,8b,8c6,d6,g,g,e6,8c6,8d6,8e6,8f#6,g6,g,g,c6,8d6,8c6,8b,8a,b,8c6,8b,8a,8g,f#,8g,8a,8b,8g,2a.",
        "Canon in D (Pachelbel)" to "Canon:d=4,o=5,b=80:f#6,e6,d6,c#6,b,a,b,c#6,d6,c#6,b,a,g,f#,g,e",
        "Eine kleine Nachtmusik (Mozart)" to "Nachtmusik:d=8,o=5,b=140:4g,p,d,4g,p,d,g,d,g,b,4d6,p,4c6,p,a,4c6,p,a,c6,a,f#,a,4d",
        "Can-can (Offenbach)" to "CanCan:d=8,o=5,b=180:4c,4p,d,f,e,d,4g,4g,4g,a,e,f,d,4g,4g,4g,a,e,f,d,g,c6,b,a,g,f,e,d,c",
        "The Entertainer (Joplin)" to "Entertainer:d=8,o=5,b=140:d,d#,e,4c6,e,4c6,e,2c6.,c6,d6,d#6,e6,c6,d6,4e6,b,4d6,2c6",
        "Lullaby (Brahms)" to "Lullaby:d=4,o=5,b=100:8e,8e,g.,8e,8e,g,8e,8g,c6,b.,8a,a,g,8d,8e,f,d,8d,8e,f.,8d,8f,b,8a,8g,b,c6",
        "Jingle Bells" to "JingleBells:d=4,o=5,b=170:e,e,2e,e,e,2e,e,g,c.,8d,1e,f,f,f.,8f,f,e,e,8e,8e,e,d,d,e,2d,2g",
        "Twinkle, Twinkle, Little Star" to "Twinkle:d=4,o=5,b=120:c,c,g,g,a,a,2g,f,f,e,e,d,d,2c",
        "Frère Jacques" to "FrereJacques:d=4,o=5,b=120:c,d,e,c,c,d,e,c,e,f,2g,e,f,2g",
        "Oh! Susanna (Foster)" to "OhSusanna:d=8,o=5,b=150:c,d,4e,4g,4g.,a,4g,4e,4c.,d,4e,4e,4d,4c,2d.,c,d,4e,4g,4g.,a,4g,4e,4c.,d,4e,4e,4d,4d,2c.",
        "Yankee Doodle" to "YankeeDoodle:d=8,o=5,b=140:c,c,d,e,c,e,d,4g4,c,c,d,e,4c,4b4,c,c,d,e,f,e,d,c,b4,g4,a4,b4,4c,4c",
        "Good Morning to All (Hill sisters)" to "GoodMorning:d=4,o=5,b=110:8c.,16c,d,c,f,2e,8c.,16c,d,c,g,2f,8c.,16c,c6,a,f,e,d,8a#.,16a#,a,f,g,2f",
    )
}
