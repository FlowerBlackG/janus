// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import java.util.concurrent.ConcurrentLinkedQueue

class MessageObjectPool<T: JanusMessage>(private val factory: () -> T) {
    protected val pool = ConcurrentLinkedQueue<T>()

    fun borrow(): T {
        return pool.poll() ?: factory()
    }

    fun recycle(instance: T) {
        instance.reset()
        pool.add(instance)
    }
}
