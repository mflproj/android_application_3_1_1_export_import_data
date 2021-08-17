/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models.Experiment

import androidx.annotation.StringRes
import com.siliconlab.bluetoothmesh.adk.connectable_device.ConnectableDevice
import com.siliconlabs.bluetoothmesh.App.ModelView.MeshNode
import com.siliconlabs.bluetoothmesh.App.Models.DeviceDescription
import com.siliconlabs.bluetoothmesh.R

class ExperimentDetail(
        var id: Experiment,
        var maxTime: Long
) {
    var status: Status = Status.WAITING
    var timeStart: Long = 0L
    var timeEnd: Long = 0L
    var isConfigFinished: Boolean = false
    var areResponsesCorrect: Boolean = false
    var isDone: Boolean = false
    var error: String? = null
    var deviceDescription: DeviceDescription? = null
    var connectableDevice: ConnectableDevice? = null
    var meshNode: MeshNode? = null

    fun includeErrorMessage(errorMessage: String) {
        error = if (error != null) "$error; $errorMessage" else errorMessage
    }

    // region status
    fun calculateStatus(): Status {
        if (isDone) {
            status = if (error.isNullOrEmpty()) {
                when (id) {
                    Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK,
                    Experiment.REMOVE_NODES_IN_NETWORK,
                    Experiment.ADD_NODES_TO_NETWORK,
                    Experiment.POST_TESTING -> {
                        calculateStatusByResponses()
                    }
                    Experiment.CONTROL_GROUP,
                    Experiment.CONNECTION_NETWORK,
                    Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
                    Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                        calculateStatusByResponsesAndTime()
                    }
                    else -> {
                        calculateStatusByTime()
                    }
                }
            } else Status.FAIL
        }
        return status
    }

    private fun calculateStatusByTime(): Status {
        if (timeStart != 0L && timeEnd != 0L) {
            val timeToDo = timeEnd - timeStart
            return when {
                timeToDo > maxTime -> Status.FAIL
                timeToDo in 1..maxTime -> Status.PASS
                timeToDo < 0 -> Status.PROCESSING
                else -> Status.WAITING
            }
        }
        return Status.FAIL
    }

    private fun calculateStatusByResponsesAndTime(): Status {
        return if (areResponsesCorrect) calculateStatusByTime() else Status.FAIL
    }

    private fun calculateStatusByResponses(): Status {
        return if (areResponsesCorrect) Status.PASS else Status.FAIL
    }
    // endregion

    // region toString
    private fun getToStringItemPass(time: Long): String {
        return when (id) {
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK,
            Experiment.REMOVE_NODES_IN_NETWORK,
            Experiment.ADD_NODES_TO_NETWORK,
            Experiment.POST_TESTING -> {
                "Test case ${id.ordinal + 1}, ${status.name}."
            }
            else -> {
                "Test case ${id.ordinal + 1}, ${status.name}.Testing time: $time;Acceptable time: $maxTime."
            }
        }
    }

    override fun toString(): String {
        val time: Long = timeEnd - timeStart
        return when (calculateStatus()) {
            Status.PASS -> {
                getToStringItemPass(time)
            }
            Status.FAIL -> {
                getToStringItemFail(time)
            }
            else -> ""
        }
    }

    private fun getToStringItemFail(time: Long): String {
        return when (id) {
            Experiment.CONTROL_NODE_UUID_0E_0F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITH_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITH_ACK,
            Experiment.REMOVE_NODES_IN_NETWORK,
            Experiment.ADD_NODES_TO_NETWORK,
            Experiment.POST_TESTING -> {
                getDescriptionNotByTime()
            }
            Experiment.CONTROL_GROUP,
            Experiment.CONNECTION_NETWORK,
            Experiment.CONTROL_NODE_UUID_0E_0F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_1F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_2F_WITHOUT_ACK,
            Experiment.CONTROL_NODE_UUID_0E_3F_WITHOUT_ACK -> {
                if (error.isNullOrEmpty()) {
                    getMessageErrorByResponses(time)
                } else {
                    "Test case ${id.ordinal + 1}, ${status.name}.$error"
                }
            }
            else -> {
                getMessageErrorOther(time)
            }
        }
    }

    private fun getMessageErrorOther(time: Long): String {
        return if (error.isNullOrEmpty()) {
            "Test case ${id.ordinal + 1}, ${status.name}.Testing time:$time;Acceptable time: $maxTime."
        } else {
            "Test case ${id.ordinal + 1}, ${status.name}.$error."
        }
    }

    private fun getDescriptionNotByTime(): String {
        return if (error.isNullOrEmpty()) {
            "Test case ${id.ordinal + 1}, ${status.name}.Get status for node $areResponsesCorrect"
        } else {
            "Test case ${id.ordinal + 1}, ${status.name}.$error"
        }
    }

    private fun getMessageErrorByResponses(time: Long): String {
        return if (time > maxTime) {
            "Test case ${id.ordinal + 1}, ${status.name}.Testing time: $time;Acceptable time: $maxTime."
        } else if (!areResponsesCorrect) {
            "Test case ${id.ordinal + 1}, ${status.name}.Get status for node $areResponsesCorrect."
        } else ""
    }
    // endregion

    enum class Status(@StringRes val stringRes: Int? = null) {
        FAIL(R.string.test_status_fail),
        PASS(R.string.test_status_pass),
        PROCESSING,
        WAITING(R.string.test_status_waiting);
    }

    enum class Provisioned {
        NON_OOB,
        STATIC_OOB,
        OUTPUT_OOB,
        INPUT_OOB
    }
}
