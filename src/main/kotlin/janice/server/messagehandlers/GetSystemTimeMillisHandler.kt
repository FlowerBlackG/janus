// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import java.nio.ByteBuffer

class GetSystemTimeMillisHandler : MessageHandler<JanusMessage.GetSystemTimeMillis> {
    override suspend fun handle(conn: JanusProtocolConnection, msg: JanusMessage.GetSystemTimeMillis) {
        val time = System.currentTimeMillis()
        val buf = ByteBuffer.allocate(8).putLong(time)
        conn.sendResponse(code = 0, data = buf.array())
    }
}
