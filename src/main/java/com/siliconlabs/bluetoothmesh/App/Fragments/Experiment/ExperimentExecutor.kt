/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.util.Log
import com.siliconlab.bluetoothmesh.adk.ErrorType
import com.siliconlab.bluetoothmesh.adk.configuration_control.*
import com.siliconlab.bluetoothmesh.adk.connectable_device.ConnectableDevice
import com.siliconlab.bluetoothmesh.adk.data_model.dcd.DeviceCompositionData
import com.siliconlab.bluetoothmesh.adk.data_model.element.Element
import com.siliconlab.bluetoothmesh.adk.data_model.group.Group
import com.siliconlab.bluetoothmesh.adk.data_model.model.Model
import com.siliconlab.bluetoothmesh.adk.data_model.model.ModelIdentifier
import com.siliconlab.bluetoothmesh.adk.data_model.model.SigModel
import com.siliconlab.bluetoothmesh.adk.data_model.node.Node
import com.siliconlab.bluetoothmesh.adk.data_model.node.NodeChangeNameException
import com.siliconlab.bluetoothmesh.adk.data_model.subnet.Subnet
import com.siliconlab.bluetoothmesh.adk.functionality_binder.FunctionalityBinder
import com.siliconlab.bluetoothmesh.adk.functionality_binder.FunctionalityBinderCallback
import com.siliconlab.bluetoothmesh.adk.functionality_control.*
import com.siliconlab.bluetoothmesh.adk.functionality_control.specific.GenericOnOff
import com.siliconlab.bluetoothmesh.adk.node_control.NodeControl
import com.siliconlab.bluetoothmesh.adk.node_control.NodeControlCallback
import com.siliconlab.bluetoothmesh.adk.notification_control.PublicationSettingsCallback
import com.siliconlab.bluetoothmesh.adk.notification_control.SubscriptionControl
import com.siliconlab.bluetoothmesh.adk.notification_control.SubscriptionSettingsCallback
import com.siliconlab.bluetoothmesh.adk.notification_control.settings.PublicationSettings
import com.siliconlab.bluetoothmesh.adk.notification_control.settings.SubscriptionSettings
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisioningCallback
import com.siliconlabs.bluetoothmesh.App.Logic.MeshLogic
import com.siliconlabs.bluetoothmesh.App.Logic.NetworkConnectionLogic
import com.siliconlabs.bluetoothmesh.App.Models.DeviceDescription
import com.siliconlabs.bluetoothmesh.App.Models.DeviceFunctionality
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.Experiment
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentProvisioningModel
import com.siliconlabs.bluetoothmesh.App.Models.MeshNetworkManager
import com.siliconlabs.bluetoothmesh.App.Models.MeshNodeManager
import com.siliconlabs.bluetoothmesh.App.Utils.Converters
import com.siliconlabs.bluetoothmesh.App.Utils.ErrorMessageConverter
import com.siliconlabs.bluetoothmesh.R
import java.util.concurrent.Executors

