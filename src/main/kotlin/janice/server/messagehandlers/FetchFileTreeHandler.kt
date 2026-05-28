// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server.messagehandlers

import io.github.flowerblackg.janice.config.Config
import io.github.flowerblackg.janice.filesystem.FileTree
import io.github.flowerblackg.janice.filesystem.globFilesRelative
import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import kotlin.time.measureTime

class FetchFileTreeHandler(val workspace: Config.WorkspaceConfig) : MessageHandler<JaniceMessage.FetchFileTree> {
    override suspend fun handle(conn: JaniceProtocolConnection, msg: JaniceMessage.FetchFileTree) {
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