// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.config.ConnectionMode
import io.github.flowerblackg.janus.filesystem.FileTree
import io.github.flowerblackg.janus.filesystem.globFilesRelative
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import java.nio.ByteBuffer
import kotlin.time.measureTime


/**
 * Lounge.
 *
 * Lounge is one time use. You should only call [serve] once.
 */
class Lounge constructor(
    val conn: JanusProtocolConnection,
    val config: Config
): AutoCloseable {
    protected data class MsgHandler(
        val msgType: Int,
        val handler: suspend (JanusMessage) -> Unit,
        val once: Boolean = false
    )

    protected val msgHandlers = mutableMapOf<Int, MsgHandler>()

    protected fun handle(msgType: Int, handler: suspend (JanusMessage) -> Unit) {
        msgHandlers[msgType] = MsgHandler(msgType, handler, once = false)
    }

    protected fun handleOnce(msgType: Int, handler: suspend (JanusMessage) -> Unit) {
        msgHandlers[msgType] = MsgHandler(msgType, handler, once = true)
    }

    protected fun removeHandler(msgType: Int) {
        msgHandlers.remove(msgType)
    }


    /**
     * Must be set properly during auth. If failed to set, lounge should close itself.
     */
    protected var workspace: Config.WorkspaceConfig = Config.WorkspaceConfig()


    protected suspend fun handleMessage(msg: JanusMessage) {
        val handler = msgHandlers[msg.type] ?: throw Exception("No handler for message type: ${msg.type}")
        val exception = runCatching { handler.handler(msg) }.exceptionOrNull()
        if (handler.once)
            removeHandler(msg.type)

        if (exception != null) {
            throw exception
        }
    }


    protected suspend fun recvAndHandleMessage() {
        handleMessage(conn.recvMessage())
    }

    /**
     *
     * No throw.
     */
    protected suspend fun helloAndAuth(): Unit? {
        runCatching { conn.hello(JanusProtocolConnection.Role.SERVER)  }.onFailure {
            Logger.error("Failed to serve client. Failed on Hello.")
            return null
        }

        val workspaces = config.workspaces
            .filterKeys { it.first == ConnectionMode.SERVER }
            .values
            .associateBy { it.name }
        this.workspace = runCatching { conn.auth(workspaces = workspaces) }.getOrNull() ?: run {
            Logger.error("Failed to serve client. Failed on Auth.")
            return null
        }

        return Unit
    }


    protected suspend fun fetchFileTreeHandler(msg: JanusMessage.FetchFileTree) {
        val fileTree: FileTree
        val globDuration = measureTime {
            fileTree = workspace.path.globFilesRelative(workspace.ignore) ?: throw Exception("Failed to fetch file tree.")
        }

        val encoded: ByteArray
        val encodeDuration = measureTime {
            encoded = fileTree.encodeToByteArray()
        }

        Logger.info("File tree built in ${globDuration.inWholeMilliseconds}ms.")
        Logger.info("        encoded in ${encodeDuration.inWholeMilliseconds}ms.")
        conn.sendResponse(code = 0, data = encoded)
    }

    protected suspend fun getSystemTimeMillisHandler(msg: JanusMessage.GetSystemTimeMillis) {
        val time = System.currentTimeMillis()
        val buf = ByteBuffer.allocate(8).putLong(time)
        conn.sendResponse(code = 0, data = buf.array())
    }


    protected var served = false

    /**
     * This never throws.
     *
     * @return 0 means client left happily. Otherwise, something went wrong.
     */
    suspend fun serve(onAuthorized: ((Config.WorkspaceConfig) -> Boolean)? = null): Int {
        if (served) {
            Logger.error("Lounge already served someone else.")
            return 1
        }
        served = true

        Logger.info("Welcome ${conn.socketChannel.remoteAddress} to Lounge.")
        helloAndAuth() ?: return 1

        if (onAuthorized != null) {
            if (!onAuthorized(this.workspace))
                return 2
        }

        handle(JanusMessage.FetchFileTree.typeCode) {
            fetchFileTreeHandler(it as JanusMessage.FetchFileTree)
        }
        handle(JanusMessage.GetSystemTimeMillis.typeCode) {
            getSystemTimeMillisHandler(it as JanusMessage.GetSystemTimeMillis)
        }

        while (true) {
            runCatching { recvAndHandleMessage() }.exceptionOrNull()?.let {
                Logger.warn("Exception: ${it.message}. Shutting down Lounge...")
                break
            }
        }

        return 0
    }

    override fun close() {
        msgHandlers.clear()
        conn.close()
    }
}
