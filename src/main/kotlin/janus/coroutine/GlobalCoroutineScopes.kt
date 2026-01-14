// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking


/**
 * In Janus, we should never use kotlin's global coroutine scopes and global dispatchers directly.
 *
 * We should always create coroutine tasks in scopes provided by [GlobalCoroutineScopes].
 *
 * The only coroutine you can use outside these scopes is by a simple [runBlocking].
 */
object GlobalCoroutineScopes {
    val IO: CoroutineScope = CoroutineScope(Dispatchers.IO)
    val Default: CoroutineScope = CoroutineScope(Dispatchers.Default)

    val scopes = setOf(IO, Default)

    /**
     * You should only call this outside any coroutine scopes.
     *
     * NEVER CALL THIS INSIDE ANY COROUTINE SCOPES.
     */
    fun joinChildren(nothrow: Boolean = false) {
        runBlocking {
            scopes.forEach { it.joinChildren(nothrow = nothrow) }
        }
    }

    fun activeJobCount(): Int {
        return scopes.sumOf { it.activeJobCount() }
    }

    /**
     * This will shut down kotlin's global dispatchers.
     *
     * You should only call this outside any coroutine scopes.
     */
    fun shutdown() {
        scopes.forEach { it.runCatching { cancel() } }
        Dispatchers.shutdown()
    }
}


fun CoroutineScope.activeJobCount(): Int {
    return this.coroutineContext.job.children.count()
}


suspend fun CoroutineScope.joinChildren(nothrow: Boolean = false) {
    if (nothrow)
        this.coroutineContext.job.children.forEach { it.runCatching { join() } }
    else
        this.coroutineContext.job.children.forEach { it.join() }
}
