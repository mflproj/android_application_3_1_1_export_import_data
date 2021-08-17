/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.App.Models.InputOOBCallBack

interface ExperimentListView {
    fun notifyDataItem(isRunning: Boolean)

    fun prepareData(experiments: List<ExperimentDetail>)

    fun showDialogOutputOOB(callBack: InputOOBCallBack)

    fun showDialogInputOOB(times: Int)

    fun enableButton(enable: Boolean)
}
