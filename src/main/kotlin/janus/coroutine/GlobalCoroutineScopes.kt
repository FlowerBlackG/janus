// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher

object GlobalCoroutineScopes {
    private val nettyCoroutineDispatcher = GlobalNettyEventLoopGroups.Default.asCoroutineDispatcher()

    val IO: CoroutineScope = CoroutineScope(nettyCoroutineDispatcher)
    val Default: CoroutineScope = CoroutineScope(nettyCoroutineDispatcher)
}
