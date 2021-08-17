/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

interface BluetoothScanHandler {
    fun startScanner(mode: Int, timeoutDelay: Long)
    fun stopScanner()
}
