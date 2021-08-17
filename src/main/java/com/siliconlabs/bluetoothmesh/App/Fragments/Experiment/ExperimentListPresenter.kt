/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import com.siliconlab.bluetoothmesh.adk.BluetoothMesh
import com.siliconlab.bluetoothmesh.adk.ErrorType
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerConnection
import com.siliconlabs.bluetoothmesh.App.BasePresenter
import com.siliconlabs.bluetoothmesh.App.Logic.BluetoothScanner
import com.siliconlabs.bluetoothmesh.App.Logic.MeshLogic
import com.siliconlabs.bluetoothmesh.App.Logic.NetworkConnectionListener
import com.siliconlabs.bluetoothmesh.App.Logic.NetworkConnectionLogic
import com.siliconlabs.bluetoothmesh.App.Models.*
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.Experiment
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.App.Utils.*
import com.siliconlabs.bluetoothmesh.BuildConfig
import com.siliconlabs.bluetoothmesh.R
import java.io.IOException

class ExperimentListPresenter(
        val context: Context,
        private val experimentListView: ExperimentListView,
        meshLogic: MeshLogic,
        private val meshNetworkManager: MeshNetworkManager,
        val networkConnectionLogic: NetworkConnectionLogic,
        val meshNodeManager: MeshNodeManager,
        private val bluetoothLeScanLogic: BluetoothScanner,
        deviceFunctionalityDb: MeshNodeManager
) : BasePresenter, NetworkConnectionListener, BluetoothScanHandler {
    private val handler = Handler()
    private val experimentExecutor = ExperimentExecutor(context, experimentListView, meshLogic, meshNetworkManager, networkConnectionLogic,
            meshNodeManager, this, deviceFunctionalityDb)
    private var experiments: List<ExperimentDetail> = emptyList()

    private var startScannerMillis = 0L
    private var stepScanningMillis = 0L

    fun processExperiments() {
        BluetoothMesh.getInstance().clearDatabase()
        meshNetworkManager.createDefaultStructure()
        val subnet = findTestSubnet() ?: meshNetworkManager.network!!.createSubnet(SUBNET_NAME)
        val group = subnet.groups.firstOrNull() ?: subnet.createGroup("Group", null, null)
        val nextExperimentRun = experimentExecutor.isFinished
        if (nextExperimentRun) {
            prepareExperimentData()
        }
        experimentExecutor.prepare(subnet, group, experiments)
        if (nextExperimentRun) {
            networkConnectionLogic.addListener(this)
        }
        experimentExecutor.start()
    }

    private fun findTestSubnet() = meshNetworkManager.network!!.subnets?.find { it.name == SUBNET_NAME }

    // region Scanner
    override fun startScanner(mode: Int, timeoutDelay: Long) {
        startScannerMillis = System.currentTimeMillis()
        Log.i(TAG, "=================Start Scanner: $startScannerMillis ")
        stepScanningMillis = startScannerMillis

        if (!bluetoothLeScanLogic.isLeScanStarted()) {
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(scanTimeoutCallback, timeoutDelay)
            val meshServ = ParcelUuid.fromString(ProvisionerConnection.MESH_UNPROVISIONED_SERVICE.toString())
            bluetoothLeScanLogic.addScanCallback(scanCallback)
            bluetoothLeScanLogic.startLeScan(meshServ, mode)
        }
    }

    override fun stopScanner() {
        if (bluetoothLeScanLogic.isLeScanStarted()) {
            Log.i(TAG, "=================End Scanner: ${System.currentTimeMillis()} ")
            val stopScannerMillis = System.currentTimeMillis() - startScannerMillis
            Log.i(TAG, "=================Time of Scanner: $stopScannerMillis ms")
            bluetoothLeScanLogic.stopLeScan()
            handler.removeCallbacks(scanTimeoutCallback)
            bluetoothLeScanLogic.removeScanCallback(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result?.scanRecord?.serviceUuids?.isEmpty() != false) return
            val bluetoothConnectableDevice = BluetoothConnectableDevice(context, result, bluetoothLeScanLogic)
            val deviceDescription = DeviceDescriptionBuilder.build(result, bluetoothConnectableDevice)
            val durationScanningMillis = System.currentTimeMillis() - stepScanningMillis
            stepScanningMillis = System.currentTimeMillis()
            Log.v(TAG, "=========durationScanningMillis of a step $durationScanningMillis")

            experimentExecutor.onScanResult(bluetoothConnectableDevice.uuid, deviceDescription)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            stopScanner()
            experimentExecutor.nextExperiment(context.getString(R.string.message_error_cannot_scanner_device))
        }
    }

    private val scanTimeoutCallback = Runnable {
        if (bluetoothLeScanLogic.isLeScanStarted()) {
            experimentExecutor.onScanTimeout()
        }
    }
    // endregion

    // region Log file
    fun prepareSaveLogIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(logfileType)
                .putExtra(Intent.EXTRA_TITLE, "${LogUtils.generateFileName()}.txt")
    }

    fun writeInFile(uri: Uri, text: String) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            outputStream?.bufferedWriter()?.use {
                it.write(text)
                it.flush()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun prepareExperimentData() {
        experiments = Experiment.values().map {
            ExperimentDetail(it, it.maxTime)
        }
        experimentListView.prepareData(experiments)
    }

    fun getLog(): String {
        return StringBuilder()
                .append("<timestamp>").append(LogUtils.getDate()).append("</timestamp>").append("\n")
                .append("<phone_informations>").append("\n")
                .append("\t").append("<phone_name>").append(DeviceUtils.getDeviceName()).append("</phone_name>").append("\n")
                .append("\t").append("<phone_os_version>").append(DeviceUtils.getAndroidVersion()).append("</phone_os_version>").append("\n")
                .append("</phone_informations>").append("\n")
                .append("<firmware_informations>").append("\n")
                .append(getDevicesUuidLog())
                .append("</firmware_informations>").append("\n")
                .append("<test_results>").append("\n")
                .append(getExperimentsLog())
                .append("</test_results>").append("\n")
                .toString()
    }

    private fun getDevicesUuidLog(): StringBuilder {
        return StringBuilder().apply {
            if (experiments[0].deviceDescription != null) {
                append("\t").append("<proxy_node_uuid>").append(Experiment.Node.PROXY.uuid).append("</proxy_node_uuid>").append("\n")
            }
            if (experiments[1].deviceDescription != null) {
                append("\t").append("<relay_node_uuid>").append(Experiment.Node.RELAY.uuid).append("</relay_node_uuid>").append("\n")
            }
            if (experiments[2].deviceDescription != null) {
                append("\t").append("<friend_node_uuid>").append(Experiment.Node.FRIEND.uuid).append("</friend_node_uuid>").append("\n")
            }
            if (experiments[3].deviceDescription != null) {
                append("\t").append("<lpn_node_uuid>").append(Experiment.Node.LPN.uuid).append("</lpn_node_uuid>").append("\n")
            }
        }
    }

    private fun getExperimentsLog(): StringBuilder {
        return StringBuilder().apply {
            experiments.forEach { experiment ->
                append("\t").append(experiment).append("\n")
            }
        }
    }
    // endregion

    override fun onResume() {
        Log.i(TAG, "onResume ")
        Log.i(TAG, "Version code: ${BuildConfig.VERSION_CODE} Version name: ${BuildConfig.VERSION_NAME}")
        networkConnectionLogic.addListener(this)
    }

    override fun onPause() {
        if (networkConnectionLogic.isConnected()) {
            networkConnectionLogic.disconnect()
        } else {
            networkConnectionLogic.removeListener(this)
        }
        handler.removeCallbacks(scanTimeoutCallback)
    }

    // region Connection
    override fun connecting() {
        Log.i(TAG, "connecting")
    }

    override fun connected() {
        experimentExecutor.onSubnetConnected()
    }

    override fun disconnected() {
        if (experimentExecutor.isFinished) {
            networkConnectionLogic.removeListener(this)
        } else {
            experimentExecutor.onSubnetDisconnected()
        }
    }

    override fun initialConfigurationLoaded() {
    }

    override fun connectionMessage(messageType: NetworkConnectionListener.MessageType) {
        Log.i(TAG, "connectionMessage $messageType")
        handler.postDelayed({
            experimentExecutor.nextExperiment(context.getString(R.string.message_error_not_connect_node))
        }, 500)
    }

    override fun connectionErrorMessage(error: ErrorType) {
        Log.e(TAG, "connectionErrorMessage $error")
    }
    // endregion

    companion object {
        private const val logfileType = "text/plain"
        private const val SUBNET_NAME = "IOP Test"
    }
}
