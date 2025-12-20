// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.crypto.AesHelper
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.AsyncSocketWrapper
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class JanusProtocolConnection(socketChannel: AsynchronousSocketChannel) : AsyncSocketWrapper(socketChannel) {
    suspend fun send(janusMsg: JanusMessage) {
        write(janusMsg.toByteBuffer())
    }

    suspend fun sendResponse(code: Int, data: ByteBuffer? = null) {
        val response = JanusMessage.create(JanusMessage.CommonResponse.typeCode) as JanusMessage.CommonResponse
        response.code = code
        response.msg = data?.let {
            val arr = ByteArray(it.remaining())
            it.get(arr)
            arr
        } ?: byteArrayOf()
        send(response)

        JanusMessage.recycle(response)
    }

    suspend fun sendResponse(code: Int, data: ByteArray) {
        return this.sendResponse(code, ByteBuffer.wrap(data))
    }

    suspend fun sendResponse(code: Int, msg: String) {
        return this.sendResponse(code, msg.toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun recvHeader(): Pair<Int, Long>? {
        val header = ByteBuffer.allocate(JanusMessage.HEADER_LENGTH)
        runCatching { read(header) }.getOrNull() ?: return null
        return JanusMessage.decodeHeader(header.flip())
    }

    suspend fun recvBody(bodyLength: Long): ByteBuffer? {
        if (bodyLength >= Int.MAX_VALUE) {
            Logger.error("bodyLength too large: $bodyLength")
            return null
        }

        val buf = ByteBuffer.allocate(bodyLength.toInt())
        runCatching { read(buf) }.getOrNull() ?: return null
        return buf.flip()
    }


    @Throws(Exception::class)
    suspend fun recvMessage(requiredMsgType: Int? = null): JanusMessage {
        val (msgType, bodyLength) = recvHeader() ?: throw Exception("Failed to recv header.")
        val body = recvBody(bodyLength) ?: throw Exception("Failed to recv body. requiredType: ${requiredMsgType?.toHexString()}, bodyLength: $bodyLength")

        if (requiredMsgType != null && requiredMsgType != msgType)
            throw Exception("Wrong msg type: $msgType. Required msg type: $requiredMsgType")

        val janusMsg = JanusMessage.decode(body, msgType)
        return janusMsg
    }


    enum class Role { SERVER, CLIENT }


    protected suspend fun serverModeHello() {
        Logger.info("Running server mode Hello.")

        val fromClient = recvMessage(JanusMessage.Hello.typeCode) as JanusMessage.Hello
        if (fromClient.protocolVersions.firstOrNull() != JanusMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${fromClient.protocolVersions.firstOrNull()}")
        }

        val toClient = JanusMessage.create(JanusMessage.Hello.typeCode) as JanusMessage.Hello
        toClient.protocolVersions += JanusMessage.PROTOCOL_VERSION
        send(toClient)

        val finalMsg = recvMessage(JanusMessage.Hello.typeCode) as JanusMessage.Hello
        if (finalMsg.protocolVersions.firstOrNull() != JanusMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${finalMsg.protocolVersions.firstOrNull()}")
        }
        Logger.success("Server mode Hello: Using protocol version: ${JanusMessage.PROTOCOL_VERSION}")

        JanusMessage.recycle(fromClient, toClient, finalMsg)
    }
    protected suspend fun clientModeClient() {
        Logger.info("Running client mode Hello.")
        val toServer = JanusMessage.create(JanusMessage.Hello.typeCode) as JanusMessage.Hello
        toServer.protocolVersions += JanusMessage.PROTOCOL_VERSION
        send(toServer)

        val fromServer = recvMessage(JanusMessage.Hello.typeCode) as JanusMessage.Hello
        if (fromServer.protocolVersions.firstOrNull() != JanusMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${fromServer.protocolVersions.firstOrNull()}")
        }

        send(toServer)
        Logger.success("Client mode Hello: Using protocol version: ${JanusMessage.PROTOCOL_VERSION}")

        JanusMessage.recycle(toServer, fromServer)
    }
    suspend fun hello(mode: Role) {
        when (mode) {
            Role.SERVER -> serverModeHello()
            Role.CLIENT -> clientModeClient()
        }
    }


    @OptIn(ExperimentalUuidApi::class)
    protected suspend fun serverModeAuth(workspaces: Map<String, Config.WorkspaceConfig>): Config.WorkspaceConfig {
        val fromClient1 = recvMessage(JanusMessage.Auth.typeCode) as JanusMessage.Auth
        val wsName = String(fromClient1.challenge, StandardCharsets.UTF_8)

        val workspace = workspaces[wsName]
        val aes = workspace?.crypto?.aes

        val challenge = Uuid.random().toHexString() + Random.nextLong()
        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)
        val toClient = JanusMessage.create(JanusMessage.Auth.typeCode) as JanusMessage.Auth
        toClient.challenge = challengeBytes
        send(toClient)
        val fromClient2 = recvMessage(JanusMessage.Auth.typeCode) as JanusMessage.Auth

        val success = if (workspace != null)
                (aes?.decrypt(fromClient2.challenge) ?: fromClient2.challenge).contentEquals(challengeBytes)
            else
                false

        if (success) {
            workspace!!
            Logger.success("Client authorized.")
            sendResponse(0)
        } else {
            sendResponse(1, msg = "Failed on Auth")
            throw Exception("Failed on auth")
        }

        JanusMessage.recycle(toClient, fromClient2, fromClient1)
        return workspace
    }

    protected suspend fun clientModeAuth(workspaceConfig: Config.WorkspaceConfig) {
        val aes = workspaceConfig.crypto.aes
        val wsName = workspaceConfig.name

        // Auth : step 1

        val toSvr = JanusMessage.create(JanusMessage.Auth.typeCode) as JanusMessage.Auth
        toSvr.challenge = wsName.toByteArray(StandardCharsets.UTF_8)

        send(toSvr)

        // Auth : step 2 & 3

        val fromSvr = recvMessage(JanusMessage.Auth.typeCode) as JanusMessage.Auth
        val cipher = aes?.encrypt(fromSvr.challenge) ?: fromSvr.challenge

        toSvr.reset()
        toSvr.challenge = cipher
        send(toSvr)

        // Auth : step 4

        val response = recvMessage(JanusMessage.CommonResponse.typeCode) as JanusMessage.CommonResponse
        if (!response.success)
            throw Exception("Failed on auth")

        JanusMessage.recycle(fromSvr, toSvr, response)
    }

    /**
     * Pass only and exactly one of workspace and workspaces.
     *
     * If workspace passed, means you run in client mode.
     * If workspaces passed, means you run in server mode.
     */
    suspend fun auth(workspaces: Map<String, Config.WorkspaceConfig>? = null, workspace: Config.WorkspaceConfig? = null): Config.WorkspaceConfig? {
        var ret: Config.WorkspaceConfig? = null

        when {
            workspace != null && workspaces != null -> throw IllegalArgumentException("Both workspaces and workspace provided.")
            workspaces != null -> ret = serverModeAuth(workspaces)
            workspace != null -> clientModeAuth(workspace)
            else -> throw IllegalArgumentException("No workspaces or workspace provided.")
        }

        Logger.success("Auth success.")
        return ret
    }

}

