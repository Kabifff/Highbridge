package org.highbridge.app.pro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** A 700 Hz sidetone for the straight key, plus timing-based decoding of what was keyed. */
class CwKeyer(private val toneHz: Double = 700.0) {
    private val rate = 44_100
    private val track: AudioTrack
    init {
        val cycle = (rate / toneHz).toInt() * 20
        val samples = ShortArray(cycle) { i ->
            (sin(2 * PI * toneHz * i / rate) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        track.write(samples, 0, samples.size)
        track.setLoopPoints(0, samples.size, -1)
    }
    fun keyDown() { runCatching { track.stop(); track.reloadStaticData(); track.play() } }
    fun keyUp() { runCatching { track.pause() } }
    fun release() { runCatching { track.release() } }

    /** Plays a text as Morse at [wpm] words per minute (PARIS timing). */
    suspend fun playText(text: String, wpm: Int = 15) {
        val unit = 1200L / wpm
        for (ch in MorseCodec.encode(text)) {
            when (ch) {
                '.' -> { keyDown(); kotlinx.coroutines.delay(unit); keyUp(); kotlinx.coroutines.delay(unit) }
                '-' -> { keyDown(); kotlinx.coroutines.delay(unit * 3); keyUp(); kotlinx.coroutines.delay(unit) }
                ' ' -> kotlinx.coroutines.delay(unit * 2)
                '/' -> kotlinx.coroutines.delay(unit * 4)
            }
        }
    }
}

/** Turns key-down / key-up durations into dots, dashes and gaps, adapting to the operator's speed. */
class CwTimingDecoder {
    private val sb = StringBuilder()
    private var dotMs = 100.0

    fun onTone(durationMs: Long) {
        if (durationMs < dotMs * 2) { sb.append('.'); dotMs = dotMs * 0.8 + durationMs * 0.2 }
        else { sb.append('-'); dotMs = dotMs * 0.8 + (durationMs / 3.0) * 0.2 }
    }

    fun onGap(durationMs: Long) {
        when {
            durationMs > dotMs * 5 -> if (!sb.endsWith(" / ")) sb.append(" / ")
            durationMs > dotMs * 2 -> if (!sb.endsWith(" ")) sb.append(' ')
        }
    }

    val morse: String get() = sb.toString().trim()
    val text: String get() = MorseCodec.decode(morse.replace(" / ", " / "))
    fun clear() { sb.setLength(0) }
}
