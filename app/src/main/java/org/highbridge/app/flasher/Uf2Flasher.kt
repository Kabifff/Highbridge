package org.highbridge.app.flasher

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * nRF52 (RAK4631, T-Echo, Heltec T114, XIAO) firmware updates through the UF2 bootloader:
 * a "1200 baud touch" reboots the board into a USB drive, then the .uf2 file is copied onto it.
 */
object Uf2Flasher {
    data class Uf2Info(val blocks: Int, val familyId: Long?, val bytes: Int)

    /** Checks the UF2 magic numbers on every 512-byte block. */
    fun validate(data: ByteArray): Uf2Info {
        require(data.size >= 512 && data.size % 512 == 0) { "Not a UF2 file (size must be a multiple of 512)" }
        var family: Long? = null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until data.size / 512) {
            val o = i * 512
            require(bb.getInt(o) == 0x0A324655 && bb.getInt(o + 4) == 0x9E5D5157.toInt() && bb.getInt(o + 508) == 0x0AB16F30) { "Block $i isn't valid UF2" }
            val flags = bb.getInt(o + 8)
            if (flags and 0x2000 != 0) family = bb.getInt(o + 28).toLong() and 0xFFFFFFFFL
        }
        return Uf2Info(data.size / 512, family, data.size)
    }

    fun familyName(id: Long?): String = when (id) {
        0xADA52840L -> "nRF52840"
        0x621E937AL -> "nRF52833"
        0xE48BFF56L -> "RP2040"
        0xC47E5767L -> "ESP32-S3"
        0xBFDD4EEEL -> "ESP32-S2"
        null -> "unknown"
        else -> "0x%08X".format(id)
    }

    /** Opens and closes the port at 1200 baud, which tells Adafruit-style bootloaders to show their drive. */
    suspend fun touch1200(usb: UsbManager, driver: UsbSerialDriver) = withContext(Dispatchers.IO) {
        val conn = usb.openDevice(driver.device) ?: error("No USB permission")
        val port = driver.ports.first()
        port.open(conn)
        runCatching { port.setParameters(1200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); port.dtr = false }
        delay(200)
        runCatching { port.close() }
    }

    /** Copies the firmware into the bootloader drive the user picked (Android's folder picker). */
    suspend fun copyToDrive(ctx: Context, treeUri: Uri, fileName: String, data: ByteArray, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        validate(data)
        val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Can't open that drive")
        if (tree.findFile("INFO_UF2.TXT") == null) error("That doesn't look like a UF2 bootloader drive (no INFO_UF2.TXT)")
        val target = tree.createFile("application/octet-stream", fileName.removeSuffix(".uf2")) ?: error("Couldn't write to the drive")
        ctx.contentResolver.openOutputStream(target.uri)!!.use { out ->
            var i = 0
            while (i < data.size) {
                val n = minOf(16 * 1024, data.size - i)
                out.write(data, i, n); i += n
                onProgress(i / data.size.toFloat())
            }
            out.flush()
        }
    }
}
