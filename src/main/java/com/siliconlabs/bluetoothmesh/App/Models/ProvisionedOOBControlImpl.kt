/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Models

import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerOOB.*
import com.siliconlab.bluetoothmesh.adk.provisioning.ProvisionerOOBControl
import com.siliconlabs.bluetoothmesh.App.Fragments.Experiment.ExperimentListView
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.App.Utils.hexToByteArray

class ProvisionedOOBControlImpl(private val experimentView: ExperimentListView,
                                private val typeOOB: ExperimentDetail.Provisioned) : ProvisionerOOBControl() {

    override fun isPublicKeyAllowed() = PUBLIC_KEY_ALLOWED.YES
    override fun minLengthOfOOBData() = 0
    override fun maxLengthOfOOBData() = 8
    override fun oobPublicKeyRequest(uuid: ByteArray?, algorithm: Int, publicKeyType: Int) = RESULT.SUCCESS

    override fun getAllowedAuthMethods(): Set<AUTH_METHODS_ALLOWED> {
        return when (typeOOB) {
            ExperimentDetail.Provisioned.STATIC_OOB -> setOf(AUTH_METHODS_ALLOWED.STATIC_OBB)
            ExperimentDetail.Provisioned.OUTPUT_OOB -> setOf(AUTH_METHODS_ALLOWED.OUTPUT_OOB)
            ExperimentDetail.Provisioned.INPUT_OOB -> setOf(AUTH_METHODS_ALLOWED.INPUT_OBB)
            else -> emptySet()
        }
    }

    override fun getAllowedOutputActions() = setOf(OUTPUT_ACTIONS_ALLOWED.NUMERIC)

    override fun getAllowedInputActions(): Set<INPUT_ACTIONS_ALLOWED> {
        return if (typeOOB == ExperimentDetail.Provisioned.INPUT_OOB) {
            setOf(INPUT_ACTIONS_ALLOWED.PUSH)
        } else {
            emptySet()
        }
    }

    override fun authRequest(uuid: ByteArray?): RESULT {
        if (typeOOB == ExperimentDetail.Provisioned.STATIC_OOB) {
            provideAuthData(uuid, STATIC_AUTH_DATA)
        }
        return RESULT.SUCCESS
    }

    override fun inputOobDisplay(uuid: ByteArray?, inputAction: INPUT_ACTIONS, authNumber: Int) {
        if (typeOOB == ExperimentDetail.Provisioned.INPUT_OOB) {
            experimentView.showDialogInputOOB(authNumber)
        }
    }

    override fun outputRequest(uuid: ByteArray, outputActions: OUTPUT_ACTIONS, outputSize: Int): RESULT {
        if (typeOOB == ExperimentDetail.Provisioned.OUTPUT_OOB) {
            experimentView.showDialogOutputOOB(object : InputOOBCallBack {
                override fun onClickSaveOOBInput(oobInput: String) {
                    oobInput.toIntOrNull()?.let { provideNumericAuthData(uuid, it) }
                }
            })
        }
        return RESULT.SUCCESS
    }

    companion object {
        private const val STATIC_AUTH_DATA_HEX = "00112233445566778899aabbccddeeff"
        private val STATIC_AUTH_DATA = STATIC_AUTH_DATA_HEX.hexToByteArray()
    }
}

interface InputOOBCallBack {
    fun onClickSaveOOBInput(oobInput: String)
}
