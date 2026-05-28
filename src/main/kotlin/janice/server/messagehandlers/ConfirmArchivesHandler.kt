// SPDX-License-Identifier: MulanPSL-2.0


package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import io.github.flowerblackg.janice.server.ArchiveExtractorPool
import java.nio.ByteBuffer

class ConfirmArchivesHandler(val extractorPool: ArchiveExtractorPool) : MessageHandler<JaniceMessage.ConfirmArchives> {
    override suspend fun handle(
        conn: JaniceProtocolConnection,
        msg: JaniceMessage.ConfirmArchives
    ) {
        val results = extractorPool.checkExtractedArchives()

        val bArr = ByteArray((Long.SIZE_BYTES + Int.SIZE_BYTES) * results.size)

        val buf = ByteBuffer.wrap(bArr)

        for ((seqId, status) in results) {
            buf.putLong(seqId)
                .putInt(status)
        }

        conn.sendResponse(code = 0, data = bArr)  // TODO: Report error on any archive failure?
    }
}
