// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.FSUtils
import io.github.flowerblackg.janus.filesystem.MemoryMappedFile
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.io.path.absolute
import kotlin.io.path.name

class UploadFileHandler(val ws: Config.WorkspaceConfig) : MessageHandler<JanusMessage.UploadFile> {

    override suspend fun handle(conn: JanusProtocolConnection, msg: JanusMessage.UploadFile) {
        val path = ws.path.resolve(msg.path)
        val absPath = path.absolute().normalize()
        if (!absPath.startsWith(ws.path.absolute().normalize())) {
            throw Exception("Path is out of workspace")
        }

        if (Files.exists(absPath) && !Files.isRegularFile(absPath))
            absPath.toFile().deleteRecursively()

        val beginTimeMillis = System.currentTimeMillis()

        val tmpPath = absPath.resolveSibling("${absPath.name}.janus-sync-tmp")

        MemoryMappedFile.createAndMap(tmpPath, size = msg.fileSize, permissionBits = msg.permBits).use { file ->
            file.writePos = 0
            var remaining = msg.fileSize

            // start accepting file.

            while (remaining > 0) {
                val block = conn.recvMessage(JanusMessage.DataBlock.typeCode) as JanusMessage.DataBlock
                file.write(block.dataBuffer)

                remaining -= block.dataBlock.size
            }
        }

        FSUtils.moveFile(tmpPath, absPath, deleteSrcOnFailure = true)

        val endTimeMillis = System.currentTimeMillis()
        var timeCostMillis = endTimeMillis - beginTimeMillis
        if (timeCostMillis <= 0)
            timeCostMillis = 1
        val speedMBps = msg.fileSize * 1000 / 1024 / 1024 / timeCostMillis

        Logger.success("File ${msg.path} uploaded. Size ${msg.fileSize / 1024 / 1024} MB, at speed: $speedMBps MB/s")
        val resBody = ByteBuffer.allocate(Long.SIZE_BYTES)
        resBody.putLong(msg.nonce)
        conn.sendResponse(code = 0, data = resBody.flip())
    }
}
