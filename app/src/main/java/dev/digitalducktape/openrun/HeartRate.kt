package dev.digitalducktape.openrun

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class HeartRate(private val context: Context, private val scope: CoroutineScope) {
    data class Device(val address: String, val name: String)
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val bpm = MutableStateFlow<Int?>(null)
    val status = MutableStateFlow("No chest strap paired")
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var lastReading = 0L
    private var attemptAt = 0L
    private var generation = 0
    private val service = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val measurement = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val descriptorId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    init { scope.launch { while (isActive) { delay(1000); if (bpm.value != null && SystemClock.elapsedRealtime()-lastReading > 5000) { bpm.value = null; status.value = "HR disconnected · holding settings" }; if (address != null && bpm.value == null && SystemClock.elapsedRealtime()-attemptAt > 15000) connect(address) } } }
    private val scanner = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val d = Device(result.device.address, result.scanRecord?.deviceName ?: result.device.name ?: "Heart-rate strap")
            devices.value = (devices.value.filterNot { it.address == d.address } + d).sortedBy { it.name }
        }
        override fun onScanFailed(errorCode: Int) { status.value = "Bluetooth scan failed ($errorCode)" }
    }
    fun scan() {
        devices.value = emptyList()
        try {
            if (adapter?.isEnabled != true) { status.value = "Enable Bluetooth in Android settings"; return }
            adapter.bluetoothLeScanner.stopScan(scanner)
            adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanner)
            status.value = "Searching for chest straps…"
            scope.launch { delay(12000); runCatching { adapter?.bluetoothLeScanner?.stopScan(scanner) }; if (devices.value.isEmpty()) status.value = "No straps found · wear strap and enable Android Location" }
        } catch (_: SecurityException) { status.value = "Bluetooth permission required" }
    }
    fun connect(next: String?) {
        generation++; val token = generation
        runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null
        address = next; bpm.value = null; lastReading = 0; attemptAt = SystemClock.elapsedRealtime()
        if (next == null) { status.value = "No chest strap paired"; return }
        status.value = "Connecting chest strap…"
        try {
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
                    if (token != generation) return
                    if (state == BluetoothProfile.STATE_CONNECTED && code == 0) g.discoverServices()
                    else if (state == BluetoothProfile.STATE_DISCONNECTED) { bpm.value = null; status.value = "HR disconnected · holding settings"; g.close(); gatt = null }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
                    if (token != generation || code != 0) return
                    val c = g.getService(service)?.getCharacteristic(measurement) ?: return
                    g.setCharacteristicNotification(c,true)
                    c.getDescriptor(descriptorId)?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(it) }
                }
                @Deprecated("Android 9 callback") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    if (token != generation || c.uuid != measurement) return
                    val value = decodeHeartRate(c.value ?: return)
                    lastReading = SystemClock.elapsedRealtime(); bpm.value = value
                    status.value = if (value == null) "HR signal unavailable" else "Chest strap connected"
                }
            }
            gatt = adapter?.getRemoteDevice(next)?.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) { status.value = "Unable to connect chest strap" }
    }
}
fun decodeHeartRate(bytes: ByteArray): Int? {
    if (bytes.size < 2) return null
    val f = bytes[0].toInt() and 255
    if (f and 4 != 0 && f and 2 == 0) return null
    val n = if (f and 1 != 0) { if (bytes.size < 3) return null; (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8) } else bytes[1].toInt() and 255
    return n.takeIf { it in 1..255 }
}
