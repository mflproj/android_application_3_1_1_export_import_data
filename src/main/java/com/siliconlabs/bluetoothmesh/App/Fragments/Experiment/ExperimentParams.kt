/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import com.siliconlab.bluetoothmesh.adk.functionality_control.base.ControlRequestParameters
import com.siliconlabs.bluetoothmesh.App.Models.TransactionId

class ExperimentParams {
    var isProxy = false
        private set
    var isRelay = false
        private set
    var isFriend = false
        private set

    var onOff = false
    var isFunctionality = false
    var isRequestAck = false
    val requestParams get() = ControlRequestParameters(0, 0, isRequestAck, tid.next())
    var countGroupSetResponse = 0
    var countElementSetResponse = 0
    var countElementGetResponse = 0

    fun resetAfterExperiment() {
        isFunctionality = false
        countGroupSetResponse = 0
        countElementGetResponse = 0
    }

    fun prepareConfig(isProxy: Boolean = false, isRelay: Boolean = false, isFriend: Boolean = false) {
        this.isProxy = isProxy
        this.isRelay = isRelay
        this.isFriend = isFriend
    }

    companion object {
        private var tid = TransactionId()
    }
}
