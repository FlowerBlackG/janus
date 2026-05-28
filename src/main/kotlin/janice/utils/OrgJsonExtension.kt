// SPDX-License-Identifier: MulanPSL-2.0
/* From SUFE GuoTuanTuan Project. */

package io.github.flowerblackg.janus.utils

import org.json.JSONObject

/*
 * Some extensions to org.json library.
 */

/**
 * get string, and returns null instead of throws.
 */
fun JSONObject.getStringOrNull(key: String) = this.getString(key, null)

/**
 * get string, and returns default value instead of throws.
 */
fun JSONObject.getString(key: String, defaultValue: String?) = try {
    this.getString(key)
} catch (_: Exception) {
    defaultValue
}


fun JSONObject.getInt(key: String, defaultValue: Int?) = try {
    this.getInt(key)
} catch (_: Exception) {
    defaultValue
}


fun JSONObject.getBoolean(key: String, defaultValue: Boolean?) = try {
    this.getBoolean(key)
} catch (_: Exception) {
    defaultValue
}
