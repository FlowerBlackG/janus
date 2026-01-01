// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object GlobalCoroutineScopes {
    val IO: CoroutineScope = CoroutineScope(Dispatchers.IO)
    val Default: CoroutineScope = CoroutineScope(Dispatchers.Default)
}
