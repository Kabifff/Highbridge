// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.media

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable

object LoRaImageCompressor {
    /**
     * Shrinks a photo until it fits [targetMaxBytes]. The old version stopped at quality 10 and
     * returned whatever size it had, so big photos could still be too large to send. Now it
     * keeps lowering the resolution as well.
     */
    fun compressForLoRa(source: Bitmap, maxDimensionPx: Int = 160, targetMaxBytes: Int = 3500): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                     else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        var dim = maxDimensionPx
        var best = ByteArray(0)
        while (dim >= 48) {
            val scale = minOf(dim.toFloat() / source.width, dim.toFloat() / source.height, 1f)
            val w = (source.width * scale).toInt().coerceAtLeast(16)
            val h = (source.height * scale).toInt().coerceAtLeast(16)
            val scaled = Bitmap.createScaledBitmap(source, w, h, true)
            try {
                var quality = 55
                while (quality >= 10) {
                    val out = ByteArrayOutputStream()
                    scaled.compress(format, quality, out)
                    best = out.toByteArray()
                    if (best.size <= targetMaxBytes) return best
                    quality -= 10
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
            dim = (dim * 0.8f).toInt()
        }
        return best // smallest we could make; caller should check the size before sending
    }
}

interface LoRaVoiceCodec {
    val bitrateBps: Int
    /** Codec2 wants 8 kHz, 16-bit mono PCM. Resample 16 kHz mic audio before calling this. */
    fun encodePcm8k(pcmSamples: ShortArray): ByteArray
    fun decodeToPcm8k(encodedBytes: ByteArray): ShortArray
}

/**
 * Codec2 through JNI. Mode numbers must match codec2.h:
 *   CODEC2_MODE_1300 = 4, CODEC2_MODE_1200 = 5, CODEC2_MODE_700C = 8.
 * The old code used 4 for "1200", which actually selected 1300 bps.
 * Native side: core-media/src/main/cpp (Codec2 LGPL-2.1 sources plus codec2_jni.c).
 */
class NativeCodec2(mode: Int = MODE_1200) : LoRaVoiceCodec, Closeable {
    companion object {
        init { System.loadLibrary("highbridge_codec2") }
        const val MODE_1300 = 4
        const val MODE_1200 = 5
        const val MODE_700C = 8
    }

    private var handle: Long = nativeCreate(mode).also { check(it != 0L) { "Codec2 failed to start" } }
    override val bitrateBps: Int = when (mode) { MODE_700C -> 700; MODE_1300 -> 1300; else -> 1200 }

    @Synchronized override fun encodePcm8k(pcmSamples: ShortArray): ByteArray {
        check(handle != 0L) { "Codec2 already closed" }
        return nativeEncode(handle, pcmSamples)
    }
    @Synchronized override fun decodeToPcm8k(encodedBytes: ByteArray): ShortArray {
        check(handle != 0L) { "Codec2 already closed" }
        return nativeDecode(handle, encodedBytes)
    }
    @Synchronized override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }

    private external fun nativeCreate(mode: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray
    private external fun nativeDecode(handle: Long, encoded: ByteArray): ShortArray
}
