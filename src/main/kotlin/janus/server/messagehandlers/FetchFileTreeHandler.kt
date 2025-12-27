// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.FileTree
import io.github.flowerblackg.janus.filesystem.globFilesRelative
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import kotlin.time.measureTime

class FetchFileTreeHandler(val workspace: Config.WorkspaceConfig) : MessageHandler<JanusMessage.FetchFileTree> {
    override suspend fun handle(conn: JanusProtocolConnection, msg: JanusMessage.FetchFileTree) {
        val fileTree: FileTree
        val globDuration = measureTime {
            fileTree = workspace.path.globFilesRelative(workspace.filter.ignore) ?: throw Exception("Failed to fetch file tree.")
        }

        val encoded: ByteArray
        val encodeDuration = measureTime {
            encoded = fileTree.encodeToByteArray()
        }

        Logger.info("File tree built in ${globDuration.inWholeMilliseconds}ms.")
        Logger.info("        encoded in ${encodeDuration.inWholeMilliseconds}ms.")
        conn.sendResponse(code = 0, data = encoded)
    }
}