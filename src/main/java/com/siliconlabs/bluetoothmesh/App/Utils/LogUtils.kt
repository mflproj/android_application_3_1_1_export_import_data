/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Utils

import java.text.SimpleDateFormat
import java.util.*

object LogUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val signsToReplace = Regex("[- :]")

    fun generateFileName() = "${DeviceUtils.getDeviceName()}_${getDate()}".replace(signsToReplace, "_")

    fun getDate(): String = dateFormat.format(Date())
}

val Any.TAG: String
    get() {
        val tag = javaClass.simpleName
        return if (tag.length <= 23) tag else tag.substring(0, 23)
    }
