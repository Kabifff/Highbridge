package org.highbridge.core.protocol.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID

/** A two-way byte stream: a USB serial port or a Bluetooth UART (Nordic UART Service). */
interface StreamLink : Closeable {
    val name: String
    val incoming: SharedFlow<ByteArray>
    val isOpen: StateFlow<Boolean>
    suspend fun open(): Result<Unit>
    suspend fun write(data: ByteArray): Result<Unit>
}

object UsbDevices {
    /** Devices the stock prober misses: ESP32-S2/S3/C3/C6 native USB, Adafruit/Seeed/RAK nRF52 bootloaders. */
    private val extra: ProbeTable = ProbeTable().apply {
        addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // Espressif USB-JTAG-serial (Heltec V4, T-Deck, Station G2)
        addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java) // ESP32-S2/S3 TinyUSB CDC
        addProduct(0x303A, 0x80D1, CdcAcmSerialDriver::class.java)
        addProduct(0x239A, 0x8029, CdcAcmSerialDriver::class.java) // Adafruit nRF52840 (RAK4631, T-Echo, Heltec T114 app)
        addProduct(0x239A, 0x0029, CdcAcmSerialDriver::class.java) // Adafruit nRF52840 bootloader
        addProduct(0x239A, 0x4405, CdcAcmSerialDriver::class.java)
        addProduct(0x2886, 0x8044, CdcAcmSerialDriver::class.java) // Seeed XIAO nRF52840
        addProduct(0x2886, 0x0045, CdcAcmSerialDriver::class.java) // Seeed Wio Tracker L1 / T1000-E
        addProduct(0x1915, 0x520F, CdcAcmSerialDriver::class.java) // Nordic
        addProduct(0x2E8A, 0x000A, CdcAcmSerialDriver::class.java) // RP2040 (RAK11310, Pico)
    }

    fun drivers(usb: UsbManager): List<UsbSerialDriver> {
        val found = UsbSerialProber.getDefaultProber().findAllDrivers(usb).toMutableList()
        val known = found.map { it.device.deviceId }.toSet()
        found += UsbSerialProber(extra).findAllDrivers(usb).filter { it.device.deviceId !in known }
        // Anything else with a CDC data interface is very likely a serial radio too.
        val known2 = found.map { it.device.deviceId }.toSet()
        usb.deviceList.values.filter { it.deviceId !in known2 && hasCdc(it) }.forEach { found += CdcAcmSerialDriver(it) }
        return found
    }

    private fun hasCdc(d: UsbDevice) = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 0x0A }

    fun describe(d: UsbDevice): String = buildString {
        append(d.productName ?: "USB device")
        append(" (%04X:%04X)".format(d.vendorId, d.productId))
    }
}

class UsbSerialLink(
    private val usb: UsbManager,
    val driver: UsbSerialDriver,
    private val baud: Int = 115200,
) : StreamLink {
    override val name: String = UsbDevices.describe(driver.device)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _open = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _open
    var port: UsbSerialPort? = null; private set
    private var io: SerialInputOutputManager? = null

    override suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = usb.openDevice(driver.device) ?: error("No permission for ${name}. Allow USB access and try again.")
            val p = driver.ports.first()
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.dtr = true; p.rts = true }
            port = p
            io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { _incoming.tryEmit(data) }
                override fun onRunError(e: Exception) { _open.value = false }
            }).also { it.start() }
            _open.value = true
        }
    }

    fun setBaud(newBaud: Int) { port?.setParameters(newBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }

    override suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { (port ?: error("Port closed")).write(data, 2000) }
    }

    override fun close() {
        runCatching { io?.stop() }
        runCatching { port?.close() }
        io = null; port = null
        _open.value = false
    }
}

/** Nordic UART Service as a byte stream (MeshCore companion BLE, RNode BLE). */
class BleNusStream(context: Context, device: BluetoothDevice) : StreamLink {
    @SuppressLint("MissingPermission")
    override val name: String = device.name ?: device.address
    val gatt = GattClient(context, device)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)
    override val incoming: SharedFlow<ByteArray> = _incoming
    override val isOpen: StateFlow<Boolean> = gatt.connected
    private var job: Job? = null

    override suspend fun open(): Result<Unit> = runCatching {
        gatt.ensureBonded()
        gatt.connect()
        check(gatt.hasService(SERVICE)) { "This device has no Bluetooth UART service" }
        job = scope.launch { gatt.notifications.collect { if (it.uuid == TX) _incoming.emit(it.value) } }
        gatt.enableNotifications(SERVICE, TX)
    }

    override suspend fun write(data: ByteArray): Result<Unit> = runCatching {
        val chunk = (gatt.mtu - 3).coerceAtLeast(20)
        var i = 0
        while (i < data.size) {
            val end = minOf(data.size, i + chunk)
            gatt.write(SERVICE, RX, data.copyOfRange(i, end))
            i = end
        }
    }

    override fun close() { job?.cancel(); gatt.close(); scope.cancel() }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // phone -> radio
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // radio -> phone
    }
}
