// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.filesystem.MemoryMappedFile
import io.github.flowerblackg.janus.filesystem.moveFile
import io.github.flowerblackg.janus.logging.Logger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.readByteArray
import kotlinx.io.readTo
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import kotlin.io.path.absolute
import kotlin.io.path.name
import kotlin.math.min


/**
 * Each lounge should hold one archive extractor pool.
 */
class ArchiveExtractorPool {
    /**
     * Must be set before use.
     */
    var workspace: Config.WorkspaceConfig? = null

    protected val extractorJobs = mutableMapOf<Long, Deferred<Long>>()


    /**
     * If critical failure, and producerJob is here, it will be canceled.
     *
     * This method creates a coroutine job and returns without blocking.
     */
    fun extract(
        dataChannel: Channel<ByteArray>,
        archiveSize: Long,
        seqId: Long,
        producerJob: Job? = null
    ) {
        require(workspace != null) { "Workspace not set" }

        val extractJob = GlobalCoroutineScopes.IO.async {
            runCatching {
                val reader = ChannelByteReader(dataChannel)
                var processedSize = 0L

                while (processedSize < archiveSize) {
                    val filePathSize = reader.readInt()
                    val filePermissionMask = reader.readInt()
                    val fileDataSize = reader.readLong()
                    processedSize += 16

                    // Read file path
                    val pathBytes = reader.readBytes(filePathSize.toInt())
                    val relativePath = String(pathBytes, Charsets.UTF_8)
                    processedSize += filePathSize

                    // Security Check (ZipSlip prevention)
                    val outFile = workspace!!.path.resolve(relativePath).absolute().normalize()
                    if (!outFile.startsWith(workspace!!.path.absolute().normalize())) {
                        Logger.warn("Security Warning: Blocked malicious path traversal: $relativePath")
                        reader.skip(fileDataSize)
                    } else {
                        // Ensure directory structure exists
                        Files.createDirectories(outFile.parent)

                        val tmpPath = outFile.resolveSibling("${outFile.name}.janus-sync-tmp")

                        // Memory-Mapped Writing
                        // MemoryMappedFile handles the mapping and truncate operations.
                        MemoryMappedFile.createAndMap(path = tmpPath, size = fileDataSize, permissionBits = filePermissionMask).use { mmf ->
                            // Efficiently copy data from Channel buffers to the mapped MemorySegment
                            reader.readToSegment(mmf.segment, fileDataSize)
                        }

                        moveFile(tmpPath, outFile, deleteSrcOnFailure = true)

                        Logger.success("Extracted: $relativePath ($fileDataSize bytes)")
                    }
                    processedSize += fileDataSize
                }

                return@async seqId

            }.onFailure { e ->
                producerJob?.cancel()
                Logger.error("Archive extraction failed: ${e.message}", trace = e)
                throw e
            }

            return@async seqId
        }

        extractorJobs[seqId] = extractJob
    }


    /**
     * @return Each pair is (seqId, status). Status is 0 if success, other if failed.
     */
    suspend fun checkExtractedArchives(blockUntilSome: Boolean = true): List<Pair<Long, Int>> {
        if (extractorJobs.isEmpty())
            return emptyList()

        val pending = mutableMapOf<Long, Deferred<Long>>()
        val ret = mutableListOf<Pair<Long, Int>>()

        for ((seqId, job) in extractorJobs) {
            if (job.isActive) {
                pending[seqId] = job
                continue
            }

            val seqFromRet = runCatching { job.await() }.getOrNull() ?: run {
                ret += Pair(seqId, 1)
                null
            }

            seqFromRet?.let {
                ret += Pair(seqId, if (seqFromRet == seqId) 0 else 1)
            }
        }
        extractorJobs.clear()
        extractorJobs.putAll(pending)


        if (ret.isEmpty() && blockUntilSome) {
            val firstSeqId = extractorJobs.keys.first()
            val firstResult = runCatching { extractorJobs[firstSeqId]!!.await() }.getOrNull()

            extractorJobs.remove(firstSeqId)
            ret += Pair(firstSeqId, if (firstResult == firstSeqId) 0 else 1)
        }

        return ret
    }
}


/**
 * Co-engined with Google Gemini 3.0 Pro.
 */
class ChannelByteReader(private val channel: Channel<ByteArray>) {
    private val buffer = Buffer()

    private suspend fun request(size: Long) {
        while (buffer.size < size) {
            val bytes = channel.receiveCatching().getOrNull()
                ?: throw EOFException("Stream ended prematurely while waiting for $size bytes")
            buffer.write(bytes)
        }
    }

    suspend fun readLong(): Long {
        request(Long.SIZE_BYTES.toLong())
        return buffer.readLong()
    }

    suspend fun readInt(): Int {
        request(Int.SIZE_BYTES.toLong())
        return buffer.readInt()
    }

    suspend fun readBytes(size: Int): ByteArray {
        request(size.toLong())
        return buffer.readByteArray(size)
    }

    suspend fun skip(size: Long) {
        var remaining = size
        while (remaining > 0) {
            val toSkip = min(buffer.size, remaining)
            buffer.skip(toSkip)
            remaining -= toSkip
            if (remaining > 0)
                request(1)
        }
    }


    suspend fun readToSegment(destSegment: MemorySegment, totalSize: Long) {
        var offset = 0L
        var remaining = totalSize

        val tempArray = ByteArray(256 * 1024)

        while (remaining > 0) {
            request(1)
            val step = minOf(buffer.size, remaining, tempArray.size.toLong()).toInt()

            buffer.readTo(tempArray, 0, step)

            MemorySegment.copy(
                MemorySegment.ofArray(tempArray), 0,
                destSegment, offset,
                step.toLong()
            )

            offset += step
            remaining -= step
        }
    }
}

