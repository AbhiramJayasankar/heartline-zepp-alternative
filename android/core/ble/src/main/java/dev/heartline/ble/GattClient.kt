@file:Suppress("DEPRECATION", "MissingPermission")
package dev.heartline.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException
import java.util.UUID

data class GattNotification(val uuid: UUID, val value: ByteArray)

class GattClient(private val context: Context) : AutoCloseable {
    private data class Pending(val kind: String, val uuid: UUID?, val result: CompletableDeferred<ByteArray>)
    @Volatile private var pending: Pending? = null
    @Volatile private var gatt: BluetoothGatt? = null
    private val queue = OperationQueue { close() }
    private val notices = MutableSharedFlow<GattNotification>(extraBufferCapacity = 2048)
    val notifications: SharedFlow<GattNotification> = notices
    val disconnected = CompletableDeferred<Throwable?>()
    var mtu: Int = 23
        private set

    private fun finish(kind: String, uuid: UUID?, status: Int, value: ByteArray = byteArrayOf()) {
        val p = pending ?: return
        if (p.kind != kind || p.uuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) p.result.complete(value.copyOf())
        else p.result.completeExceptionally(IOException("GATT $kind failed: $status"))
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) finish("connect", null, status)
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = IOException("GATT disconnected ($status)")
                pending?.result?.completeExceptionally(error)
                disconnected.complete(error)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish("discover", null, status)
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            finish("mtu", null, status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = finish("read", c.uuid, status, value)
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish("read", c.uuid, status, c.value ?: byteArrayOf())
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish("write", c.uuid, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish("descriptor", d.characteristic.uuid, status)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = notice(c.uuid, value)
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notice(c.uuid, c.value ?: byteArrayOf())
        }
    }
    private fun notice(uuid: UUID, value: ByteArray) {
        if (!notices.tryEmit(GattNotification(uuid, value.copyOf()))) {
            pending?.result?.completeExceptionally(IOException("Notification consumer overflow"))
            close()
        }
    }
    private suspend fun operation(kind: String, uuid: UUID? = null, timeoutMs: Long = 10_000, start: () -> Boolean): ByteArray =
        queue.run(timeoutMs) {
            check(!disconnected.isCompleted) { "GATT is closed" }
            val p = Pending(kind, uuid, CompletableDeferred())
            pending = p
            try {
                check(start()) { "Unable to start GATT $kind" }
                p.result.await()
            } finally { pending = null }
        }

    suspend fun connect(device: BluetoothDevice) {
        operation("connect", timeoutMs = 45_000) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        // Required before service discovery: raw notifications can carry 244 bytes.
        requestMtu(247)
    }
    suspend fun discoverServices() { operation("discover") { requireGatt().discoverServices() } }
    suspend fun requestMtu(value: Int): Int {
        operation("mtu") { requireGatt().requestMtu(value) }
        return mtu
    }
    suspend fun connectionPriority(priority: Int = BluetoothGatt.CONNECTION_PRIORITY_HIGH) = queue.run(5_000) {
        check(requireGatt().requestConnectionPriority(priority)) { "Connection priority rejected" }
        // This Android request has no completion callback on API 31-33.
        delay(100)
    }
    suspend fun read(uuid: UUID): ByteArray = operation("read", uuid) { requireGatt().readCharacteristic(characteristic(uuid)) }
    suspend fun write(uuid: UUID, value: ByteArray, response: Boolean = false) {
        operation("write", uuid) {
            val c = characteristic(uuid)
            val type = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= 33) requireGatt().writeCharacteristic(c, value.copyOf(), type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = value.copyOf(); requireGatt().writeCharacteristic(c) }
        }
    }
    suspend fun enableNotification(uuid: UUID, enabled: Boolean = true) {
        operation("descriptor", uuid) {
            val c = characteristic(uuid)
            check(requireGatt().setCharacteristicNotification(c, enabled)) { "Local notification registration failed" }
            val d = c.getDescriptor(CCCD) ?: error("Missing CCCD for $uuid")
            val value = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                else if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) requireGatt().writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            else { d.value = value; requireGatt().writeDescriptor(d) }
        }
    }
    private fun requireGatt() = gatt ?: error("GATT is not connected")
    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic = requireGatt().services.asSequence()
        .flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid } ?: error("Missing characteristic $uuid")
    override fun close() {
        pending?.result?.completeExceptionally(IOException("GATT closed"))
        val old = gatt; gatt = null
        disconnected.complete(null)
        try { old?.disconnect() } finally { old?.close() }
    }
    companion object { private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
