package com.example.atlas.acquisition.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** All entry points and callbacks are serialized on the main looper; one ATT operation at a time. */
class AndroidGattClient(context: Context) : GattClient {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(GattState())
    override val state = mutable.asStateFlow()
    private var connection: BluetoothGatt? = null
    private val characteristics = linkedMapOf<String, BluetoothGattCharacteristic>()
    private data class Pending(val key: String, val enableNotifications: Boolean? = null)
    private var pending: Pending? = null
    private var timeout: Runnable? = null
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun connect(address: String) {
        requireMain()
        disconnect()
        mutable.value = GattState(address = address, status = "Connecting", busy = true)
        try {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            check(adapter?.isEnabled == true) { "Bluetooth is disabled or unavailable." }
            val device = adapter.getRemoteDevice(address)
            connection = if (Build.VERSION.SDK_INT >= 37) {
                device.connectGatt(BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE).setAutoConnectEnabled(false).build(),
                    app.mainExecutor, callback)
            } else legacyConnect(device)
            checkNotNull(connection) { "Android did not create a GATT connection." }
            armTimeout(30_000)
        } catch (denied: SecurityException) { fail(failureLabel(denied)) }
        catch (failure: Exception) { fail(failureLabel(failure)) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("GATT connection failed (status $status)."); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> try {
                    mutable.value = mutable.value.copy(status = "Discovering services")
                    if (!gatt.discoverServices()) fail("Service discovery request rejected.")
                } catch (denied: SecurityException) { fail(failureLabel(denied)) }
                catch (failure: Exception) { fail(failureLabel(failure)) }
                BluetoothProfile.STATE_DISCONNECTED -> fail("Peripheral disconnected or became unreachable.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed (status $status)."); return }
            cancelTimeout()
            val services = gatt.services.take(128)
            val all = services.flatMap { service -> service.characteristics }
            characteristics.clear()
            all.take(512).forEach { characteristics[key(it)] = it }
            mutable.value = mutable.value.copy(status = "Connected", busy = false, ready = true,
                serviceUuids = services.map { it.uuid.toString() },
                characteristics = characteristics.map { (key, value) ->
                    GattCharacteristicInfo(key, value.service.uuid.toString(), value.uuid.toString(),
                        value.has(BluetoothGattCharacteristic.PROPERTY_READ),
                        value.has(BluetoothGattCharacteristic.PROPERTY_NOTIFY),
                        value.has(BluetoothGattCharacteristic.PROPERTY_INDICATE))
                }, truncated = gatt.services.size > 128 || all.size > 512)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readResult(gatt, characteristic, value.copyOf(), status)
        }

        @Deprecated("API 31/32 compatibility callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) readResult(gatt, characteristic, characteristic.value?.copyOf(), status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notification(gatt, characteristic, value.copyOf())
        }

        @Deprecated("API 31/32 compatibility callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(gatt, characteristic, characteristic.value?.copyOf())
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val operation = pending ?: return
            if (connection !== gatt || descriptor.uuid != cccd || key(descriptor.characteristic) != operation.key || operation.enableNotifications == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Notification configuration failed (status $status); connection closed."); return }
            val subscribed = mutable.value.subscribed.toMutableSet()
            if (operation.enableNotifications) subscribed.add(operation.key) else subscribed.remove(operation.key)
            mutable.value = mutable.value.copy(subscribed = subscribed)
            complete()
        }
    }

    override fun read(key: String) {
        requireMain()
        val pair = begin(key) ?: return
        if (!pair.second.has(BluetoothGattCharacteristic.PROPERTY_READ)) { reject("Characteristic is not readable."); return }
        pending = Pending(key)
        try {
            if (!pair.first.readCharacteristic(pair.second)) reject("Read request rejected by Android.")
            else armTimeout(10_000)
        } catch (denied: SecurityException) { fail(failureLabel(denied)) }
        catch (failure: Exception) { fail(failureLabel(failure)) }
    }

    override fun notifications(key: String, enable: Boolean) {
        requireMain()
        val pair = begin(key) ?: return
        val (gatt, characteristic) = pair
        if (!characteristic.has(BluetoothGattCharacteristic.PROPERTY_NOTIFY) && !characteristic.has(BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
            reject("Characteristic does not advertise notifications or indications."); return
        }
        val descriptor = characteristic.getDescriptor(cccd)
        if (descriptor == null) { reject("CCCD descriptor unavailable; notifications cannot be configured."); return }
        pending = Pending(key, enable)
        try {
            check(gatt.setCharacteristicNotification(characteristic, enable)) { "Local notification registration rejected." }
            val value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                characteristic.has(BluetoothGattCharacteristic.PROPERTY_NOTIFY) -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else legacyWriteDescriptor(gatt, descriptor, value)
            if (!accepted) fail("CCCD write rejected; connection closed to clear local subscription state.")
            else armTimeout(10_000)
        } catch (denied: SecurityException) { fail(failureLabel(denied)) }
        catch (failure: Exception) { fail(failureLabel(failure)) }
    }

    private fun begin(key: String): Pair<BluetoothGatt, BluetoothGattCharacteristic>? {
        if (!mutable.value.ready || mutable.value.busy || pending != null) return null
        val gatt = connection ?: return null
        val characteristic = characteristics[key] ?: return null
        mutable.value = mutable.value.copy(busy = true, error = null)
        return gatt to characteristic
    }

    private fun readResult(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        val operation = pending ?: return
        if (connection !== gatt || operation.key != key(characteristic) || operation.enableNotifications != null) return
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) addValue(operation.key, value, "Read")
        complete()
        if (status != BluetoothGatt.GATT_SUCCESS) mutable.value = mutable.value.copy(error = "Read failed (GATT status $status). Authorization or encryption may be required; no automatic retry.")
        else if (value == null) mutable.value = mutable.value.copy(error = "Read completed without a supplied value; data unavailable.")
    }

    private fun notification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (connection !== gatt || key(characteristic) !in mutable.value.subscribed) return
        if (value != null) addValue(key(characteristic), value, "Notification/indication")
        else mutable.value = mutable.value.copy(error = "Notification arrived without a supplied value; data unavailable.")
    }

    private fun addValue(key: String, value: ByteArray, kind: String) {
        val event = GattValue(key, value.take(512).toByteArray().hex(), System.currentTimeMillis(), kind)
        mutable.value = mutable.value.copy(values = (listOf(event) + mutable.value.values).take(128))
    }

    private fun complete() {
        cancelTimeout(); pending = null
        mutable.value = mutable.value.copy(busy = false, status = "Connected")
    }

    private fun reject(message: String) { complete(); mutable.value = mutable.value.copy(error = message) }

    override fun disconnect() {
        requireMain()
        cancelTimeout()
        pending = null
        val old = connection
        connection = null // Reject late callbacks before touching the old connection.
        characteristics.clear()
        var cleanupError: String? = null
        try { old?.disconnect() } catch (_: SecurityException) { cleanupError = "Permission revoked; Android denied disconnect." }
        catch (failure: RuntimeException) { cleanupError = "GATT disconnect failed (${failure.javaClass.simpleName})." }
        try { old?.close() } catch (_: SecurityException) { cleanupError = "Permission revoked; Android denied GATT close." }
        catch (failure: RuntimeException) { cleanupError = "GATT close failed (${failure.javaClass.simpleName})." }
        mutable.value = GattState(error = cleanupError)
    }

    private fun fail(message: String) {
        disconnect()
        mutable.value = mutable.value.copy(error = listOfNotNull(message, mutable.value.error).joinToString(" "))
    }

    private fun armTimeout(millis: Long) {
        cancelTimeout()
        timeout = Runnable { fail("GATT operation timed out; connection closed. No automatic retry.") }.also { handler.postDelayed(it, millis) }
    }

    private fun cancelTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }
    private fun key(value: BluetoothGattCharacteristic) = "${value.service.instanceId}/${value.instanceId}/${value.uuid}"
    private fun BluetoothGattCharacteristic.has(property: Int) = properties and property != 0
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun failureLabel(failure: Exception): String = when (failure) {
        is SecurityException -> "Android denied GATT access; review Nearby devices permission."
        is IllegalStateException -> failure.message ?: "Bluetooth unavailable."
        is IllegalArgumentException -> "Invalid Bluetooth address or GATT argument."
        else -> "GATT failed (${failure.javaClass.simpleName})."
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun legacyWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        descriptor.value = value
        return gatt.writeDescriptor(descriptor)
    }

    // API 31–36 use the handler overload; API 37 uses an executor and connection settings.
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun legacyConnect(device: BluetoothDevice): BluetoothGatt? = device.connectGatt(
        app, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
}
