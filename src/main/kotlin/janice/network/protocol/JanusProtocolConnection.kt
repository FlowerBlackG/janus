// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.network.protocol

import io.github.flowerblackg.janice.config.Config
import io.github.flowerblackg.janice.filesystem.FileTree
import io.github.flowerblackg.janice.filesystem.MemoryMappedFile
import io.github.flowerblackg.janice.filesystem.SyncPlan
import io.github.flowerblackg.janice.filesystem.getPermissionMask
import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.JaniceSocket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class JaniceProtocolConnection(val janiceSocket: JaniceSocket) : AutoCloseable {
    override fun close() {
        janiceSocket.close()
    }

    suspend fun send(janiceMsg: JaniceMessage) {
        janiceSocket.write(janiceMsg.toByteBuffer())
    }

    suspend fun sendResponse(code: Int, msg: ByteBuffer? = null, data: ByteBuffer? = null) {
        val response = JaniceMessage.create(JaniceMessage.CommonResponse.typeCode) as JaniceMessage.CommonResponse
        response.code = code
        response.data = data?.let {
            val arr = ByteArray(it.remaining())
            it.get(arr)
            arr
        } ?: byteArrayOf()

        response.msgAsBytes = msg?.let {
            val arr = ByteArray(it.remaining())
            it.get(arr)
            arr
        } ?: byteArrayOf()
        send(response)

        JaniceMessage.recycle(response)
    }

    suspend fun sendResponse(code: Int, msg: ByteArray?, data: ByteArray?) {
        return this.sendResponse(
            code,
            ByteBuffer.wrap(msg ?: byteArrayOf()),
            ByteBuffer.wrap(data ?: byteArrayOf())
        )
    }

    suspend fun sendResponse(code: Int, msg: String?, data: String? = null) {
        return this.sendResponse(code, msg?.toByteArray(StandardCharsets.UTF_8), data?.toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun sendResponse(code: Int, msg: String? = null, data: ByteArray?) {
        return this.sendResponse(code, msg?.toByteArray(StandardCharsets.UTF_8), data)
    }

    suspend fun sendResponse(code: Int, msg: ByteArray?, data: String?) {
        return this.sendResponse(code, msg, data?.toByteArray(StandardCharsets.UTF_8))
    }


    /**
     *
     * @param throwOnFail Whether throw when Response code is not 0.
     */
    suspend fun recvResponse(throwOnFail: Boolean = false, throwOnFailPrompt: String? = null): JaniceMessage.CommonResponse {
        val res = recvMessage(JaniceMessage.CommonResponse.typeCode) as JaniceMessage.CommonResponse
        if (throwOnFail && !res.success) {
            val throwMsg = throwOnFailPrompt ?: "Response code is not 0: ${res.code}. Message: ${res.msg}"
            JaniceMessage.recycle(res)
            throw Exception(throwMsg)
        }
        return res
    }

    suspend fun recvHeader(): Pair<Int, Long>? {
        val header = ByteBuffer.allocate(JaniceMessage.HEADER_LENGTH)
        runCatching { janiceSocket.read(header) }.getOrNull() ?: return null
        return JaniceMessage.decodeHeader(header.flip())
    }

    suspend fun recvBody(bodyLength: Long): ByteBuffer {
        if (bodyLength >= Int.MAX_VALUE) {
            throw Exception("bodyLength too large: $bodyLength")
        }

        val buf = ByteBuffer.allocate(bodyLength.toInt())
        janiceSocket.read(buf)
        return buf.flip()
    }


    @Throws(Exception::class)
    suspend fun recvMessage(requiredMsgType: Int? = null): JaniceMessage {
        val (msgType, bodyLength) = recvHeader() ?: throw Exception("Failed to recv header.")
        val body = recvBody(bodyLength)

        if (requiredMsgType != null && requiredMsgType != msgType)
            throw Exception("Wrong msg type: $msgType. Required msg type: $requiredMsgType")

        val janiceMsg = JaniceMessage.decode(body, msgType)
        return janiceMsg
    }


    enum class Role { SERVER, CLIENT }


    protected suspend fun serverModeHello() {
        Logger.info("Running server mode Hello.")

        val fromClient = recvMessage(JaniceMessage.Hello.typeCode) as JaniceMessage.Hello
        if (fromClient.protocolVersions.firstOrNull() != JaniceMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${fromClient.protocolVersions.firstOrNull()}")
        }

        val toClient = JaniceMessage.create(JaniceMessage.Hello.typeCode) as JaniceMessage.Hello
        toClient.protocolVersions += JaniceMessage.PROTOCOL_VERSION
        send(toClient)

        val finalMsg = recvMessage(JaniceMessage.Hello.typeCode) as JaniceMessage.Hello
        if (finalMsg.protocolVersions.firstOrNull() != JaniceMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${finalMsg.protocolVersions.firstOrNull()}")
        }
        Logger.success("Server mode Hello: Using protocol version: ${JaniceMessage.PROTOCOL_VERSION}")

        JaniceMessage.recycle(fromClient, toClient, finalMsg)
    }
    protected suspend fun clientModeHello() {
        Logger.info("Running client mode Hello.")
        val toServer = JaniceMessage.create(JaniceMessage.Hello.typeCode) as JaniceMessage.Hello
        toServer.protocolVersions += JaniceMessage.PROTOCOL_VERSION
        send(toServer)

        val fromServer = recvMessage(JaniceMessage.Hello.typeCode) as JaniceMessage.Hello
        if (fromServer.protocolVersions.firstOrNull() != JaniceMessage.PROTOCOL_VERSION) {
            throw Exception("Wrong protocol version: ${fromServer.protocolVersions.firstOrNull()}")
        }

        send(toServer)
        Logger.success("Client mode Hello: Using protocol version: ${JaniceMessage.PROTOCOL_VERSION}")

        JaniceMessage.recycle(toServer, fromServer)
    }
    suspend fun hello(mode: Role) {
        when (mode) {
            Role.SERVER -> serverModeHello()
            Role.CLIENT -> clientModeHello()
        }
    }


    @OptIn(ExperimentalUuidApi::class)
    protected suspend fun serverModeAuth(workspaces: Map<String, Config.WorkspaceConfig>): Config.WorkspaceConfig {
        val fromClient1 = recvMessage(JaniceMessage.Auth.typeCode) as JaniceMessage.Auth
        val wsName = String(fromClient1.challenge, StandardCharsets.UTF_8)

        val workspace = workspaces[wsName]
        val aes = workspace?.crypto?.aes

        if (workspace == null)
            Logger.warn("Failed to load workspace required by client: $wsName. Client denied.")

        val challenge = Uuid.random().toHexString() + Random.nextLong()
        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)
        val toClient = JaniceMessage.create(JaniceMessage.Auth.typeCode) as JaniceMessage.Auth
        toClient.challenge = challengeBytes
        send(toClient)
        val fromClient2 = recvMessage(JaniceMessage.Auth.typeCode) as JaniceMessage.Auth

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

        JaniceMessage.recycle(toClient, fromClient2, fromClient1)
        return workspace
    }

    protected suspend fun clientModeAuth(workspaceConfig: Config.WorkspaceConfig) {
        val aes = workspaceConfig.crypto.aes
        val wsName = workspaceConfig.remoteName

        // Auth : step 1

        val toSvr = JaniceMessage.create(JaniceMessage.Auth.typeCode) as JaniceMessage.Auth
        toSvr.challenge = wsName.toByteArray(StandardCharsets.UTF_8)

        send(toSvr)

        // Auth : step 2 & 3

        val fromSvr = recvMessage(JaniceMessage.Auth.typeCode) as JaniceMessage.Auth
        val encrypted = aes?.encrypt(fromSvr.challenge) ?: fromSvr.challenge

        toSvr.reset()
        toSvr.challenge = encrypted
        send(toSvr)

        // Auth : step 4

        val response = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on auth")

        JaniceMessage.recycle(fromSvr, toSvr, response)
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
        val req = JaniceMessage.create(JaniceMessage.FetchFileTree.typeCode) as JaniceMessage.FetchFileTree
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
        val req = JaniceMessage.create(JaniceMessage.GetSystemTimeMillis.typeCode) as JaniceMessage.GetSystemTimeMillis
        send(req)
        val res = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on get system time millis")

        if (res.data.size != Long.SIZE_BYTES)
            throw Exception("Wrong data size: ${res.data.size}")

        return ByteBuffer.wrap(res.data).getLong()
    }


    suspend fun commitSyncPlan(syncPlans: List<SyncPlan>) {
        val req = JaniceMessage.create(JaniceMessage.CommitSyncPlan.typeCode) as JaniceMessage.CommitSyncPlan
        req.syncPlansBytes = syncPlans.map { it.encodeToByteArray() }
        send(req)

        val res = recvResponse(throwOnFail = true, throwOnFailPrompt = "Failed on commit sync plan")
        JaniceMessage.recycle(req, res)
    }


    private suspend fun sendFile(file: MemoryMappedFile) {
        val timeBeginMillis = System.currentTimeMillis()

        val dataBlockReq = JaniceMessage.create(JaniceMessage.DataBlock.typeCode) as JaniceMessage.DataBlock

        file.readPos = 0
        var remaining = file.size
        val chunkSize = 1 * 1024L * 1024
        var sharedByteArray = byteArrayOf()
        while (remaining > 0) {
            dataBlockReq.reset()
            val size = minOf(chunkSize, remaining)
            sharedByteArray = if (sharedByteArray.size == size.toInt()) sharedByteArray else ByteArray(size.toInt())
            dataBlockReq.dataBuffer = ByteBuffer.wrap(sharedByteArray)
            val bytesRead = file.read(dataBlockReq.dataBuffer)
            dataBlockReq.dataBuffer.flip()

            if (bytesRead != size.toInt())
                throw Exception("Failed to read file: ${file.path}")

            remaining -= size
            send(dataBlockReq)
        }

        JaniceMessage.recycle(dataBlockReq)

        val timeEndMillis = System.currentTimeMillis()
        var timeCostMillis = timeEndMillis - timeBeginMillis
        if (timeCostMillis <= 0)
            timeCostMillis = 1
        val speedMBps = file.size * 1000 / 1024 / 1024 / timeCostMillis
        Logger.success("File ${file.path.name} uploaded in $timeCostMillis ms. Speed: $speedMBps MB/s")
    }


    var nextSeqId = 1L
    private val pendingFileSequences = mutableSetOf<Long>()

    /**
     * @return seq Id of the file.
     */
    suspend fun uploadFile(
        filePath: Path,
        workspace: Config.WorkspaceConfig,
        asyncAck: Boolean = false,
        skipRecvResponse: Boolean = false
    ): Long {
        val realPath = workspace.path.resolve(filePath)
        val realPathAbs = realPath.absolute().normalize()
        if (!realPathAbs.startsWith(workspace.path.absolute().normalize()))
            throw Exception("File path is not in workspace: $realPath")

        val seqId = nextSeqId++
        val uploadFileReq = JaniceMessage.create(JaniceMessage.UploadFile.typeCode) as JaniceMessage.UploadFile
        uploadFileReq.path = filePath
        uploadFileReq.fileSize = Files.size(realPathAbs)
        uploadFileReq.permBits = realPath.getPermissionMask()
        uploadFileReq.seqId = seqId
        uploadFileReq.asyncAck = asyncAck

        send(uploadFileReq)

        JaniceMessage.recycle(uploadFileReq)

        MemoryMappedFile.openAndMap(realPathAbs, FileChannel.MapMode.READ_ONLY).use { sendFile(it) }

        if (asyncAck)
            pendingFileSequences += seqId
        
        if (skipRecvResponse)
            return seqId

        val response = recvResponse(throwOnFail = true, "Server failed to receive file: $realPathAbs")
        if (response.data.size != Long.SIZE_BYTES || ByteBuffer.wrap(response.data).getLong() != seqId) {
            throw Exception("Server failed to process file: $realPathAbs. Wrong seqId.")
        }
        JaniceMessage.recycle(response)
        return seqId
    }

    private val pendingArchiveSequences = mutableSetOf<Long>()

    suspend fun uploadArchive(byteBuffer: ByteBuffer, skipRecvResponse: Boolean = false) {
        val timeBeginMillis = System.currentTimeMillis()
        val archiveSize = byteBuffer.remaining().toLong()
        val DATA_BLOCK_SIZE = 2L * 1024 * 1024

        val uploadArchiveReq = JaniceMessage.create(JaniceMessage.UploadArchive.typeCode) as JaniceMessage.UploadArchive
        uploadArchiveReq.archiveSize = byteBuffer.remaining().toLong()
        val seqId = nextSeqId++
        uploadArchiveReq.seqId = seqId
        send(uploadArchiveReq)

        var sharedByteArray = byteArrayOf()
        val dataBlockReq = JaniceMessage.create(JaniceMessage.DataBlock.typeCode) as JaniceMessage.DataBlock

        while (byteBuffer.remaining() > 0) {
            val bytesToSend = minOf(byteBuffer.remaining().toLong(), DATA_BLOCK_SIZE)
            if (bytesToSend != sharedByteArray.size.toLong())
                sharedByteArray = ByteArray(bytesToSend.toInt())

            byteBuffer.get(sharedByteArray)
            dataBlockReq.reset()
            dataBlockReq.dataBuffer = ByteBuffer.wrap(sharedByteArray)
            send(dataBlockReq)
        }

        if (!skipRecvResponse) {
            val response = recvResponse(throwOnFail = true, "Server failed to receive archive")
            JaniceMessage.recycle(dataBlockReq, uploadArchiveReq, response)
        }

        pendingArchiveSequences += seqId

        val timeEndMillis = System.currentTimeMillis()
        var timeCostMillis = timeEndMillis - timeBeginMillis
        if (timeCostMillis <= 0)
            timeCostMillis = 1
        val speedMBps = archiveSize * 1000 / 1024 / 1024 / timeCostMillis
        Logger.success("Archive $seqId uploaded. Archive size: ${archiveSize / 1024 / 1024}MB. At speed: $speedMBps MB/s")
    }


    /**
     *
     * @return Pair(n archives confirmed, m failed). If null, no archive pending.
     */
    protected fun decodeConfirmMessage(bytes: ByteArray, seqContainer: MutableSet<Long>? = null): Pair<List<Long>, List<Long>> {
        val data = ByteBuffer.wrap(bytes)

        val success = mutableListOf<Long>()
        val failed = mutableListOf<Long>()

        while (data.remaining() >= Long.SIZE_BYTES + Int.SIZE_BYTES) {
            val seqId = data.getLong()
            val status = data.getInt()
            if (seqContainer != null) {
                if (!seqContainer.contains(seqId))
                    Logger.error("Archive's seq id not found: $seqId")
                seqContainer.remove(seqId)
            }
            if (status == 0) {
                success += seqId
            }
            else {
                failed += seqId
            }
        }

        return Pair(success, failed)
    }


    /**
     *
     * @return Pair(n archives confirmed, m failed). If null, no archive pending.
     */
    suspend fun confirmArchives(noBlock: Boolean = false): Pair<List<Long>, List<Long>>? {
        if (pendingArchiveSequences.isEmpty())
            return null

        val req = JaniceMessage.create(JaniceMessage.ConfirmArchives.typeCode) as JaniceMessage.ConfirmArchives
        req.noBlock = noBlock
        send(req)

        val res = recvResponse(throwOnFail = true, "Server failed to confirm archives")
        val ret = decodeConfirmMessage(res.data, pendingArchiveSequences)
        JaniceMessage.recycle(req, res)
        return ret
    }


    /**
     * @return Pair(n archives confirmed, m failed). If null, no file pending.
     */
    suspend fun confirmFiles(noBlock: Boolean = false): Pair<List<Long>, List<Long>>? {
        require(!noBlock) { "no block is NOT supported yet." }

        if (pendingFileSequences.isEmpty())
            return null
        val req = JaniceMessage.create(JaniceMessage.ConfirmFiles.typeCode) as JaniceMessage.ConfirmFiles
        req.noBlock = noBlock
        send(req)

        val res = recvResponse(throwOnFail = true, "Server failed to confirm files")
        val ret = decodeConfirmMessage(res.data, pendingFileSequences)
        JaniceMessage.recycle(req, res)
        return ret
    }


    suspend fun bye(expectResponse: Boolean) {
        val byeMsg = JaniceMessage.create(JaniceMessage.Bye.typeCode) as JaniceMessage.Bye
        send(byeMsg)
        Logger.success("Waved goodbye.")
        JaniceMessage.recycle(byeMsg)
        if (expectResponse)
            JaniceMessage.recycle(recvMessage(requiredMsgType = JaniceMessage.Bye.typeCode))
    }
}
