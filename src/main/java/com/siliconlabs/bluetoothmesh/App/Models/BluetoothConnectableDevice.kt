/*
 * Copyright © 2020 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.siliconlab.bluetoothmesh.adk.connectable_device.*
import com.siliconlabs.bluetoothmesh.App.Logic.BluetoothScanner
import java.lang.Thread.sleep
import java.util.*
import java.util.concurrent.TimeUnit

open class BluetoothConnectableDevice(
        val context: Context,
        var scanResult: ScanResult,
        val bluetoothScanner: BluetoothScanner
) : ConnectableDevice() {
    private val TAG = BluetoothConnectableDevice::class.java.simpleName + "@" + hashCode()
    var deviceConnectionCallbacks = mutableSetOf<DeviceConnectionCallback>()
    var mainHandler = Handler(Looper.getMainLooper())
    var bluetoothGatt: BluetoothGatt? = null
    lateinit var bluetoothDevice: BluetoothDevice
    lateinit var address: String
        private set
    private lateinit var bluetoothGattCallback: BluetoothGattCallback
    lateinit var scanCallback: ScanCallback
    private var refreshBluetoothDeviceCallback: RefreshBluetoothDeviceCallback? = null
    private lateinit var refreshBluetoothDeviceTimeoutRunnable: Runnable

    init {
        processScanResult(scanResult)
        initScanCallback()
        initBluetoothGattCallback()
        initRefreshBluetoothDeviceTimeoutRunnable()
    }

    private fun processScanResult(scanResult: ScanResult) {
        this.bluetoothDevice = scanResult.device
        this.advertisementData = scanResult.scanRecord!!.bytes
        this.address = bluetoothDevice.address
        this.scanResult = scanResult
    }

    fun initScanCallback() {
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                Log.d(TAG, result.toString())

                result?.let {
                    if (it.device.address == address) {
                        processDeviceFound(result)
                    }
                }
            }

            fun processDeviceFound(result: ScanResult) {
                stopScan()
                mainHandler.removeCallbacks(refreshBluetoothDeviceTimeoutRunnable)
                processScanResult(result)
                // workaround to 133 gatt issue
                // https://github.com/googlesamples/android-BluetoothLeGatt/issues/44
                mainHandler.postDelayed({ refreshBluetoothDeviceCallback?.success() }, 500)
            }
        }
    }

    fun initBluetoothGattCallback() {
        bluetoothGattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                mainHandler.post {
                    Log.d(TAG, "onConnectionStateChange : status: $status, newState: $newState")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectionRequest?.handleSuccess()
                            }
                            BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                                handleDisconnected()
                            }
                        }
                    } else {
                        gatt.close()
                        if (connecting) {
                            connectionRequest?.handleAttemptFailure()
                        } else {
                            handleBrokenConnection()
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                mainHandler.post {
                    Log.d(TAG, "onMtuChanged : status $status, mtu: $mtu")
                    this@BluetoothConnectableDevice.mtu = mtu
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        mtuRequest?.handleSuccess()
                    } else {
                        mtuRequest?.handleAttemptFailure()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                mainHandler.post {
                    Log.d(TAG, "onServicesDiscovered: status: $status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        discoverServicesRequest?.handleSuccess()
                    } else {
                        discoverServicesRequest?.handleAttemptFailure()
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                Log.d(TAG, "onCharacteristicChanged : bluetoothGattCharacteristic: ${characteristic.uuid}")
                updateData(characteristic.service.uuid, characteristic.uuid, characteristic.value)
            }
        }
    }

    private fun handleBrokenConnection() {
        onConnectionError()
        notifyConnectionState(false)
    }

    private fun handleDisconnected() {
        bluetoothGatt?.close()
        /* When connection is being closed by Android, it needs 1s to check if other apps are using this gatt connection with this peripheral.
        Otherwise, when trying to re-connect without delay, connection will close on behalf of a peripheral after a while calling
        #onConnectionStateChange with status 22 */

        notifyDisconnectionWithDelay()
    }

    private fun notifyDisconnectionWithDelay() {
        mainHandler.postDelayed({
            notifyDeviceDisconnected()
            Log.d(TAG, "Gatt connection closed")
        }, 1500)
    }

    fun initRefreshBluetoothDeviceTimeoutRunnable() {
        refreshBluetoothDeviceTimeoutRunnable = Runnable {
            refreshingBluetoothDeviceTimeout()
        }
    }

    interface RequestCallback {
        fun success()
        fun failure()
    }

    fun discoverServices(callback: RequestCallback) {
        Log.d(TAG, "discoverServices")
        DiscoverServicesRequest(callback).process()
    }

    private var discoverServicesRequest: DiscoverServicesRequest? = null

    private abstract class Request(private val callback: RequestCallback) {
        private var attempt = 0

        abstract fun process()
        abstract fun finish()

        fun handleSuccess() {
            finish()
            callback.success()
        }

        fun handleAttemptFailure() {
            if (++attempt < 3) {
                process()
            } else {
                handleFailure()
            }
        }

        fun handleFailure() {
            finish()
            callback.failure()
        }
    }

    private inner class DiscoverServicesRequest(callback: RequestCallback) : Request(callback) {
        init {
            discoverServicesRequest = this
        }

        override fun process() {
            repeat(3) {
                if (bluetoothGatt!!.discoverServices()) {
                    return
                }
                sleep(50)
                Log.d(TAG, "retry discover services i: $it")
            }
            handleFailure()
        }

        override fun finish() {
            discoverServicesRequest = null
        }
    }

    private var mtu = 0
    override fun getMTU() = mtu

    fun changeMtu(callback: RequestCallback) {
        Log.d(TAG, "changeMtu")
        MtuRequest(callback).process()
    }

    private var mtuRequest: MtuRequest? = null

    private inner class MtuRequest(callback: RequestCallback) : Request(callback) {
        init {
            mtuRequest = this
        }

        override fun process() {
            repeat(3) {
                if (bluetoothGatt!!.requestMtu(512)) {
                    return
                }
                sleep(50)
                Log.d(TAG, "retry request mtu i: $it")
            }
            handleFailure()
        }

        override fun finish() {
            mtuRequest = null
        }
    }

    private var connectionRequest: ConnectionRequest? = null
    val connecting get() = connectionRequest != null

    private inner class ConnectionRequest(callback: RequestCallback) : Request(callback) {
        init {
            connectionRequest = this
        }

        override fun process() {
            checkMainThread()
            bluetoothGatt = bluetoothDevice.connectGatt(context, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE).also {
                it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                setupTimeout(it)
            }
        }

        private fun setupTimeout(bluetoothGattLast: BluetoothGatt) {
            mainHandler.postDelayed({
                if (bluetoothGatt == bluetoothGattLast && connecting) {
                    Log.d(TAG, "connection timeout mac: $address")
                    onConnectionError()
                }
            }, TimeUnit.SECONDS.toMillis(30))
        }

        override fun finish() {
            connectionRequest = null
        }
    }

    fun addDeviceConnectionCallback(deviceConnectionCallback: DeviceConnectionCallback) {
        synchronized(deviceConnectionCallbacks) {
            deviceConnectionCallbacks.add(deviceConnectionCallback)
        }
    }

    fun removeDeviceConnectionCallback(deviceConnectionCallback: DeviceConnectionCallback) {
        synchronized(deviceConnectionCallbacks) {
            deviceConnectionCallbacks.remove(deviceConnectionCallback)
        }
    }

    fun notifyConnectionState(connected: Boolean) {
        synchronized(deviceConnectionCallbacks) {
            for (callback in deviceConnectionCallbacks) {
                notifyConnectionState(callback, connected)
            }
        }
    }

    private fun notifyConnectionState(callback: DeviceConnectionCallback, connected: Boolean) {
        if (connected) {
            callback.onConnectedToDevice()
        } else {
            callback.onDisconnectedFromDevice()
        }
    }

    override fun getName(): String? {
        return bluetoothDevice.name
    }

    override fun refreshBluetoothDevice(callback: RefreshBluetoothDeviceCallback) {
        if (startScan()) {
            Log.d(TAG, "refreshBluetoothDevice: starting scan succeeded")
            onScanStarted(callback)
        } else {
            Log.d(TAG, "refreshBluetoothDevice: starting scan failed")
            callback.failure()
        }
    }

    private fun onScanStarted(callback: RefreshBluetoothDeviceCallback) {
        refreshBluetoothDeviceCallback = callback
        mainHandler.removeCallbacks(refreshBluetoothDeviceTimeoutRunnable)
        mainHandler.postDelayed(refreshBluetoothDeviceTimeoutRunnable, 10000L)
    }

    private fun refreshingBluetoothDeviceTimeout() {
        Log.d(TAG, "refreshingBluetoothDeviceTimeout")

        mainHandler.removeCallbacks(refreshBluetoothDeviceTimeoutRunnable)
        stopScan()
        refreshBluetoothDeviceCallback?.failure()
        refreshBluetoothDeviceCallback = null
    }

    override fun connect() {
        Log.d(TAG, "connect mac: $address")
        ConnectionRequest(object : RequestCallback {
            override fun success() {
                configureConnectionAndNotifyResult()
            }

            override fun failure() {
                onConnectionError()
            }
        }).process()
    }

    private fun configureConnectionAndNotifyResult() {
        changeMtu(object : RequestCallback {
            private val servicesDiscoveredCallback = object : RequestCallback {
                override fun success() {
                    notifyDeviceConnected()
                }

                override fun failure() {
                    disconnect()
                }
            }

            override fun success() {
                discoverServices(servicesDiscoveredCallback)
            }

            override fun failure() {
                discoverServices(servicesDiscoveredCallback)
            }
        })
    }

    private fun startScan(): Boolean {
        bluetoothScanner.addScanCallback(scanCallback)
        return bluetoothScanner.startLeScan(null)
    }

    private fun stopScan() {
        bluetoothScanner.removeScanCallback(scanCallback)
        bluetoothScanner.stopLeScan()
    }

    override fun disconnect() {
        Log.d(TAG, "disconnect mac: $address")
        checkMainThread()

        connectionRequest?.finish()
        mainHandler.removeCallbacks(refreshBluetoothDeviceTimeoutRunnable)
        refreshDeviceCache()
        bluetoothGatt?.disconnect()
        stopScan()
    }

    private fun notifyDeviceConnected() {
        Log.d(TAG, "notifyDeviceConnected")
        connectionRequest?.finish()
        onConnected()
        notifyConnectionState(true)
    }

    private fun notifyDeviceDisconnected() {
        onDisconnected()
        notifyConnectionState(false)
    }

    fun refreshDeviceCache(): Boolean {
        var result = false
        try {
            val refreshMethod = bluetoothGatt?.javaClass?.getMethod("refresh")
            result = refreshMethod?.invoke(bluetoothGatt, *arrayOfNulls(0)) as? Boolean ?: false
            Log.d(TAG, "refreshDeviceCache $result")
        } catch (localException: Exception) {
            Log.e(TAG, "An exception occurred while refreshing device")
        }
        return result
    }

    private var advertisementData: ByteArray? = null
    override fun getAdvertisementData() = advertisementData

    override fun refreshGattServices(refreshGattServicesCallback: RefreshGattServicesCallback) {
        if (refreshDeviceCache()) {
            discoverServices(object : RequestCallback {
                override fun success() {
                    refreshGattServicesCallback.onSuccess()
                }

                override fun failure() {
                    refreshGattServicesCallback.onFail()
                    disconnect()
                }
            })
        } else {
            refreshGattServicesCallback.onFail()
        }
    }

    override fun getServiceData(service: UUID?): ByteArray? {
        return service?.let { scanResult.scanRecord?.getServiceData(ParcelUuid(it)) }
    }

    override fun hasService(service: UUID?): Boolean {
        Log.d(TAG, "hasService $service")

        return if (bluetoothGatt?.services?.isNotEmpty() == true) {
            bluetoothGatt!!.getService(service) != null
        } else {
            scanResult.scanRecord?.serviceUuids?.contains(ParcelUuid(service))
                    ?: false
        }
    }

    override fun writeData(service: UUID?, characteristic: UUID?, data: ByteArray?, connectableDeviceWriteCallback: ConnectableDeviceWriteCallback) {
        checkMainThread()

        try {
            tryToWriteData(service, characteristic, data)
            connectableDeviceWriteCallback.onWrite(service, characteristic)
        } catch (e: Exception) {
            Log.w(TAG, "writeData error: ${e.message}")
            connectableDeviceWriteCallback.onFailed(service, characteristic)
        }
    }

    private fun tryToWriteData(service: UUID?, characteristic: UUID?, data: ByteArray?) {
        val bluetoothGattCharacteristic = getBluetoothGattCharacteristic(service, characteristic)
        setCharacteristicValueAndWriteType(bluetoothGattCharacteristic, data)
        writeCharacteristic(bluetoothGattCharacteristic)
    }

    private fun getBluetoothGattCharacteristic(service: UUID?, characteristic: UUID?): BluetoothGattCharacteristic {
        return bluetoothGatt!!.getService(service)!!.getCharacteristic(characteristic)
    }

    private fun setCharacteristicValueAndWriteType(characteristic: BluetoothGattCharacteristic, data: ByteArray?) {
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }

    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (!bluetoothGatt!!.writeCharacteristic(characteristic)) {
            throw Exception("Writing to characteristic failed")
        }
    }

    override fun subscribe(service: UUID?, characteristic: UUID?, connectableDeviceSubscriptionCallback: ConnectableDeviceSubscriptionCallback) {
        Log.d(TAG, "subscribe service=$service characteristic=$characteristic")
        checkMainThread()

        try {
            Log.d(TAG, "available services=" + bluetoothGatt?.services?.map { it.uuid })
            tryToSubscribe(service, characteristic)
            connectableDeviceSubscriptionCallback.onSuccess(service, characteristic)
        } catch (e: Exception) {
            e.message?.let { Log.e(TAG, "subscribe error: $it") } ?: e.printStackTrace()
            connectableDeviceSubscriptionCallback.onFail(service, characteristic)
        }
    }

    override fun unsubscribe(service: UUID?, characteristic: UUID?, capableDeviceUnsubscriptionCallback: ConnectableDeviceUnsubscriptionCallback) {
        Log.d(TAG, "unsubscribe service=$service characteristic=$characteristic")
        checkMainThread()

        try {
            Log.d(TAG, "available services=" + bluetoothGatt?.services?.map { it.uuid })
            tryToUnsubscribe(service, characteristic)
            capableDeviceUnsubscriptionCallback.onSuccess(service, characteristic)
        } catch (e: Exception) {
            e.message?.let { Log.e(TAG, "subscribe error: $it") } ?: e.printStackTrace()
            capableDeviceUnsubscriptionCallback.onFail(service, characteristic)
        }
    }

    private fun tryToSubscribe(service: UUID?, characteristic: UUID?) {
        val bluetoothGattCharacteristic =
                try {
                    getBluetoothGattCharacteristic(service, characteristic)
                } catch (e: NullPointerException) {
                    throw NullPointerException("Service not available")
                }
        setCharacteristicNotification(bluetoothGattCharacteristic, true)
        val bluetoothGattDescriptor = getBluetoothGattDescriptor(bluetoothGattCharacteristic)
                .apply { value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE }
        writeDescriptor(bluetoothGattDescriptor)
    }

    private fun tryToUnsubscribe(service: UUID?, characteristic: UUID?) {
        val bluetoothGattCharacteristic =
                try {
                    getBluetoothGattCharacteristic(service, characteristic)
                } catch (e: NullPointerException) {
                    throw NullPointerException("Service not available")
                }
        setCharacteristicNotification(bluetoothGattCharacteristic, false)
        val bluetoothGattDescriptor = getBluetoothGattDescriptor(bluetoothGattCharacteristic)
                .apply { value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE }
        writeDescriptor(bluetoothGattDescriptor)
    }

    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean) {
        if (!bluetoothGatt!!.setCharacteristicNotification(characteristic, enable)) {
            throw Exception("Set characteristic notification failed: characteristic=$characteristic enable=$enable")
        }
    }

    private fun getBluetoothGattDescriptor(characteristic: BluetoothGattCharacteristic): BluetoothGattDescriptor {
        return characteristic.descriptors.takeIf { it.size == 1 }?.first()
                ?: throw Exception("Descriptors size (${characteristic.descriptors.size}) different than expected: 1")
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor) {
        if (!bluetoothGatt!!.writeDescriptor(descriptor)) {
            throw Exception("Writing to descriptor failed")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BluetoothConnectableDevice

        return (scanResult == other.scanResult)
    }

    override fun hashCode(): Int {
        return scanResult.hashCode()
    }

    fun checkMainThread() {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            throw RuntimeException("Not on the main thread.")
        }
    }

    interface DeviceConnectionCallback {
        fun onConnectedToDevice()
        fun onDisconnectedFromDevice()
    }
}
