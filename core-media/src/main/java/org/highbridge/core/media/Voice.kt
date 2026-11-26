// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** Records 8 kHz mono PCM for Codec2 and plays decoded voice notes back. */
object VoiceIO {
    const val SAMPLE_RATE = 8000
    /** Longest voice note: 20 s at 1200 bps is 3 KB, about 22 LoRa frames. */
    const val MAX_SECONDS = 20

    @SuppressLint("MissingPermission")
    suspend fun record(stopWhen: () -> Boolean, onLevel: (Float) -> Unit = {}): ShortArray = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone not available" }
        val out = ArrayList<Short>(SAMPLE_RATE * MAX_SECONDS)
        val buf = ShortArray(320)
        rec.startRecording()
        try {
            while (coroutineContext.isActive && !stopWhen() && out.size < SAMPLE_RATE * MAX_SECONDS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) { out.add(buf[i]); peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
                onLevel(peak / 32768f)
            }
        } finally { rec.stop(); rec.release() }
        out.toShortArray()
    }

    suspend fun play(pcm: ShortArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        kotlinx.coroutines.delay(pcm.size * 1000L / SAMPLE_RATE + 200)
        track.release()
    }

    /** Small header so the receiver knows which Codec2 mode was used: [mode] + codec2 bytes. */
    fun pack(mode: Int, encoded: ByteArray): ByteArray = byteArrayOf(mode.toByte()) + encoded
    fun unpack(data: ByteArray): Pair<Int, ByteArray> = (data.firstOrNull()?.toInt() ?: NativeCodec2.MODE_1200) to data.copyOfRange(minOf(1, data.size), data.size)

    /** Writes 16-bit mono PCM as a .wav so a voice note can be saved or shared. */
    fun toWav(pcm: ShortArray): ByteArray {
        val data = ByteArrayOutputStream()
        pcm.forEach { data.write(it.toInt() and 0xFF); data.write((it.toInt() shr 8) and 0xFF) }
        val d = data.toByteArray()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + d.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(d.size)
        return h.array() + d
    }
}
