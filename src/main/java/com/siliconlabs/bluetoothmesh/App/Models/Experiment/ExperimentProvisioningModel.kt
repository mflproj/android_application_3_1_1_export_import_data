/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models.Experiment

import android.util.Log
import com.siliconlab.bluetoothmesh.adk.connectable_device.ProxyConnection
import com.siliconlab.bluetoothmesh.adk.data_model.subnet.Subnet
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerConfiguration
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerConnection
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisioningCallback
import com.siliconlabs.bluetoothmesh.App.Fragments.Experiment.ExperimentListView
import com.siliconlabs.bluetoothmesh.App.Logic.MeshLogic
import com.siliconlabs.bluetoothmesh.App.Models.BluetoothConnectableDevice
import com.siliconlabs.bluetoothmesh.App.Models.DeviceDescription
import com.siliconlabs.bluetoothmesh.App.Models.ProvisionedOOBControlImpl

class ExperimentProvisioningModel(private val meshLogic: MeshLogic) {

    private var provisionerConnection: ProvisionerConnection? = null

    val proxyConnection: ProxyConnection?
        get() = provisionerConnection?.proxyConnection

    fun provisionDevice(typeOOb: ExperimentDetail.Provisioned, selectedDevice: DeviceDescription, experimentListView: ExperimentListView,
                        networkInfo: Subnet, callback: ProvisioningCallback) {
        meshLogic.currentSubnet = networkInfo

        val connectableDevice = selectedDevice.connectable_device!!
        provisionDevice(typeOOb, experimentListView, connectableDevice, networkInfo, callback)
    }

    private fun provisionDevice(oobType: ExperimentDetail.Provisioned, experimentListView: ExperimentListView,
                                connectableDevice: BluetoothConnectableDevice, network: Subnet, callback: ProvisioningCallback) {
        Log.i("SiLab_Phase3", "========Time Start Provisioning========" + System.currentTimeMillis())
        meshLogic.deviceToConfigure = null

        provisionerConnection = ProvisionerConnection(connectableDevice, network)
        val configuration = ProvisionerConfiguration().apply {
            isKeepingProxyConnection = true
            isUsingOneGattConnection = true
        }
        if (oobType != ExperimentDetail.Provisioned.NON_OOB) {
            val oob = ProvisionedOOBControlImpl(experimentListView, oobType)
            provisionerConnection!!.provisionerOOB = oob
        }
        provisionerConnection!!.provision(configuration, null, callback)
    }
}
