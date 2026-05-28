// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class ConfirmFilesHandler(
    val pendingACKsHolder: ConcurrentLinkedQueue<Pair<Long, Int>>
) : MessageHandler<JanusMessage.ConfirmFiles> {
    override suspend fun handle(
        conn: JanusProtocolConnection,
        msg: JanusMessage.ConfirmFiles
    ) {
        val acks = ArrayList<Pair<Long, Int>>()

        var seqId = pendingACKsHolder.poll()
        while (seqId != null) {
            acks += seqId
            seqId = pendingACKsHolder.poll()
        }

        val buf = ByteBuffer.allocate(acks.size * (Int.SIZE_BYTES + Long.SIZE_BYTES))
        acks.forEach {
            buf.putLong(it.first)
            buf.putInt(it.second)
        }

        conn.sendResponse(0, data = buf.flip())
    }

}
