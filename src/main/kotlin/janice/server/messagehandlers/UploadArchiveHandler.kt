// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import io.github.flowerblackg.janice.server.ArchiveExtractorPool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class UploadArchiveHandler(val extractorPool: ArchiveExtractorPool) : MessageHandler<JaniceMessage.UploadArchive> {


    /**
     * Co-engined with Google Gemini 3.0 Pro.
     */
    override suspend fun handle(
        conn: JaniceProtocolConnection,
        msg: JaniceMessage.UploadArchive
    ) {
        Logger.info("Archive processing started. Total Size: ${msg.archiveSize / 1024 / 1024}MB")

        val dataChannel = Channel<ByteArray>(capacity = 192)

        val producerJob = GlobalCoroutineScopes.IO.launch {
            try {
                var receivedBytes = 0L
                while (receivedBytes < msg.archiveSize) {
                    val block = conn.recvMessage(JaniceMessage.DataBlock.typeCode) as JaniceMessage.DataBlock
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
