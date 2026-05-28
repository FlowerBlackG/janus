// SPDX-License-Identifier: MulanPSL-2.0


package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.server.ArchiveExtractorPool
import java.nio.ByteBuffer

class ConfirmArchivesHandler(val extractorPool: ArchiveExtractorPool) : MessageHandler<JanusMessage.ConfirmArchives> {
    override suspend fun handle(
        conn: JanusProtocolConnection,
        msg: JanusMessage.ConfirmArchives
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
