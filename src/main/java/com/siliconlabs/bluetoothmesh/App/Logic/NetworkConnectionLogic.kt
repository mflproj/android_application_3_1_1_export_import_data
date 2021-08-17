/*
 * Copyright © 2019 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Logic

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.siliconlab.bluetoothmesh.adk.ErrorType
import com.siliconlab.bluetoothmesh.adk.configuration_control.ConfigurationControl
import com.siliconlab.bluetoothmesh.adk.configuration_control.GetDeviceCompositionDataCallback
import com.siliconlab.bluetoothmesh.adk.configuration_control.SetNodeBehaviourCallback
import com.siliconlab.bluetoothmesh.adk.connectable_device.*
import com.siliconlab.bluetoothmesh.adk.connectable_device.ProxyConnection.MESH_PROXY_SERVICE
import com.siliconlab.bluetoothmesh.adk.data_model.dcd.DeviceCompositionData
import com.siliconlab.bluetoothmesh.adk.data_model.element.Element
import com.siliconlab.bluetoothmesh.adk.data_model.node.Node
import com.siliconlab.bluetoothmesh.adk.data_model.subnet.Subnet
import com.siliconlabs.bluetoothmesh.App.Models.BluetoothConnectableDevice
import java.util.*

class NetworkConnectionLogic(private val context: Context,
                             private val connectableDeviceHelper: ConnectableDeviceHelper,
                             val bluetoothScanner: BluetoothScanner) : BluetoothConnectableDevice.DeviceConnectionCallback {
    private val TAG: String = javaClass.canonicalName!!

    private val uiHandler: Handler = Handler(Looper.getMainLooper())

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private var currentState = ConnectionState.DISCONNECTED

    private val listeners = mutableSetOf<NetworkConnectionListener>()

    private var subnet: Subnet? = null

    private var proxyConnection: ProxyConnection? = null

    private var bluetoothConnectableDevice: BluetoothConnectableDevice? = null

    private var connectionTimeoutRunnable = Runnable { connectionTimeout() }

    @Synchronized
    fun connect(subnet: Subnet) {
        if (this.subnet == subnet && currentState != ConnectionState.DISCONNECTED) {
            return
        }

        Log.d(TAG, "Connecting to subnet")
        setCurrentState(ConnectionState.CONNECTING)

        this.subnet = subnet
        bluetoothScanner.addScanCallback(scanCallback)
        startScan()
    }

    fun connect(bluetoothConnectableDevice: BluetoothConnectableDevice, refreshBluetoothDevice: Boolean) {
        synchronized(currentState) {
            if (subnet != null) {
                disconnect()
            }

            Log.d(TAG, "Connecting to device")
            setCurrentState(ConnectionState.CONNECTING)

            // workaround to 133 gatt issue
            // https://github.com/googlesamples/android-BluetoothLeGatt/issues/44
            uiHandler.postDelayed({
                this@NetworkConnectionLogic.bluetoothConnectableDevice = bluetoothConnectableDevice
                bluetoothConnectableDevice.addDeviceConnectionCallback(this@NetworkConnectionLogic)

                proxyConnection = ProxyConnection(bluetoothConnectableDevice)
                proxyConnection!!.connectToProxy(refreshBluetoothDevice, object : ConnectionCallback {
                    override fun success(device: ConnectableDevice) {
                        Log.d(TAG, "ConnectionCallback success")
                        setCurrentState(ConnectionState.CONNECTED)
                    }

                    override fun error(device: ConnectableDevice, error: ErrorType) {
                        Log.d(TAG, "ConnectionCallback error=$error")
                        setCurrentState(ConnectionState.DISCONNECTED)
                        connectionErrorMessage(error)
                    }
                })
            }, 500)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        subnet = null
        stopScan()
        bluetoothScanner.removeScanCallback(scanCallback)
        setCurrentState(ConnectionState.DISCONNECTED)
        bluetoothConnectableDevice?.removeDeviceConnectionCallback(this)
        bluetoothConnectableDevice = null
        proxyConnection?.disconnect(object : DisconnectionCallback {
            override fun success(device: ConnectableDevice) {
                Log.d(TAG, "Disconnecting success")
            }

            override fun error(device: ConnectableDevice, error: ErrorType) {
                Log.d(TAG, "Disconnecting error: $error")
            }
        })
    }

    fun setEstablishedProxyConnection(proxyConnection: ProxyConnection, subnet: Subnet) {
        this.proxyConnection = proxyConnection
        this.subnet = subnet
        setCurrentState(ConnectionState.CONNECTED)
    }

    fun setupInitialNodeConfiguration(node: Node) {
        enableProxy(node)
    }

    private fun enableProxy(node: Node) {
        ConfigurationControl(node).setProxy(true, object : SetNodeBehaviourCallback {
            override fun error(node: Node, error: ErrorType) {
                connectionErrorMessage(error)
            }

            override fun success(node: Node, enabled: Boolean) {
                getDeviceCompositionData(node)
            }
        })
    }

    private fun getDeviceCompositionData(node: Node) {
        ConfigurationControl(node).getDeviceCompositionData(0, object : GetDeviceCompositionDataCallback {
            override fun error(node: Node, error: ErrorType) {
                connectionErrorMessage(error)
            }

            override fun success(node: Node, deviceCompositionData: DeviceCompositionData, elements: Array<out Element>?) {
                enableNodeIdentity(node)
            }
        })
    }

    private fun enableNodeIdentity(node: Node) {
        ConfigurationControl(node).setNodeIdentity(true, node.subnets.first(), object : SetNodeBehaviourCallback {
            override fun error(node: Node, error: ErrorType) {
                connectionErrorMessage(error)
            }

            override fun success(node: Node, enabled: Boolean) {
                listeners.forEach { listener -> listener.initialConfigurationLoaded() }
            }
        })
    }

    fun addListener(networkConnectionListener: NetworkConnectionListener) {
        synchronized(listeners) {
            listeners.add(networkConnectionListener)

            notifyCurrentState(networkConnectionListener)
        }
    }

    fun removeListener(networkConnectionListener: NetworkConnectionListener) {
        synchronized(listeners) {
            listeners.remove(networkConnectionListener)
        }
    }

    // DeviceConnectionCallback

    override fun onConnectedToDevice() {
        Log.d(TAG, "onConnectedToDevice")
        //ignore
    }

    override fun onDisconnectedFromDevice() {
        Log.d(TAG, "onDisconnectedFromDevice")
        disconnect()
    }

    // ScanCallback

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, result.toString())

            val bluetoothConnectableDevice = BluetoothConnectableDevice(context, result, bluetoothScanner)

            val subnets = connectableDeviceHelper.findSubnets(bluetoothConnectableDevice)

            if (subnets.contains(subnet)) {
                stopScan()
            } else {
                return
            }

            connect(bluetoothConnectableDevice, refreshBluetoothDevice = false)
        }
    }

    private fun startScan() {
        Log.d(TAG, "Start scanning")

        if (subnet?.nodes!!.isEmpty()) {
            notifyThereAreNoNodes()
            return
        }

        val meshProxyService = ParcelUuid(MESH_PROXY_SERVICE)
        if (bluetoothScanner.startLeScan(meshProxyService)) {
            uiHandler.removeCallbacks(connectionTimeoutRunnable)
            uiHandler.postDelayed(connectionTimeoutRunnable, 10000)
        }
    }

    private fun stopScan() {
        Log.d(TAG, "Stop Scanning")

        bluetoothScanner.stopLeScan()
    }

    private fun connectionTimeout() {
        Log.d(TAG, "Connection timeout")

        stopScan()
        setCurrentState(ConnectionState.DISCONNECTED)
        connectionErrorMessage(ErrorType(ErrorType.TYPE.COULD_NOT_CONNECT_TO_DEVICE))
    }

    fun isConnected(): Boolean {
        synchronized(this) {
            return currentState == ConnectionState.CONNECTED
        }
    }

    private fun setCurrentState(currentState: ConnectionState) {
        Log.d(TAG, "setCurrentState: $currentState")
        synchronized(this) {
            if (this.currentState == currentState) {
                return
            }
            uiHandler.removeCallbacks(connectionTimeoutRunnable)
            this.currentState = currentState
        }
        notifyCurrentState()
    }

    private fun notifyCurrentState() {
        synchronized(listeners) {
            listeners.forEach {
                notifyCurrentState(it)
            }
        }
    }

    private fun notifyCurrentState(listener: NetworkConnectionListener) {
        uiHandler.post {
            listener.run {
                when (currentState) {
                    ConnectionState.DISCONNECTED -> disconnected()
                    ConnectionState.CONNECTING -> connecting()
                    ConnectionState.CONNECTED -> connected()
                }
            }
        }
    }

    private fun notifyThereAreNoNodes() {
        synchronized(listeners) {
            uiHandler.post {
                listeners.forEach { listener -> listener.connectionMessage(NetworkConnectionListener.MessageType.NO_NODE_IN_NETWORK) }
            }
        }
    }

    private fun connectionErrorMessage(errorType: ErrorType) {
        synchronized(listeners) {
            uiHandler.post {
                listeners.forEach { listener -> listener.connectionErrorMessage(errorType) }
            }
        }
    }

    fun getCurrentlyConnectedNode(): Node? {
        return connectableDeviceHelper.findNode(bluetoothConnectableDevice)
    }
}
