// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.config.ConnectionMode
import io.github.flowerblackg.janus.filesystem.FileTree
import io.github.flowerblackg.janus.filesystem.globFilesRelative
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.server.messagehandlers.FetchFileTreeHandler
import io.github.flowerblackg.janus.server.messagehandlers.GetSystemTimeMillisHandler
import io.github.flowerblackg.janus.server.messagehandlers.MessageHandler
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

    protected enum class MessageHandlerLife { ONCE, ETERNAL }
    protected val msgHandlers = mutableMapOf<Int, Pair<MessageHandlerLife, MessageHandler<JanusMessage>>>()


    protected fun <T: JanusMessage> handle(msgType: Int, life: MessageHandlerLife, handler: MessageHandler<T>) {
        val typeErasedAdapter = object : MessageHandler<JanusMessage> {
            override suspend fun handle(conn: JanusProtocolConnection, msg: JanusMessage) {
                @Suppress("UNCHECKED_CAST")
                handler.handle(conn, msg as T)
            }
        }

        msgHandlers[msgType] = Pair(life, typeErasedAdapter)
    }

    protected fun <T: JanusMessage> handle(msgType: Int, handler: MessageHandler<T>) {
        handle(msgType, MessageHandlerLife.ETERNAL, handler)
    }

    protected fun handleOnce(msgType: Int, handler: MessageHandler<JanusMessage>) {
        handle(msgType, MessageHandlerLife.ONCE, handler)
    }

    protected fun removeHandler(msgType: Int) {
        msgHandlers.remove(msgType)
    }


    /**
     * Must be set properly during auth. If failed to set, lounge should close itself.
     */
    protected var workspace: Config.WorkspaceConfig = Config.WorkspaceConfig()


    protected suspend fun handleMessage(msg: JanusMessage) {
        val (life, handler) = msgHandlers[msg.type] ?: throw Exception("No handler for message type: ${msg.type}")
        val exception = runCatching { handler.handle(conn = conn, msg = msg) }.exceptionOrNull()
        if (life == MessageHandlerLife.ONCE)
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

        handle(JanusMessage.FetchFileTree.typeCode, FetchFileTreeHandler(workspace))
        handle(JanusMessage.GetSystemTimeMillis.typeCode, GetSystemTimeMillisHandler())

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
