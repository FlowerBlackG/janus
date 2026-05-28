// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import java.nio.ByteBuffer

class GetSystemTimeMillisHandler : MessageHandler<JaniceMessage.GetSystemTimeMillis> {
    override suspend fun handle(conn: JaniceProtocolConnection, msg: JaniceMessage.GetSystemTimeMillis) {
        val time = System.currentTimeMillis()
        val buf = ByteBuffer.allocate(8).putLong(time)
        conn.sendResponse(code = 0, data = buf.array())
    }
}
