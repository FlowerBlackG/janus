// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.server.Lounge

class ByeHandler(val lounge: Lounge) : MessageHandler<JanusMessage.Bye> {
    override suspend fun handle(
        conn: JanusProtocolConnection,
        msg: JanusMessage.Bye
    ) {
        conn.bye(expectResponse = false)
        lounge.stop()
    }
}
