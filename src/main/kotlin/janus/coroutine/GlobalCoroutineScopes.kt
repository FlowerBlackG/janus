// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job

object GlobalCoroutineScopes {
    val IO: CoroutineScope = CoroutineScope(Dispatchers.IO)
    val Default: CoroutineScope = CoroutineScope(Dispatchers.Default)

    val scopes = setOf(IO, Default)

    suspend fun joinChildren() {
        scopes.forEach { it.joinChildren() }
    }

    fun activeJobCount(): Int {
        return scopes.sumOf { it.activeJobCount() }
    }
}


fun CoroutineScope.activeJobCount(): Int {
    return this.coroutineContext.job.children.count()
}


suspend fun CoroutineScope.joinChildren() {
    this.coroutineContext.job.children.forEach { it.join() }
}
