// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.client

import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Once new instance created, a loop inside will start receiving seqIds and receive corresponding responses from Janus Connection immediately.
 *
 * The receiver loop will try receives response as ACK for [JanusMessage.UploadFile].
 *
 * This receiver is used when sending lots of files continuously and want to wait response while sending another file simultaneously.
 */
class UploadFileResponseReceiver(
    val conn: JanusProtocolConnection,
    /**
     * Since you might send archives between files,
     * you can pass a `null` to the channel,
     * so it will handle one ACK for archive request without crashing.
     */
    val seqIdChannel: Channel<Long?>,
) {

    protected val receiverJob: Job = GlobalCoroutineScopes.IO.launch { receiverLoop() }

    protected suspend fun receiverLoop() {
        for (seqId in seqIdChannel) {
            val res = conn.recvResponse()
            val seqIdFromResponse = if (res.data.size != Long.SIZE_BYTES) null else ByteBuffer.wrap(res.data).getLong()

            if (seqIdFromResponse != seqId)
                throw Exception("Failed to receive ack for file. Expected $seqId, but got $seqIdFromResponse.")

            if (!res.success) {
                Logger.error("Something went wrong for file with seqId $seqId. ${res.msg}")
            }
        }
    }

    suspend fun join() {
        this.receiverJob.join()
    }
}