// TODO: Refactor using State pattern (with currentExperiment as state)
class ExperimentExecutor(
        private val context: Context,
        private val experimentListView: ExperimentListView,
        private val meshLogic: MeshLogic,
        private val meshNetworkManager: MeshNetworkManager,
        private val networkConnectionLogic: NetworkConnectionLogic,
        private val meshNodeManager: MeshNodeManager,
        private val bluetoothScanHandler: BluetoothScanHandler,
        private val deviceFunctionalityDb: MeshNodeManager
) {
    private lateinit var experiments: List<ExperimentDetail>
    private var currentExperiment = Experiment.SCAN_NODE_UUID_0E_0F
    private val handler = Handler()
    private val taskList = mutableListOf<Runnable>()
    private val taskListThird = mutableListOf<Runnable>()
    private val taskExecutor = Executors.newSingleThreadScheduledExecutor()
    private val taskExecutorThird = Executors.newSingleThreadScheduledExecutor()
    private lateinit var experimentParams: ExperimentParams
    var isFinished = false
    private val provisioningModel = ExperimentProvisioningModel(meshLogic)
    private lateinit var subnet: Subnet
    private lateinit var group: Group

    fun prepare(subnet: Subnet, group: Group, experiments: List<ExperimentDetail>) {
        Log.i(TAG, "prepare subnet=$subnet isFinished=$isFinished")
        this.subnet = subnet
        this.group = group
        this.experiments = experiments
        experimentParams = ExperimentParams()
        currentExperiment = Experiment.SCAN_NODE_UUID_0E_0F
        isFinished = false
    }

    fun start() {
        runExperiment()
    }

    fun onScanResult(deviceUuid: ByteArray, deviceDescription: DeviceDescription) {
        when (currentExperiment) {
            Experiment.SCAN_NODE_UUID_0E_0F,
            Experiment.SCAN_NODE_UUID_0E_1F,
            Experiment.SCAN_NODE_UUID_0E_2F,
            Experiment.SCAN_NODE_UUID_0E_3F -> {
                if (currentExperiment.node!!.isUuidMatching(deviceUuid)) {
                    currentExperiment.getAllIncompleteUsingSameNode().forEach {
                        experiments[it.ordinal].deviceDescription = deviceDescription
                    }
                    experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                    bluetoothScanHandler.stopScanner()
                    nextExperiment()
                }
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                if (Experiment.Node.FRIEND.isUuidMatching(deviceUuid)) {
                    experiments[currentExperiment.ordinal].deviceDescription = deviceDescription
                    experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                    bluetoothScanHandler.stopScanner()
                    handler.postDelayed({
                        provision()
                    }, 5000)
                }
            }
        }
    }

    fun onScanTimeout() {
        when (currentExperiment) {
            Experiment.SCAN_NODE_UUID_0E_0F,
            Experiment.SCAN_NODE_UUID_0E_1F,
            Experiment.SCAN_NODE_UUID_0E_2F -> {
                bluetoothScanHandler.stopScanner()
                nextExperiment(context.getString(R.string.message_error_expired_time, TIME_DELAY,
                        experiments[currentExperiment.ordinal].maxTime))
            }
            Experiment.SCAN_NODE_UUID_0E_3F -> {
                bluetoothScanHandler.stopScanner()
                nextExperiment(context.getString(R.string.message_error_expired_time, TIME_DELAY_LPN,
                        experiments[currentExperiment.ordinal].maxTime))
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                bluetoothScanHandler.stopScanner()
                nextExperiment(context.getString(R.string.message_error_expired_time_scanner_add_node,
                        experiments[currentExperiment.ordinal].timeEnd,
                        experiments[currentExperiment.ordinal].maxTime))
            }
        }
    }

    private val provisioningCallback = object : ProvisioningCallback {
        override fun error(device: ConnectableDevice, subnet: Subnet, error: ErrorType) {
            Log.e(TAG, "provisioning error: ${currentExperiment.name}")
            val errorMessage = context.getString(R.string.message_error_provisioning, currentExperiment.node?.uuid,
                    ErrorMessageConverter.convert(context, error))
            nextExperiment(errorMessage)
            handler.removeCallbacks(serviceNotFoundCallback)
        }

        override fun success(device: ConnectableDevice, subnet: Subnet, node: Node) {
            Log.i(TAG, "provisioning success: ${currentExperiment.name}")
            Log.i(TAG, "Dev_key ${currentExperiment.name}: ${Converters.getHexValue(node.devKey.key)}")
            val proxyConnection = provisioningModel.proxyConnection
            if (proxyConnection != null) {
                networkConnectionLogic.setEstablishedProxyConnection(proxyConnection, subnet)
                meshLogic.deviceToConfigure = meshNodeManager.getMeshNode(node)
                experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                node.name = currentExperiment.name
                node.primaryElementAddress
                saveProvisioningData(device, node)
            } else {
                Log.e(TAG, "provisioning: no proxy connection")
                nextExperiment(context.getString(R.string.message_error_provisioning, currentExperiment.node?.uuid,
                        "No proxy connection"))
            }
            handler.removeCallbacks(serviceNotFoundCallback)
        }
    }

    private fun connectToSubnet() {
        if (experiments[currentExperiment.ordinal].connectableDevice == null) {
            finishExperimentFail()
        } else if (!networkConnectionLogic.isConnected()) {
            networkConnectionLogic.connect(subnet)
        }
    }

    private fun provision() {
        if (experiments[currentExperiment.ordinal].deviceDescription != null) {
            experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
            val oobType: ExperimentDetail.Provisioned? = when (currentExperiment) {
                Experiment.PROVISIONING_NODE_UUID_0E_0F -> ExperimentDetail.Provisioned.NON_OOB
                Experiment.PROVISIONING_NODE_UUID_0E_1F -> ExperimentDetail.Provisioned.STATIC_OOB
                Experiment.PROVISIONING_NODE_UUID_0E_2F, Experiment.ADD_NODES_TO_NETWORK -> ExperimentDetail.Provisioned.OUTPUT_OOB
                Experiment.PROVISIONING_NODE_UUID_0E_3F -> ExperimentDetail.Provisioned.INPUT_OOB
                else -> null
            }
            if (oobType != null) {
                provisioningModel.provisionDevice(
                        oobType,
                        experiments[currentExperiment.ordinal].deviceDescription!!,
                        experimentListView,
                        subnet,
                        provisioningCallback
                )
                handler.postDelayed(serviceNotFoundCallback, 70000)
            }
            Log.i(TAG, "name device provisioning: ${currentExperiment.name}")
        } else {
            Log.e(TAG, "name device provisioning errorHigh: ${currentExperiment.name}")
            nextExperiment(context.getString(R.string.message_error_no_device))
        }
    }

    private val serviceNotFoundCallback = Runnable {
        //do workaround for Discovery service has no service
        Log.e(TAG, "disconnect when OnNotFoundService")
        if (experiments[currentExperiment.ordinal].deviceDescription!!.connectable_device!!.isConnected) {
            experiments[currentExperiment.ordinal].deviceDescription!!.connectable_device!!.disconnect()
        } else {
            onSubnetDisconnected()
        }
    }

    private fun scanWithDelay(scannerMode: Int, timeoutDelay: Long) {
        handler.postDelayed({
            experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
            bluetoothScanHandler.startScanner(scannerMode, timeoutDelay)
        }, 1000)
    }

    private fun saveProvisioningData(device: ConnectableDevice, node: Node) {
        if (currentExperiment == Experiment.ADD_NODES_TO_NETWORK) {
            experiments[currentExperiment.ordinal].connectableDevice = device
            experiments[currentExperiment.ordinal].meshNode = meshNodeManager.getMeshNode(node)
        } else {
            currentExperiment.getAllIncompleteUsingSameNode().forEach {
                experiments[it.ordinal].connectableDevice = device
                experiments[it.ordinal].meshNode = meshNodeManager.getMeshNode(node)
            }
        }
    }

    private fun controlUnicastWithAck() {
        Log.i(TAG, "modelUnicast start: ${currentExperiment.name}")
        if (experiments[currentExperiment.ordinal].meshNode != null) {
            if (experiments[currentExperiment.ordinal].isConfigFinished) {
                experimentParams.countElementSetResponse = 0
                sendUnicastOnOffSet()
            } else {
                nextExperiment(context.getString(R.string.message_error_configuration_node_fail))
            }
        } else {
            nextExperiment(context.getString(R.string.message_error_no_device))
        }
    }

    private fun factoryResetDeviceTask(node: Node) = Runnable {
        ConfigurationControl(node).factoryReset(object : FactoryResetCallback {
            override fun success(node: Node) {
                Log.i(TAG, "factoryResetDevice ${node.name} success: ${currentExperiment.name}")
                val meshNode = deviceFunctionalityDb.getMeshNode(node)
                deviceFunctionalityDb.removeNodeFunc(meshNode)
                takeNextTaskThird()
            }

            override fun error(node: Node, error: ErrorType) {
                Log.e(TAG, "factoryResetDevice ${node.name} error: ${currentExperiment.name}")
                if (currentExperiment == Experiment.REMOVE_NODES_IN_NETWORK) {
                    val message = "Remove device ${node.name} with element ${node.elements.first().address}:" +
                            ErrorMessageConverter.convert(context, error)
                    experiments[currentExperiment.ordinal].includeErrorMessage(message)
                    takeNextTaskThird()
                }
            }
        })
    }

    private fun sendUnicastGetTask(node: Node, group: Group) = Runnable {
        Log.i(TAG, "getOnOffStatus start: ${node.name}")
        val meshElementControl = ControlElement(node.elements[0], group)
        meshElementControl.getStatus(GenericOnOff(), object : GetElementStatusCallback<GenericOnOff> {
            override fun success(element: Element?, group: Group?, value: GenericOnOff?) {
                Log.i(TAG, "getOnOffStatus success: ${node.name}")
                when (currentExperiment) {
                    Experiment.CONTROL_GROUP,
                    Experiment.REMOVE_NODES_IN_NETWORK,
                    Experiment.ADD_NODES_TO_NETWORK,
                    Experiment.CONNECTION_NETWORK -> {
                        val isStatus = value!!.state == GenericOnOff.STATE.ON
                        Log.i(TAG,
                                "getOnOffStatus control success status $isStatus onOff ${experimentParams.onOff} ${experimentParams.countElementGetResponse}: ${node.name}")
                        if (isStatus == experimentParams.onOff) {
                            experimentParams.countElementGetResponse++
                        }
                        handler.postDelayed({
                            takeNextTask()
                        }, 5000)
                    }
                    Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                        val isStatus = value!!.state == GenericOnOff.STATE.ON
                        Log.i(TAG, "getOnOffStatus success status $isStatus onOff ${experimentParams.onOff}: ${node.name}")
                        if (isStatus == experimentParams.onOff) {
                            Log.i(TAG, "getOnOffStatus success status: ${currentExperiment.name}")
                            experiments[currentExperiment.ordinal].areResponsesCorrect = true
                        }
                        nextExperiment()
                    }
                }
            }

            override fun error(element: Element?, group: Group?, error: ErrorType?) {
                Log.e(TAG, "getOnOffStatus error:${node.name} error ${error!!.errorCode}")
                val messageError = "Get status for node ${node.name} with element address:${element!!.address}" +
                        ErrorMessageConverter.convert(context, error)
                when (currentExperiment) {
                    Experiment.CONNECTION_NETWORK,
                    Experiment.ADD_NODES_TO_NETWORK,
                    Experiment.REMOVE_NODES_IN_NETWORK,
                    Experiment.CONTROL_GROUP -> {
                        experiments[currentExperiment.ordinal].includeErrorMessage(messageError)
                        handler.postDelayed({
                            takeNextTask()
                        }, 5000)
                    }
                    Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                        handleErrorCallback(messageError)
                    }
                }
            }
        })
    }

    private fun runExperiment() {
        experiments[currentExperiment.ordinal].status = ExperimentDetail.Status.PROCESSING
        experiments[currentExperiment.ordinal].isDone = false
        experimentListView.notifyDataItem(isFinished)
        when (currentExperiment) {
            Experiment.SCAN_NODE_UUID_0E_0F,
            Experiment.SCAN_NODE_UUID_0E_1F -> {
                scanWithDelay(ScanSettings.SCAN_MODE_LOW_LATENCY, TIME_DELAY)
            }
            Experiment.SCAN_NODE_UUID_0E_2F -> {
                scanWithDelay(ScanSettings.SCAN_MODE_LOW_POWER, TIME_DELAY)
            }
            Experiment.SCAN_NODE_UUID_0E_3F -> {
                scanWithDelay(ScanSettings.SCAN_MODE_BALANCED, TIME_DELAY_LPN)
            }
            Experiment.PROVISIONING_NODE_UUID_0E_0F,
            Experiment.PROVISIONING_NODE_UUID_0E_1F,
            Experiment.PROVISIONING_NODE_UUID_0E_2F,
            Experiment.PROVISIONING_NODE_UUID_0E_3F -> {
                handler.postDelayed({
                    provision()
                }, 10000)
            }
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK -> {
                handler.removeCallbacks(responseTimeoutCallback)
                experimentParams.isRequestAck = true
                handler.postDelayed({
                    experimentParams.onOff = true
                    connectToSubnet()
                }, 1000)
            }
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK -> {
                handler.removeCallbacks(responseTimeoutCallback)
                experimentParams.isRequestAck = true
                experimentParams.onOff = true
                handler.postDelayed({
                    controlUnicastWithAck()
                }, 1000)
            }
            Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                handler.removeCallbacks(responseTimeoutCallback)
                experimentParams.isRequestAck = false
                experimentParams.onOff = false
                handler.postDelayed({
                    controlUnicastWithAck()
                }, 2000)
            }
            Experiment.CONTROL_GROUP -> {
                handler.postDelayed({
                    experimentParams.onOff = true
                    experimentParams.isRequestAck = false
                    controlMulticast()
                }, 1000)
            }
            Experiment.REMOVE_NODES_IN_NETWORK -> {
                handler.postDelayed({
                    removeNodeFromSubnet()
                }, 1000)
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                handler.postDelayed({
                    experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
                    bluetoothScanHandler.startScanner(ScanSettings.SCAN_MODE_LOW_POWER, experiments[currentExperiment.ordinal].maxTime)
                }, 10000)
            }
            Experiment.CONNECTION_NETWORK -> {
                handler.postDelayed({
                    reconnectToSubnet()
                }, 500)
            }
            Experiment.POST_TESTING -> {
                handler.postDelayed({
                    if (networkConnectionLogic.isConnected()) {
                        factoryResetSubnet()
                    } else if (meshLogic.currentNetwork!!.subnets.contains(subnet)) {
                        networkConnectionLogic.connect(subnet)
                    } else {
                        finishPostTesting()
                    }
                }, 500)
            }
        }
    }

    private fun factoryResetSubnet() {
        isDisconnectPostTesting = false
        Log.i(TAG, "modelFactoryResetNetwork start: ${currentExperiment.name}")
        meshNetworkManager.removeSubnet(subnet, object : MeshNetworkManager.DeleteNetworksCallback {
            override fun success() {
                Log.i(TAG, "modelFactoryResetNetwork success: ${currentExperiment.name}")
                removeNodesFunc()
                handler.postDelayed({
                    experiments[currentExperiment.ordinal].areResponsesCorrect = true
                    nextExperiment()
                }, 1000)
            }

            override fun error(subnet: Subnet?, error: ErrorType?) {
                Log.e(TAG, "modelFactoryResetNetwork error: ${currentExperiment.name}")
                if (subnet!!.nodes.run { size == 1 && Experiment.Node.PROXY.isUuidMatching(first().uuid) } && isDisconnectPostTesting) {
                    experiments[currentExperiment.ordinal].areResponsesCorrect = true
                } else {
                    experiments[currentExperiment.ordinal].error = ErrorMessageConverter.convert(context, error!!)
                    experiments[currentExperiment.ordinal].areResponsesCorrect = false
                }
                subnet.removeOnlyFromLocalStructure()
                removeNodesFunc()
                handler.postDelayed({
                    nextExperiment()
                }, 1000)
            }
        })
    }

    /**
     * Remove meshnode in local
     */
    private fun removeNodesFunc() {
        meshNodeManager.getMeshNodes(subnet).forEach {
            meshNodeManager.removeNodeFunc(it)
        }
    }

    private fun reconnectToSubnet() {
        if (networkConnectionLogic.isConnected()) {
            networkConnectionLogic.disconnect()
        } else {
            experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
            networkConnectionLogic.connect(subnet)
        }
    }

    private fun removeNodeFromSubnet() {
        Log.i(TAG, "modeRemoveNodeFromNetWork start: ${currentExperiment.name}")
        taskListThird.clear()
        val nodes = subnet.nodes
        nodes.find { Experiment.Node.LPN.isUuidMatching(it.uuid) }?.let {
            taskListThird.add(factoryResetDeviceTask(it))
        }
        nodes.find { Experiment.Node.FRIEND.isUuidMatching(it.uuid) }?.let {
            taskListThird.add(factoryResetDeviceTask(it))
        }

        Log.i(TAG, "modeRemoveNodeFromNetWork task size ${taskListThird.size}:")
        if (taskListThird.size > 0) {
            startTasksThird()
        } else {
            nextExperiment(context.getString(R.string.message_error_node_does_not_exist))
        }
    }

    /**
     * Function multicast automate
     */
    private fun controlMulticast() {
        if (networkConnectionLogic.isConnected()) {
            Log.i(TAG, "modeMulticast Connect: ${currentExperiment.name}")
            controlGroup()
        } else {
            Log.i(TAG, "modeMulticast not Connect: ${currentExperiment.name}")
            meshLogic.currentSubnet?.let { networkConnectionLogic.connect(it) }
        }
    }

    private fun controlGroup() {
        Log.i(TAG, "controlGroup start: ${currentExperiment.name}")
        experimentParams.countGroupSetResponse = 0
        val level = if (experimentParams.onOff) 500 else 0
        val nodesFromGroup = meshNodeManager.getMeshNodes(group)
        val onOffExist = nodesFromGroup.any { it.functionality.getAllModels().contains(ModelIdentifier.GenericOnOffServer) }
        if (onOffExist) {
            sendMulticastOnOffSet(level > 0)
        }
    }

    private fun sendMulticastOnOffSet(on: Boolean) {
        Log.i(TAG, "setOnOffGroup start ${experimentParams.isRequestAck} and ${experimentParams.onOff}: ${currentExperiment.name}")
        val generic = GenericOnOff()
        if (currentExperiment == Experiment.CONTROL_GROUP && experimentParams.onOff) {
            experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
        }
        generic.state = if (on) GenericOnOff.STATE.ON else GenericOnOff.STATE.OFF
        ControlGroup(group).setStatus(generic, experimentParams.requestParams,
                object : GenericGroupHandler<GenericOnOff?> {
                    override fun success(element: Element?, group: Group?, value: GenericOnOff?) {
                        experimentParams.countGroupSetResponse++
                        Log.i(TAG,
                                "setOnOffGroup end ${experimentParams.countGroupSetResponse}: ${currentExperiment.name}")
                        if (experimentParams.countGroupSetResponse == 1) {
                            if (currentExperiment == Experiment.CONTROL_GROUP && experimentParams.onOff) {
                                experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                            }
                            taskList.clear()
                            group!!.nodes.forEach {
                                addNodeToStackRemove(it)
                            }
                            handler.postDelayed({
                                startTasks()
                            }, 5000)
                        }
                    }

                    override fun error(group: Group?, error: ErrorType?) {
                        Log.e(TAG, "setOnOffGroup error: ${currentExperiment.name}")
                        handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
                    }
                })
    }

    private fun addNodeToStackRemove(node: Node) {
        when (currentExperiment) {
            Experiment.REMOVE_NODES_IN_NETWORK -> {
                if (listOf(Experiment.Node.PROXY, Experiment.Node.RELAY).any { it.isUuidMatching(node.uuid) }) {
                    Log.e(TAG, "Add node to task getStatus: ${node.name}")
                    taskList.add(sendUnicastGetTask(node, node.groups.first()))
                }
            }
            Experiment.ADD_NODES_TO_NETWORK,
            Experiment.CONNECTION_NETWORK -> {
                if (listOf(Experiment.Node.PROXY, Experiment.Node.RELAY, Experiment.Node.FRIEND).any { it.isUuidMatching(node.uuid) }) {
                    Log.i(TAG, "Add node to task getStatus: ${node.name}")
                    taskList.add(sendUnicastGetTask(node, node.groups.first()))
                }
            }
            else -> {
                Log.i(TAG, "setOnOffGroup end: ${node.name}")
                taskList.add(sendUnicastGetTask(node, node.groups.first()))
            }
        }
    }

    /**
     * Update test case
     */
    fun nextExperiment(errorMessage: String? = null) {
        experimentParams.resetAfterExperiment()
        experiments[currentExperiment.ordinal].isDone = true
        errorMessage?.let { experiments[currentExperiment.ordinal].includeErrorMessage(errorMessage) }
        experimentListView.notifyDataItem(isFinished)

        if (currentExperiment == Experiment.POST_TESTING) {
            finishPostTesting()
        } else {
            currentExperiment = currentExperiment.getNext()!!
        }

        if (!isFinished) {
            Log.i(TAG, "startUiByPositionExperiment ${currentExperiment.name}")
            handler.postDelayed({
                runExperiment()
            }, 200)
        }
    }

    private fun stopExperiment() {
        isFinished = true
        experimentListView.notifyDataItem(isFinished)
        experiments.forEach { Log.i(TAG, "$it") }
    }

    private fun configProxyTask() = Runnable {
        Log.i(TAG, "configProxy start ${experimentParams.isProxy}: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).setProxy(experimentParams.isProxy,
                object : PresenterSetNodeBehaviourCallback() {
                    override fun success(node: Node?, enabled: Boolean) {
                        Log.i(TAG, "configProxy success:$enabled ${currentExperiment.name}")
                        takeNextTask()
                    }
                })
    }

    private fun configRelayTask() = Runnable {
        Log.i(TAG, "configRelay start: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).setRelay(
                experimentParams.isRelay,
                RETRANSMISSION_COUNT,
                RETRANSMISSION_INTERVAL,
                object : PresenterSetNodeBehaviourCallback() {
                    override fun success(node: Node?, enabled: Boolean) {
                        Log.i(TAG, "configRelay success:$enabled ${currentExperiment.name}")
                        takeNextTask()
                    }
                }
        )
    }

    private fun configRetransmissionTask() = Runnable {
        Log.i(TAG, "configRetransmission start: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).setRetransmissionConfiguration(RETRANSMISSION_COUNT,
                RETRANSMISSION_INTERVAL, object : NodeRetransmissionConfigurationCallback {
            override fun success(node: Node?, retransmissionCount: Int, retransmissionInterval: Int) {
                Log.i(TAG, "configRetransmission success: ${currentExperiment.name}")
                takeNextTask()
            }

            override fun error(node: Node?, error: ErrorType?) {
                Log.e(TAG, "configRetransmission error: ${currentExperiment.name}")
                handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
            }
        })
    }

    private fun configFriendTask() = Runnable {
        Log.i(TAG, "configFriend start: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).setFriend(experimentParams.isFriend,
                object : PresenterSetNodeBehaviourCallback() {
                    override fun success(node: Node?, enabled: Boolean) {
                        Log.i(TAG, "configFriend success $enabled: ${currentExperiment.name}")
                        takeNextTask()
                    }
                })
    }

    private fun getDeviceCompositionDataTask() = Runnable {
        Log.i(TAG, "getDeviceCompositionData start: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).getDeviceCompositionData(0,
                object : GetDeviceCompositionDataCallback {
                    override fun error(node: Node, error: ErrorType?) {
                        Log.e(TAG, "getDeviceCompositionData error: ${currentExperiment.name}")
                        handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
                    }

                    override fun success(node: Node, deviceCompositionData: DeviceCompositionData?, elements: Array<out Element>?) {
                        when (currentExperiment) {
                            Experiment.ADD_NODES_TO_NETWORK -> {
                                experiments[currentExperiment.ordinal].meshNode = meshNodeManager.getMeshNode(node)
                            }
                            else -> {
                                currentExperiment.getAllIncompleteUsingSameNode().forEach {
                                    experiments[it.ordinal].meshNode = meshNodeManager.getMeshNode(node)
                                }
                            }
                        }
                        Log.i(TAG, "getDeviceCompositionData success: ${currentExperiment.name}")
                        takeNextTask()
                    }
                })
    }

    private fun enableNodeIdentityTask() = Runnable {
        Log.i(TAG, "enableNodeIdentity start: ${currentExperiment.name}")
        ConfigurationControl(experiments[currentExperiment.ordinal].meshNode!!.node).setNodeIdentity(
                true,
                subnet,
                object : SetNodeBehaviourCallback {
                    override fun error(node: Node?, error: ErrorType?) {
                        Log.e(TAG, "enableNodeIdentity error: ${currentExperiment.name}")
                        handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
                    }

                    override fun success(node: Node?, enabled: Boolean) {
                        Log.i(TAG, "enableNodeIdentity success: ${currentExperiment.name}")
                        takeNextTask()
                    }
                }
        )
    }

    private fun changeGroupTasks(newGroup: Group?): List<Runnable> {
        val tasks = mutableListOf<Runnable>()
        if (experiments[currentExperiment.ordinal].meshNode!!.node.groups.isNotEmpty()) {
            val oldGroup = experiments[currentExperiment.ordinal].meshNode!!.node.groups.first()
            tasks.addAll(unbindModelFromGroupTasks(oldGroup))
            tasks.add(unbindFromGroupTask(oldGroup))
        }
        if (newGroup != null) {
            tasks.add(bindToGroupTask(newGroup))
            tasks.addAll(bindModelToGroupTasks(newGroup, experiments[currentExperiment.ordinal].meshNode!!.functionality))
        }
        return tasks
    }

    private val responseTimeoutCallback = Runnable {
        when (currentExperiment) {
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                nextExperiment(
                        context.getString(R.string.message_error_expired_time, TIME_DELAY, experiments[currentExperiment.ordinal].maxTime))
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                if (experiments[currentExperiment.ordinal].isConfigFinished) {
                    experiments[currentExperiment.ordinal].areResponsesCorrect = false
                    nextExperiment()
                }
            }
        }
    }

    private fun delayControlNode() {
        when (currentExperiment) {
            Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK,
            Experiment.ADD_NODES_TO_NETWORK -> {
                handler.postDelayed(responseTimeoutCallback, TIME_DELAY)
            }
        }
    }

    private fun handleUnicastControlResponse(value: GenericOnOff?) {
        when (currentExperiment) {
            Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                handleUnicastControlResponseForNoAck()
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                handleUnicastControlResponseForAddNode(value)
            }
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK -> {
                Log.i(TAG, "modeUnicast with ack success: ${currentExperiment.name}")
                if (experimentParams.countElementSetResponse == 1) {
                    val isStatus = value!!.state == GenericOnOff.STATE.ON
                    if (isStatus == experimentParams.onOff && subnet.nodes.size >= 3) {
                        experiments[currentExperiment.ordinal].areResponsesCorrect = true
                    }
                    experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                    nextExperiment()
                }
            }
        }
    }

    private fun handleUnicastControlResponseForNoAck() {
        if (experimentParams.countElementSetResponse == 1) {
            experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
            Log.i(TAG, "modeUnicast without ack success: ${currentExperiment.name}")
            val group = experiments[currentExperiment.ordinal].meshNode!!.node.groups.first()
            if (group != null) {
                handler.postDelayed(
                        sendUnicastGetTask(experiments[currentExperiment.ordinal].meshNode!!.node, group),
                        10000
                )
            } else {
                experiments[currentExperiment.ordinal].areResponsesCorrect = false
                nextExperiment()
            }
        }
    }

    private fun handleUnicastControlResponseForAddNode(value: GenericOnOff?) {
        if (experimentParams.countElementSetResponse == 1) {
            if (experimentParams.onOff) {
                experimentParams.isRequestAck = true
                experimentParams.countElementSetResponse = 0
                experimentParams.onOff = false
                handler.postDelayed({
                    sendUnicastOnOffSet()
                }, 3000)
            } else {
                val isStatus = value!!.state == GenericOnOff.STATE.ON
                if (isStatus == experimentParams.onOff && subnet.nodes.size >= 3) {
                    experiments[currentExperiment.ordinal].areResponsesCorrect = true
                }
                experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                nextExperiment()
            }
        }
    }

    private fun sendUnicastOnOffSet() {
        Log.i(TAG, "modeUnicast start with ${experimentParams.isRequestAck} and ${experimentParams.onOff}: ${currentExperiment.name}")
        val generic = GenericOnOff()
        generic.state = if (experimentParams.onOff) GenericOnOff.STATE.ON else GenericOnOff.STATE.OFF
        if (experiments[currentExperiment.ordinal].meshNode?.let { it.node.elements[0] } != null) {
            delayControlNode()
            val meshElementControl = ControlElement(experiments[currentExperiment.ordinal].meshNode!!.node.elements[0],
                    experiments[currentExperiment.ordinal].meshNode!!.node.groups.first())
            experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
            meshElementControl.setStatus(generic, experimentParams.requestParams, object : SetElementStatusCallback<GenericOnOff> {
                override fun success(element: Element?, group: Group?, value: GenericOnOff?) {
                    handler.removeCallbacks(responseTimeoutCallback)
                    experimentParams.countElementSetResponse++
                    Log.i(TAG,
                            "modeUnicast success ${experimentParams.countElementSetResponse}: ${currentExperiment.name}")
                    handleUnicastControlResponse(value)
                }

                override fun error(element: Element?, group: Group?, error: ErrorType?) {
                    Log.e(TAG, "modeUnicast failed: ${currentExperiment.name}")
                    handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
                }
            })
        } else nextExperiment("Don't have element")
    }

    private fun unbindModelFromGroupTasks(group: Group): List<Runnable> {
        val tasks = mutableListOf<Runnable>()
        DeviceFunctionality.getSigModels(
                experiments[currentExperiment.ordinal].meshNode!!.node,
                experiments[currentExperiment.ordinal].meshNode!!.functionality
        ).forEach { model ->
            tasks.add(0, unbindModelFromGroupTask(model, group))
            if (model.isSupportSubscribe) {
                tasks.add(0, removeSubscriptionSettingsTask(model, group))
            }
            if (model.isSupportPublish) {
                tasks.add(0, clearPublicationSettingsTask(model))
            }
        }
        return tasks
    }

    private fun unbindFromGroupTask(group: Group) = Runnable {
        Log.i(TAG, "unbindFromGroup start: ${currentExperiment.name}")
        NodeControl(experiments[currentExperiment.ordinal].meshNode!!.node).unbind(group, PresenterNodeControlCallback())
    }

    private fun bindToGroupTask(group: Group) = Runnable {
        Log.i(TAG, "bindToGroup start: ${currentExperiment.name}")
        NodeControl(experiments[currentExperiment.ordinal].meshNode!!.node).bind(group, PresenterNodeControlCallback())
    }

    private fun bindModelToGroupTasks(group: Group, functionality: DeviceFunctionality.FUNCTIONALITY): List<Runnable> {
        val tasks = mutableListOf<Runnable>()
        DeviceFunctionality.getSigModels(experiments[currentExperiment.ordinal].meshNode!!.node, functionality).forEach { model ->
            if (model.isSupportPublish) {
                tasks.add(0, setPublicationSettingsTask(model, group, functionality))
            }
            if (model.isSupportSubscribe) {
                tasks.add(0, addSubscriptionSettingsTask(model, group))
            }
            tasks.add(0, bindModelToGroupTask(model, group))
        }
        return tasks
    }

    private fun changeFunctionality(newFunctionality: DeviceFunctionality.FUNCTIONALITY) {
        if (experiments[currentExperiment.ordinal].meshNode!!.node.groups.isEmpty()) {
            deviceFunctionalityDb.removeNodeFunc(experiments[currentExperiment.ordinal].meshNode!!)
            return
        }
        val group = experiments[currentExperiment.ordinal].meshNode!!.node.groups.first()
        group?.let {
            taskListThird.addAll(unbindModelFromGroupTasks(it))
            taskListThird.addAll(bindModelToGroupTasks(it, newFunctionality))
        }
        taskListThird.add(changeFunctionalityTask(newFunctionality))
        startTasksThird()
    }

    private fun changeFunctionalityTask(functionality: DeviceFunctionality.FUNCTIONALITY) = Runnable {
        try {
            deviceFunctionalityDb.updateNodeFunc(experiments[currentExperiment.ordinal].meshNode!!, functionality)
            takeNextTaskThird()
        } catch (e: NodeChangeNameException) {
            error(e)
        }
    }

    private fun unbindModelFromGroupTask(model: Model, group: Group) = Runnable {
        Log.i(TAG, "unbindModelFromGroup: ${currentExperiment.name}")
        val functionalityBinder = FunctionalityBinder(group)
        functionalityBinder.unbindModel(model, PresenterFunctionalityBinderCallback())
    }

    private fun removeSubscriptionSettingsTask(model: SigModel, group: Group) = Runnable {
        Log.i(TAG, "removeSubscriptionSettings: ${currentExperiment.name}")
        val subscriptionSettings = SubscriptionSettings(group)
        val subscriptionControl = SubscriptionControl(model)
        subscriptionControl.removeSubscriptionSettings(subscriptionSettings, PresenterSubscriptionSettingsCallback())
    }

    private fun clearPublicationSettingsTask(model: SigModel) = Runnable {
        Log.i(TAG, "clearPublicationSettings: ${currentExperiment.name}")
        val subscriptionControl = SubscriptionControl(model)
        subscriptionControl.clearPublicationSettings(PresenterPublicationSettingsCallback())
    }

    private fun setPublicationSettingsTask(model: SigModel, group: Group, functionality: DeviceFunctionality.FUNCTIONALITY) = Runnable {
        Log.i(TAG, "setPublicationSettings: ${currentExperiment.name}")
        val subscriptionControl = SubscriptionControl(model)
        val publicationSettings = PublicationSettings(group)
        if (functionality == DeviceFunctionality.FUNCTIONALITY.SensorServer) {
            publicationSettings.period = 20
        }
        publicationSettings.ttl = 5
        subscriptionControl.setPublicationSettings(publicationSettings, PresenterPublicationSettingsCallback())
    }

    private fun addSubscriptionSettingsTask(model: SigModel, group: Group) = Runnable {
        Log.i(TAG, "addSubscriptionSettings: ${currentExperiment.name}")
        val subscriptionControl = SubscriptionControl(model)
        val subscriptionSettings = SubscriptionSettings(group)
        subscriptionControl.addSubscriptionSettings(subscriptionSettings, PresenterSubscriptionSettingsCallback())
    }

    private fun bindModelToGroupTask(model: SigModel, group: Group) = Runnable {
        Log.i(TAG, "bindModelToGroup: ${currentExperiment.name}")
        val functionalityBinder = FunctionalityBinder(group)
        functionalityBinder.bindModel(model, PresenterFunctionalityBinderCallback())
    }

    private fun startTasks() {
        if (taskList.isNotEmpty()) {
            takeNextTask()
        }
    }

    private fun delayExecutor(task: Runnable) {
        when (currentExperiment) {
            Experiment.REMOVE_NODES_IN_NETWORK,
            Experiment.CONTROL_GROUP,
            Experiment.CONNECTION_NETWORK -> {
                handler.postDelayed({ taskExecutor.execute(task) }, 2000)
            }
            else -> {
                taskExecutor.execute(task)
            }
        }
    }

    private fun takeNextTask() {
        if (taskList.isNotEmpty()) {
            val task = taskList.first()
            taskList.remove(task)
            delayExecutor(task)
        } else {
            when (currentExperiment) {
                Experiment.PROVISIONING_NODE_UUID_0E_0F,
                Experiment.PROVISIONING_NODE_UUID_0E_1F,
                Experiment.PROVISIONING_NODE_UUID_0E_2F,
                Experiment.PROVISIONING_NODE_UUID_0E_3F -> {
                    handleProvisioningTestCase()
                }
                Experiment.CONTROL_GROUP,
                Experiment.CONNECTION_NETWORK -> {
                    Log.i(TAG, "Control multicast with loop: ${currentExperiment.name}")
                    loopControlGroup()
                }
                Experiment.ADD_NODES_TO_NETWORK -> {
                    experimentParams.isFunctionality = true
                    Log.i(TAG, "processSelectGroup end: ${currentExperiment.name}")
                    DeviceFunctionality.getFunctionalitiesNamed(experiments[currentExperiment.ordinal].meshNode?.node ?: return).forEach {
                        if (it.functionalityName == "Generic OnOff Server") {
                            Log.i(TAG, "processChangeFunctionality start: ${currentExperiment.name}")
                            changeFunctionality(it.functionality)
                        }
                    }
                }
            }
        }
    }

    private fun handleProvisioningTestCase() {
        if (experimentParams.isFunctionality) {
            loopControlGroup()
        } else {
            experimentParams.isFunctionality = true
            Log.i(TAG, "processSelectGroup end: ${currentExperiment.name}")
            DeviceFunctionality.getFunctionalitiesNamed(experiments[currentExperiment.ordinal].meshNode!!.node).toMutableList().forEach {
                if (it.functionalityName == "Generic OnOff Server") {
                    Log.i(TAG, "processChangeFunctionality start: ${currentExperiment.name}")
                    changeFunctionality(it.functionality)
                }
            }
        }
    }

    private fun loopControlGroup() {
        if (experimentParams.onOff) {
            experimentParams.isRequestAck = false
            Log.i(TAG, " Control groups 2: ${currentExperiment.name}")
            handler.postDelayed({
                experimentParams.onOff = false
                controlGroup()
            }, 5000)
        } else {
            when (currentExperiment) {
                Experiment.CONTROL_GROUP -> {
                    updateExperimentResult(8)
                    nextExperiment()
                }
                Experiment.CONNECTION_NETWORK -> {
                    updateExperimentResult(6)
                    nextExperiment()
                }
                Experiment.REMOVE_NODES_IN_NETWORK -> {
                    Log.i(TAG,
                            " Control groups(get status) ${experimentParams.countElementGetResponse}: ${currentExperiment.name}")
                    updateExperimentResult(4)
                    if (networkConnectionLogic.isConnected())
                        networkConnectionLogic.disconnect()
                }
            }
        }
    }

    private fun updateExperimentResult(expectedStatuses: Int) {
        Log.i(TAG,
                " Control groups(get status) ${experimentParams.countElementGetResponse}: ${currentExperiment.name}")
        if (experimentParams.countElementGetResponse == expectedStatuses) {
            experiments[currentExperiment.ordinal].areResponsesCorrect = true
        }
    }

    private fun startTasksThird() {
        if (taskListThird.isNotEmpty()) {
            takeNextTaskThird()
        }
    }

    private fun takeNextTaskThird() {
        if (taskListThird.isNotEmpty()) {
            val task = taskListThird.first()
            taskListThird.remove(task)
            taskExecutorThird.execute(task)
        } else {
            when (currentExperiment) {
                Experiment.PROVISIONING_NODE_UUID_0E_0F,
                Experiment.PROVISIONING_NODE_UUID_0E_1F,
                Experiment.PROVISIONING_NODE_UUID_0E_2F,
                Experiment.PROVISIONING_NODE_UUID_0E_3F -> {
                    Log.i(TAG, "processChangeFunctionality finish: ${currentExperiment.name}")
                    currentExperiment.getAllIncompleteUsingSameNode().forEach {
                        experiments[it.ordinal].isConfigFinished = true
                    }
                    networkConnectionLogic.disconnect()
                }
                Experiment.REMOVE_NODES_IN_NETWORK -> {
                    experiments[currentExperiment.ordinal].areResponsesCorrect = subnet.nodes.size == 2
                    if (networkConnectionLogic.isConnected()) {
                        networkConnectionLogic.disconnect()
                    }
                }
                Experiment.ADD_NODES_TO_NETWORK -> {
                    Log.i(TAG, "processChangeFunctionality finish: ${currentExperiment.name}")
                    experiments[currentExperiment.ordinal].isConfigFinished = true
                    networkConnectionLogic.disconnect()
                }
            }
        }
    }

    private fun configNode() {
        taskList.clear()
        Log.i(TAG,
                "connected proxy: ${experimentParams.isProxy} relay:${experimentParams.isRelay} friend ${experimentParams.isFriend}: ${currentExperiment.name}")
        when (currentExperiment) {
            Experiment.PROVISIONING_NODE_UUID_0E_0F -> {
                experimentParams.prepareConfig(isProxy = true)
                taskList.apply {
                    add(configProxyTask())
                    add(getDeviceCompositionDataTask())
                    add(enableNodeIdentityTask())
                    add(configRelayTask())
                    add(configFriendTask())
                    add(configRetransmissionTask())
                    addAll(changeGroupTasks(group))
                }
                startTasks()
            }
            Experiment.PROVISIONING_NODE_UUID_0E_1F -> {
                experimentParams.prepareConfig(isRelay = true)
                taskList.apply {
                    add(getDeviceCompositionDataTask())
                    add(configRelayTask())
                    add(configFriendTask())
                    add(configRetransmissionTask())
                    addAll(changeGroupTasks(group))
                    add(configProxyTask())
                }
                startTasks()
            }
            Experiment.PROVISIONING_NODE_UUID_0E_2F -> {
                experimentParams.prepareConfig(isFriend = true)
                taskList.apply {
                    add(getDeviceCompositionDataTask())
                    add(configRelayTask())
                    add(configFriendTask())
                    add(configRetransmissionTask())
                    addAll(changeGroupTasks(group))
                    add(configProxyTask())
                }
                startTasks()
            }
            Experiment.PROVISIONING_NODE_UUID_0E_3F -> {
                experimentParams.prepareConfig()
                taskList.apply {
                    add(getDeviceCompositionDataTask())
                    add(configRetransmissionTask())
                    addAll(changeGroupTasks(group))
                    add(configProxyTask())
                }
                startTasks()
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                experimentParams.prepareConfig()
                taskList.apply {
                    add(getDeviceCompositionDataTask())
                    add(configRelayTask())
                    add(configFriendTask())
                    add(configRetransmissionTask())
                    addAll(changeGroupTasks(group))
                    add(configProxyTask())
                }
                startTasks()
            }
        }
    }

    private fun finishPostTesting() {
        if (networkConnectionLogic.isConnected()) {
            networkConnectionLogic.disconnect()
        }
        stopExperiment()
    }

    // callback classes
    inner class PresenterNodeControlCallback : NodeControlCallback {
        override fun succeed() {
            Log.i(TAG,
                    "NodeControlCallback succeed:isFunctionality:${experimentParams.isFunctionality}: ${currentExperiment.name}")
            if (!experimentParams.isFunctionality) {
                takeNextTask()
            } else {
                takeNextTaskThird()
            }
        }

        override fun error(error: ErrorType) {
            Log.e(TAG, "PresenterNodeControlCallback error: ${currentExperiment.name}")
            handleErrorCallback(ErrorMessageConverter.convert(context, error))
        }
    }

    inner class PresenterFunctionalityBinderCallback : FunctionalityBinderCallback {
        override fun succeed(succeededModels: MutableList<Model>?, group: Group?) {
            Log.i(TAG,
                    "FunctionalityBinderCallback succeed:isFunctionality:${experimentParams.isFunctionality}: ${currentExperiment.name}")
            if (!experimentParams.isFunctionality) {
                takeNextTask()
            } else {
                takeNextTaskThird()
            }
        }

        override fun error(failedModels: MutableList<Model>?, group: Group?, error: ErrorType?) {
            Log.e(TAG, "PresenterFunctionalityBinderCallback error: ${currentExperiment.name}")
            handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
        }
    }

    inner class PresenterPublicationSettingsCallback : PublicationSettingsCallback {
        override fun success(meshModel: Model?, publicationSettings: PublicationSettings?) {
            Log.i(TAG,
                    "PublicationSettingsGenericCallback success:isFunctionality:${experimentParams.isFunctionality}: ${currentExperiment.name}")
            if (!experimentParams.isFunctionality) {
                takeNextTask()
            } else {
                takeNextTaskThird()
            }
        }

        override fun error(meshModel: Model?, error: ErrorType?) {
            Log.e(TAG, "PresenterPublicationSettingsCallback error: ${currentExperiment.name}")
            handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
        }
    }

    inner class PresenterSubscriptionSettingsCallback : SubscriptionSettingsCallback {
        override fun success(meshModel: Model?, subscriptionSettings: SubscriptionSettings?) {
            Log.i(TAG,
                    "SubscriptionSettingsGenericCallback success:isFunctionality:${experimentParams.isFunctionality}: ${currentExperiment.name}")
            if (!experimentParams.isFunctionality) {
                takeNextTask()
            } else {
                takeNextTaskThird()
            }
        }

        override fun error(meshModel: Model?, error: ErrorType?) {
            Log.e(TAG, "PresenterSubscriptionSettingsCallback error: ${currentExperiment.name}")
            handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
        }
    }

    abstract inner class PresenterSetNodeBehaviourCallback : SetNodeBehaviourCallback {
        override fun error(node: Node?, error: ErrorType?) {
            Log.e(TAG, "PresenterSetNodeBehaviourCallback error: ${currentExperiment.name}")
            handleErrorCallback(ErrorMessageConverter.convert(context, error!!))
        }
    }

    private fun handleErrorCallback(errorMessage: String) {
        when (currentExperiment) {
            Experiment.PROVISIONING_NODE_UUID_0E_0F,
            Experiment.PROVISIONING_NODE_UUID_0E_1F,
            Experiment.PROVISIONING_NODE_UUID_0E_2F,
            Experiment.PROVISIONING_NODE_UUID_0E_3F,
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK -> {
                Log.i(TAG, "handlerError connect proxy: ${currentExperiment.name}")
                networkConnectionLogic.disconnect()
            }
            else -> {
                nextExperiment(errorMessage)
            }
        }
    }

    fun onSubnetConnected() {
        Log.i(TAG, "connected: ${currentExperiment.name}")
        when (currentExperiment) {
            Experiment.PROVISIONING_NODE_UUID_0E_0F,
            Experiment.PROVISIONING_NODE_UUID_0E_1F,
            Experiment.PROVISIONING_NODE_UUID_0E_2F,
            Experiment.PROVISIONING_NODE_UUID_0E_3F -> {
                Log.i(TAG, "connected add task standard: ${currentExperiment.name}")
                configNode()
            }
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK -> {
                connectProxyAttempts = 0
                controlUnicastWithAck()
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                if (experiments[currentExperiment.ordinal].isConfigFinished) {
                    experimentParams.isRequestAck = true
                    experimentParams.onOff = true
                    experimentParams.countElementSetResponse = 0
                    sendUnicastOnOffSet()
                } else {
                    Log.i(TAG, "connected add task standard: ${currentExperiment.name}")
                    configNode()
                }
            }
            Experiment.CONNECTION_NETWORK -> {
                experiments[currentExperiment.ordinal].areResponsesCorrect = true
                experiments[currentExperiment.ordinal].timeEnd = System.currentTimeMillis()
                nextExperiment()
            }
            Experiment.POST_TESTING -> {
                handler.postDelayed({
                    factoryResetSubnet()
                }, 500)
            }
        }
    }

    private var connectProxyAttempts = 0
    fun onSubnetDisconnected() {
        Log.i(TAG, "disconnected: ${currentExperiment.name}")
        when (currentExperiment) {
            Experiment.PROVISIONING_NODE_UUID_0E_0F,
            Experiment.PROVISIONING_NODE_UUID_0E_1F,
            Experiment.PROVISIONING_NODE_UUID_0E_2F,
            Experiment.PROVISIONING_NODE_UUID_0E_3F,
            Experiment.REMOVE_NODES_IN_NETWORK -> {
                nextExperiment()
            }
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK -> {
                if (++connectProxyAttempts == 3) {
                    finishExperimentFail()
                } else {
                    handler.postDelayed({
                        connectToSubnet()
                    }, 5000)
                }
            }
            Experiment.ADD_NODES_TO_NETWORK -> {
                handler.postDelayed({
                    connectToSubnet()
                }, 10000)
            }
            Experiment.CONNECTION_NETWORK -> {
                handler.postDelayed({
                    if (!networkConnectionLogic.isConnected()) {
                        experiments[currentExperiment.ordinal].timeStart = System.currentTimeMillis()
                        networkConnectionLogic.connect(subnet)
                    }
                }, 10000)
            }
            Experiment.POST_TESTING -> {
                isDisconnectPostTesting = true
            }
        }
    }

    private var isDisconnectPostTesting = false
    private fun finishExperimentFail() {
        for (i in currentExperiment.ordinal until experiments.size) {
            val messageError = context.getString(R.string.message_error_cannot_connect_proxy)
            with(experiments[i]) {
                error = if (experiments[i].error != null) "${experiments[currentExperiment.ordinal].error}; $messageError" else messageError
                isDone = true
                status = ExperimentDetail.Status.FAIL
            }
        }
        isFinished = true
        experimentListView.notifyDataItem(isFinished)
        experiments.forEach { Log.i(TAG, "$it") }
    }

    companion object {
        private const val RETRANSMISSION_COUNT = 3
        private const val RETRANSMISSION_INTERVAL = 20
        private const val TIME_DELAY = 50000L
        private const val TIME_DELAY_LPN = 100000L
        private const val TAG = "ExperimentListPresenter"
    }
}
