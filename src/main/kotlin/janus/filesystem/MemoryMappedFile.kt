// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import io.github.flowerblackg.janus.logging.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption


class MemoryMappedFile : AutoCloseable {

    protected lateinit var fileChannel: FileChannel
    var chunkSize: Long = 0
        protected set
    var size: Long = 0
        protected set

    protected val chunkBuffers = mutableListOf<MappedByteBuffer>()

    var readPos: Long = 0
        protected set
    var writePos: Long = 0
        protected set

    var closed = false
        protected set

    companion object {
        /**
         * Since Java's ByteBuffer uses Integer as its size type, we need a buffer array to store files larger than 2GB.
         */
        private const val DEFAULT_CHUNK_SIZE = 1 * 1024L * 1024L * 1024L

        @Throws(IOException::class)
        fun createAndMap(path: Path, size: Long): MemoryMappedFile {
            assert(size >= 0) { "Size must be non-negative, but was $size" }

            val mmf = MemoryMappedFile()
            mmf.size = size
            mmf.chunkSize = DEFAULT_CHUNK_SIZE
            mmf.fileChannel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            mmf.fileChannel.truncate(size)

            mmf.mapChunks(mode = FileChannel.MapMode.READ_WRITE)

            return mmf
        }


        @Throws(IOException::class)
        fun openAndMap(
            path: Path,
            mode: FileChannel.MapMode = FileChannel.MapMode.READ_ONLY
        ): MemoryMappedFile {
            val mmf = MemoryMappedFile()
            val options = if (mode == FileChannel.MapMode.READ_ONLY) {
                arrayOf(StandardOpenOption.READ)
            } else {
                arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
            }

            mmf.fileChannel = FileChannel.open(path, *options)
            mmf.size = mmf.fileChannel.size()
            mmf.chunkSize = DEFAULT_CHUNK_SIZE

            mmf.mapChunks(mode)
            return mmf
        }
    }


    /**
     * Must have [fileChannel] and [size] initialized before calling this method.
     */
    private fun mapChunks(mode: FileChannel.MapMode) {
        if (chunkBuffers.isNotEmpty()) {
            Logger.error("MemoryMappedFile is already mapped", trace = Throwable())
            return
        }

        var remainingSize = size

        while (remainingSize > 0) {
            val currentChunkSize = minOf(chunkSize, remainingSize)
            val buffer = fileChannel.map(
                mode,
                size - remainingSize,
                currentChunkSize
            )
            chunkBuffers += buffer
            remainingSize -= currentChunkSize
        }
    }


    fun write(offset: Long, data: ByteArray) {
        this.write(buffer = ByteBuffer.wrap(data), offset = offset)
    }


    fun write(buffer: ByteBuffer, offset: Long = this.writePos) {
        checkNotClosed()
        checkBufferValid(this.writePos, buffer.remaining().toLong())

        this.writePos = offset
        var remaining = buffer.remaining()

        while (remaining > 0) {
            val (chunkIdx, chunkOffset) = calculateChunkPosition(this.writePos)
            val chunkBuffer = chunkBuffers[chunkIdx]
            val chunkRemaining = chunkBuffer.capacity() - chunkOffset
            val writeSize = minOf(chunkRemaining, remaining)
            chunkBuffer.position(chunkOffset)
            val tmpBuf = buffer.duplicate()
            tmpBuf.limit(tmpBuf.position() + writeSize)
            chunkBuffer.put(tmpBuf)

            remaining -= writeSize
            this.writePos += writeSize

            buffer.position(buffer.position() + writeSize)
        }
    }


    fun read(buffer: ByteBuffer, size: Int = buffer.remaining(), offset: Long = this.readPos): Int {
        checkNotClosed()
        checkBufferValid(offset, size.toLong())

        this.readPos = offset
        var remainingToRead = size

        while (remainingToRead > 0) {
            val (chunkIdx, chunkOffset) = calculateChunkPosition(readPos)
            val chunkBuffer = chunkBuffers[chunkIdx]

            // Calculate how much we can read from the current chunk
            val chunkRemaining = chunkBuffer.capacity() - chunkOffset
            val readSize = minOf(chunkRemaining, remainingToRead)

            // Set position and create a slice to prevent affecting original chunk state
            chunkBuffer.position(chunkOffset)
            val slice = chunkBuffer.duplicate()
            slice.limit(chunkOffset + readSize)

            // Transfer data to the destination buffer
            buffer.put(slice)

            remainingToRead -= readSize
            readPos += readSize
        }

        return size
    }


    @Throws(IllegalStateException::class)
    protected fun checkNotClosed() {
        if (closed)
            throw IllegalStateException("This file is already closed.")
    }


    protected fun checkBufferValid(offset: Long, length: Long) {
        require(offset >= 0) { "Offset must be non-negative, but was $offset" }
        require(length >= 0) { "Size must be non-negative, but was $length" }
        require(offset + length <= size) { "Offset + size must be less than or equal to the file size, but was $offset + $length" }
    }


    /**
     * @return Pair(chunkIndex, chunkOffset)
     */
    protected fun calculateChunkPosition(offset: Long): Pair<Int, Int> {
        return Pair((offset / chunkSize).toInt(), (offset % chunkSize).toInt())
    }


    fun force() {
        checkNotClosed()
        chunkBuffers.forEach { it.force() }
    }


    @Throws(Exception::class)
    override fun close() {
        if (closed)
            return

        var primaryException: Throwable?

        primaryException = runCatching { this.force() }.exceptionOrNull()

        runCatching { fileChannel.close() }.exceptionOrNull()?.let { e ->
            if (primaryException != null)
                primaryException.addSuppressed(e)
            else
                primaryException = e
        }

        chunkBuffers.clear()  // promote GC
        closed = true

        if (primaryException != null)
            throw primaryException
    }

}
