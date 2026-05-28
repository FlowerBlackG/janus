// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server

import io.github.flowerblackg.janice.config.Config
import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.protocol.JaniceMessage
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import io.github.flowerblackg.janice.server.messagehandlers.ByeHandler
import io.github.flowerblackg.janice.server.messagehandlers.CommitSyncPlanHandler
import io.github.flowerblackg.janice.server.messagehandlers.ConfirmArchivesHandler
import io.github.flowerblackg.janice.server.messagehandlers.ConfirmFilesHandler
import io.github.flowerblackg.janice.server.messagehandlers.FetchFileTreeHandler
import io.github.flowerblackg.janice.server.messagehandlers.GetSystemTimeMillisHandler
import io.github.flowerblackg.janice.server.messagehandlers.MessageHandler
import io.github.flowerblackg.janice.server.messagehandlers.UploadArchiveHandler
import io.github.flowerblackg.janice.server.messagehandlers.UploadFileHandler
import java.util.concurrent.ConcurrentLinkedQueue


/**
 * Lounge.
 *
 * Lounge is one time use. You should only call [serve] once.
 */
class Lounge (
    val conn: JaniceProtocolConnection,
    val config: Config
): AutoCloseable {

    protected enum class MessageHandlerLife { ONCE, ETERNAL }
    protected val msgHandlers = mutableMapOf<Int, Pair<MessageHandlerLife, MessageHandler<JaniceMessage>>>()

    protected val archiveExtractorPool = ArchiveExtractorPool()

    /**
     * For each member, Pair's first is seqId, Pair's second indicates success (zero) or not (non-zero).
     */
    protected val uploadFilePendingACKs = ConcurrentLinkedQueue<Pair<Long, Int>>()


    protected fun <T: JaniceMessage> handle(msgType: Int, life: MessageHandlerLife, handler: MessageHandler<T>) {
        val typeErasedAdapter = object : MessageHandler<JaniceMessage> {
            override suspend fun handle(conn: JaniceProtocolConnection, msg: JaniceMessage) {
                @Suppress("UNCHECKED_CAST")
                handler.handle(conn, msg as T)
            }
        }

        msgHandlers[msgType] = Pair(life, typeErasedAdapter)
    }

    protected fun <T: JaniceMessage> handle(msgType: Int, handler: MessageHandler<T>) {
        handle(msgType, MessageHandlerLife.ETERNAL, handler)
    }

    protected fun handleOnce(msgType: Int, handler: MessageHandler<JaniceMessage>) {
        handle(msgType, MessageHandlerLife.ONCE, handler)
    }

    protected fun removeHandler(msgType: Int) {
        msgHandlers.remove(msgType)
    }


    /**
     * Must be set properly during auth. If failed to set, lounge should close itself.
     */
    protected var workspace: Config.WorkspaceConfig = Config.WorkspaceConfig()


    protected suspend fun handleMessage(msg: JaniceMessage) {
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
        runCatching { conn.hello(JaniceProtocolConnection.Role.SERVER)  }.onFailure {
            Logger.error("Failed to serve client. Failed on Hello.")
            return null
        }

        this.workspace = runCatching { conn.auth(workspaces = config.workspaces) }.getOrNull() ?: run {
            Logger.error("Failed to serve client. Failed on Auth.")
            return null
        }

        this.archiveExtractorPool.workspace = this.workspace

        return Unit
    }


    protected var served = false
    protected var running = true

    /**
     * Called after authorized. If returns false, lounge will reject the client.
     *
     * This is used to block clients or reject them so only limited numbers of client can use one workspace.
     */
    var onAuthorized: ((Config.WorkspaceConfig) -> Boolean)? = null

    /**
     * This never throws.
     *
     * @return 0 means client left happily. Otherwise, something went wrong.
     */
    suspend fun serve(): Int {
        if (served) {
            Logger.error("Lounge already served someone else.")
            return 1
        }
        served = true

        Logger.info("Welcome ${conn.janiceSocket.remoteAddress} to Lounge.")
        helloAndAuth() ?: return 1

        if (onAuthorized?.let { it(this.workspace) } == false)
            return 2

        handle(JaniceMessage.FetchFileTree.typeCode, FetchFileTreeHandler(workspace))
        handle(JaniceMessage.GetSystemTimeMillis.typeCode, GetSystemTimeMillisHandler())
        handle(JaniceMessage.CommitSyncPlan.typeCode, CommitSyncPlanHandler(workspace))
        handle(JaniceMessage.UploadFile.typeCode, UploadFileHandler(workspace, uploadFilePendingACKs))
        handle(JaniceMessage.UploadArchive.typeCode, UploadArchiveHandler(this.archiveExtractorPool))
        handle(JaniceMessage.ConfirmArchives.typeCode, ConfirmArchivesHandler(this.archiveExtractorPool))
        handle(JaniceMessage.ConfirmFiles.typeCode, ConfirmFilesHandler(uploadFilePendingACKs))
        handle(JaniceMessage.Bye.typeCode, ByeHandler(this))

        running = true
        while (running) {
            runCatching { recvAndHandleMessage() }.exceptionOrNull()?.let {
                Logger.warn("Exception: ${it.message}. Shutting down Lounge...")
                return 3
            }
        }

        return 0
    }


    fun stop() {
        this.running = false
    }


    override fun close() {
        this.stop()
        msgHandlers.clear()
        conn.close()
    }
}
