// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection

interface MessageHandler <T: JanusMessage> {
    suspend fun handle(conn: JanusProtocolConnection, msg: T)
}
