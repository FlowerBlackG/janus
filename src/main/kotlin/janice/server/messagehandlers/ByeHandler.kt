// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import io.github.flowerblackg.janice.server.Lounge

class ByeHandler(val lounge: Lounge) : MessageHandler<JaniceMessage.Bye> {
    override suspend fun handle(
        conn: JaniceProtocolConnection,
        msg: JaniceMessage.Bye
    ) {
        conn.bye(expectResponse = false)
        lounge.stop()
    }
}
