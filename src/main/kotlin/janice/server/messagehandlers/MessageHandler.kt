// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection

interface MessageHandler <T: JaniceMessage> {
    suspend fun handle(conn: JaniceProtocolConnection, msg: T)
}
