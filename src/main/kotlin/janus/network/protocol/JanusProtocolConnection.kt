// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.FileTree
import io.github.flowerblackg.janus.filesystem.MemoryMappedFile
import io.github.flowerblackg.janus.filesystem.SyncPlan
import io.github.flowerblackg.janus.filesystem.getPermissionMask
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.AsyncSocketWrapper
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.name
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


    /**
     *
     * @param throwOnFail Whether throw when Response code is not 0.
     */
    suspend fun recvResponse(throwOnFail: Boolean = false, throwOnFailPrompt: String? = null): JanusMessage.CommonResponse {
        val res = recvMessage(JanusMessage.CommonResponse.typeCode) as JanusMessage.CommonResponse
        if (throwOnFail && !res.success) {
            val throwMsg = throwOnFailPrompt ?: "Response code is not 0: ${res.code}. Message: ${res.msg}"
            JanusMessage.recycle(res)
            throw Exception(throwMsg)
        }
        return res
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

        if (workspace == null)
            Logger.warn("Failed to load workspace required by client: $wsName. Client denied.")

        val challenge = Uuid.random().toHexString() + Random.nextLong()
        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)
        val toClient = JanusMessage.create(JanusMessage.Auth.typeCode) as JanusMessage.Auth
        toClient.challenge = challengeBytes
        send(toClient)
        val fromClient2 = recvMessage(JanusMessage.Auth.typeCode) as JanusMessage.Auth

        val success = if (workspace != null) {
            val authResult = (aes?.decrypt(fromClient2.challenge) ?: fromClient2.challenge).contentEquals(challengeBytes)
            if (!authResult)
                Logger.warn("Client didn't encrypted challenge correctly. Client denied.")

            authResult
        }
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
        val encrypted = aes?.encrypt(fromSvr.challenge) ?: fromSvr.challenge

        toSvr.reset()
        toSvr.challenge = encrypted
        send(toSvr)

        // Auth : step 4

        val response = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on auth")

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


    suspend fun fetchFileTree(root: Path? = null): FileTree {
        val req = JanusMessage.create(JanusMessage.FetchFileTree.typeCode) as JanusMessage.FetchFileTree
        send(req)
        val res = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on fetch file tree")
        return if (root != null) {
            FileTree.from(res.data, root)
        }
        else {
            FileTree.from(res.data)
        } ?: throw Exception("Failed to parse file tree.")
    }


    suspend fun getSystemTimeMillis(): Long {
        val req = JanusMessage.create(JanusMessage.GetSystemTimeMillis.typeCode) as JanusMessage.GetSystemTimeMillis
        send(req)
        val res = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on get system time millis")

        if (res.data.size != Long.SIZE_BYTES)
            throw Exception("Wrong data size: ${res.data.size}")

        return ByteBuffer.wrap(res.data).getLong()
    }


    suspend fun commitSyncPlan(syncPlans: List<SyncPlan>) {
        val req = JanusMessage.create(JanusMessage.CommitSyncPlan.typeCode) as JanusMessage.CommitSyncPlan
        req.syncPlansBytes = syncPlans.map { it.encodeToByteArray() }
        send(req)

        val res = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on commit sync plan")
        JanusMessage.recycle(req, res)
    }


    private suspend fun sendFile(file: MemoryMappedFile) {
        val timeBeginMillis = System.currentTimeMillis()

        val dataBlockReq = JanusMessage.create(JanusMessage.DataBlock.typeCode) as JanusMessage.DataBlock

        file.readPos = 0
        var remaining = file.size
        val chunkSize = 1 * 1024L * 1024
        var sharedByteArray = byteArrayOf()
        while (remaining > 0) {
            dataBlockReq.reset()
            val size = minOf(chunkSize, remaining)
            sharedByteArray = if (sharedByteArray.size == size.toInt()) sharedByteArray else ByteArray(size.toInt())
            dataBlockReq.dataBlock = sharedByteArray
            val bytesRead = file.read(ByteBuffer.wrap(sharedByteArray))

            if (bytesRead != size.toInt())
                throw Exception("Failed to read file: ${file.path}")

            remaining -= size
            send(dataBlockReq)
        }

        JanusMessage.recycle(dataBlockReq)

        val timeEndMillis = System.currentTimeMillis()
        var timeCostMillis = timeEndMillis - timeBeginMillis
        if (timeCostMillis <= 0)
            timeCostMillis = 1
        val speedMBps = file.size * 1000 / 1024 / 1024 / timeCostMillis
        Logger.success("File ${file.path.name} uploaded in $timeCostMillis ms. Speed: $speedMBps MB/s")
    }


    suspend fun uploadFile(filePath: Path, workspace: Config.WorkspaceConfig) {
        val realPath = workspace.path.resolve(filePath)
        val realPathAbs = realPath.absolute().normalize()
        if (!realPathAbs.startsWith(workspace.path.absolute().normalize()))
            throw Exception("File path is not in workspace: $realPath")

        val nonce = Random.nextLong()
        val uploadFileReq = JanusMessage.create(JanusMessage.UploadFile.typeCode) as JanusMessage.UploadFile
        uploadFileReq.path = filePath
        uploadFileReq.fileSize = Files.size(realPathAbs)
        uploadFileReq.permBits = realPath.getPermissionMask()
        uploadFileReq.nonce = nonce

        send(uploadFileReq)

        JanusMessage.recycle(uploadFileReq)

        MemoryMappedFile.openAndMap(realPathAbs, FileChannel.MapMode.READ_ONLY).use { sendFile(it) }

        val response = recvResponse(throwOnFail = true, "Server failed to receive file: $realPathAbs")
        if (response.data.size != Long.SIZE_BYTES || ByteBuffer.wrap(response.data).getLong() != nonce) {
            throw Exception("Server failed to process file: $realPathAbs. Wrong nonce.")
        }
        JanusMessage.recycle(response)
    }


    private var nextArchiveSeqId: Long = 1L
    private val pendingArchiveSequences = mutableSetOf<Long>()

    suspend fun uploadArchive(workspace: Config.WorkspaceConfig, byteBuffer: ByteBuffer) {
        val timeBeginMillis = System.currentTimeMillis()
        val archiveSize = byteBuffer.remaining().toLong()
        val DATA_BLOCK_SIZE = 2L * 1024 * 1024

        val uploadArchiveReq = JanusMessage.create(JanusMessage.UploadArchive.typeCode) as JanusMessage.UploadArchive
        uploadArchiveReq.archiveSize = byteBuffer.remaining().toLong()
        val seqId = nextArchiveSeqId++
        uploadArchiveReq.seqId = seqId
        send(uploadArchiveReq)

        var sharedByteArray = byteArrayOf()
        val dataBlockReq = JanusMessage.create(JanusMessage.DataBlock.typeCode) as JanusMessage.DataBlock

        while (byteBuffer.remaining() > 0) {
            val bytesToSend = minOf(byteBuffer.remaining().toLong(), DATA_BLOCK_SIZE)
            if (bytesToSend != sharedByteArray.size.toLong())
                sharedByteArray = ByteArray(bytesToSend.toInt())

            byteBuffer.get(sharedByteArray)
            dataBlockReq.reset()
            dataBlockReq.dataBuffer = ByteBuffer.wrap(sharedByteArray)
            send(dataBlockReq)
        }

        val response = recvResponse(throwOnFail = true, "Server failed to receive archive")
        JanusMessage.recycle(dataBlockReq, uploadArchiveReq, response)

        pendingArchiveSequences += seqId

        val timeEndMillis = System.currentTimeMillis()
        var timeCostMillis = timeEndMillis - timeBeginMillis
        if (timeCostMillis <= 0)
            timeCostMillis = 1
        val speedMBps = archiveSize * 1000 / 1024 / 1024 / timeCostMillis
        Logger.success("Archive uploaded. Archive size: ${archiveSize / 1024 / 1024}MB. At speed: $speedMBps MB/s")
    }


    /**
     *
     * @return Pair(n archives confirmed, m failed). If null, no archive pending.
     */
    suspend fun confirmArchives(noBlock: Boolean = false): Pair<Long, Long>? {
        if (pendingArchiveSequences.isEmpty())
            return null

        val req = JanusMessage.create(JanusMessage.ConfirmArchives.typeCode) as JanusMessage.ConfirmArchives
        req.noBlock = noBlock
        send(req)

        val res = recvResponse(throwOnFail = true, "Server failed to confirm archives")
        val data = ByteBuffer.wrap(res.data)

        var nSuccess = 0L
        var nFailed = 0L

        while (data.remaining() >= Long.SIZE_BYTES + Int.SIZE_BYTES) {
            val seqId = data.getLong()
            val status = data.getInt()
            if (!pendingArchiveSequences.contains(seqId))
                Logger.error("Archive's seq id not found: $seqId")
            pendingArchiveSequences.remove(seqId)
            if (status == 0) {
                nSuccess++
            }
            else {
                nFailed++
            }
        }

        JanusMessage.recycle(req, res)
        return Pair(nSuccess, nFailed)
    }
}
