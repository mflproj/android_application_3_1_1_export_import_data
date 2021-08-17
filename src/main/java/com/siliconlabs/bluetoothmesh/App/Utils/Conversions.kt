/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Utils

/**
 * e.g. "ffFF00" -> {-1, -1, 0}
 * @receiver hex string
 */
fun String.hexToByteArray(): ByteArray = Converters.hexToByteArray(this)
