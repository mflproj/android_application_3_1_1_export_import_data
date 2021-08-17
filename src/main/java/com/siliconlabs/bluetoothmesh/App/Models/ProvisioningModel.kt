/*
 * Copyright © 2019 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models

import com.siliconlab.bluetoothmesh.adk.ErrorType
import com.siliconlab.bluetoothmesh.adk.connectable_device.ConnectableDevice
import com.siliconlab.bluetoothmesh.adk.data_model.node.Node
import com.siliconlab.bluetoothmesh.adk.data_model.subnet.Subnet
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerConnection
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisioningCallback
import com.siliconlabs.bluetoothmesh.App.Logic.MeshLogic
import com.siliconlabs.bluetoothmesh.App.Models.ProvisioningStatusPropagator.ProvisioningStatus

class ProvisioningModel(val meshLogic: MeshLogic, val meshNodeManager: MeshNodeManager) : ProvisioningCallback {
    val provisioningStatusPropagator = ProvisioningStatusPropagator()

    var selectedDevice: DeviceDescription? = null
    internal var provisioned = false
    internal var networkInfo: Subnet? = null
    private var provisionedDeviceName: String = ""

    private fun findNodeIfAlreadyAdded(): Node? {
        val uuid = selectedDevice!!.connectable_device?.uuid ?: return null

        return meshLogic.currentNetwork?.subnets?.flatMap { it.nodes }
                ?.find { it.uuid?.contentEquals(uuid) == true }
    }

    fun provisionDevice(networkInfo: Subnet, name: String) {
        provisionedDeviceName = name.takeIf { it.isNotBlank() } ?: "Unknown"
        this.networkInfo = networkInfo
        meshLogic.currentSubnet = networkInfo
        provisioned = false

        val connectableDevice = selectedDevice!!.connectable_device!!
        val existingNode = findNodeIfAlreadyAdded()
        if (existingNode != null) {
            selectedDevice!!.existed_node = existingNode
            provisioningStatusPropagator.propagateProvisioningStatus(ProvisioningStatus.DeviceAlreadyAdded)
        } else {
            provisionDevice(connectableDevice, networkInfo)
        }
    }

    fun provisionDevice(connectableDevice: BluetoothConnectableDevice, network: Subnet) {
        ProvisionerConnection(connectableDevice, network).provision(null, null, this)
    }

    override fun success(device: ConnectableDevice, subnet: Subnet, node: Node) {
        meshLogic.provisionedBluetoothConnectableDevice = selectedDevice!!.connectable_device!!
        provisioned = true
        node.name = provisionedDeviceName
        meshLogic.deviceToConfigure = meshNodeManager.getMeshNode(node)
        provisioningStatusPropagator.propagateProvisioningStatus(ProvisioningStatus.ProvisioningSuccessful)
    }

    override fun error(device: ConnectableDevice, subnet: Subnet, error: ErrorType) {
        provisioningStatusPropagator.propagateProvisioningError(ProvisioningStatus.ErrorDuringProvisioning, error)
    }

    fun getNetworkInfo(): Subnet {
        return networkInfo!!
    }
}
