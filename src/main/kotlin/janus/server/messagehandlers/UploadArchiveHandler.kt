// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.server.ArchiveExtractorPool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class UploadArchiveHandler(val extractorPool: ArchiveExtractorPool) : MessageHandler<JanusMessage.UploadArchive> {


    /**
     * Co-engined with Google Gemini 3.0 Pro.
     */
    override suspend fun handle(
        conn: JanusProtocolConnection,
        msg: JanusMessage.UploadArchive
    ) {
        Logger.info("Archive processing started. Total Size: ${msg.archiveSize / 1024 / 1024}MB")

        val dataChannel = Channel<ByteArray>(capacity = 192)

        val producerJob = GlobalCoroutineScopes.IO.launch {
            try {
                var receivedBytes = 0L
                while (receivedBytes < msg.archiveSize) {
                    val block = conn.recvMessage(JanusMessage.DataBlock.typeCode) as JanusMessage.DataBlock
                    dataChannel.send(block.dataBlock)
                    receivedBytes += block.dataBlock.size
                }
            } catch (e: Exception) {
                Logger.error("Network receiver failed: ${e.message}")
                dataChannel.close(e)
            } finally {
                dataChannel.close()
            }
        }

        extractorPool.extract(
            dataChannel = dataChannel,
            seqId = msg.seqId,
            archiveSize = msg.archiveSize,
            producerJob = producerJob
        )


        producerJob.join()
        conn.sendResponse(code = 0)
    }
}
