// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption


class MemoryMappedFile(val path: Path) : AutoCloseable {

    protected lateinit var fileChannel: FileChannel
    protected lateinit var arena: Arena

    /**
     * This is public, but you should use it carefully.
     */
    lateinit var segment: MemorySegment

    var size: Long = 0
        protected set

    var readPos: Long = 0
    var writePos: Long = 0

    var closed = false
        private set

    companion object {
        @Throws(IOException::class)
        fun createAndMap(path: Path, size: Long): MemoryMappedFile {
            assert(size >= 0) { "Size must be non-negative, but was $size" }

            val mmf = MemoryMappedFile(path = path)
            mmf.size = size
            mmf.fileChannel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            mmf.fileChannel.truncate(size)

            mmf.arena = Arena.ofShared()
            mmf.segment = mmf.fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, size, mmf.arena)

            return mmf
        }


        @Throws(IOException::class)
        fun openAndMap(
            path: Path,
            mode: FileChannel.MapMode = FileChannel.MapMode.READ_ONLY
        ): MemoryMappedFile {
            val mmf = MemoryMappedFile(path = path)
            val options = if (mode == FileChannel.MapMode.READ_ONLY) {
                arrayOf(StandardOpenOption.READ)
            } else {
                arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
            }

            mmf.fileChannel = FileChannel.open(path, *options)
            mmf.size = mmf.fileChannel.size()

            mmf.arena = Arena.ofShared()
            mmf.segment = mmf.fileChannel.map(mode, 0, mmf.size, mmf.arena)

            return mmf
        }
    }


    fun write(offset: Long, data: ByteArray) {
        checkNotClosed()
        checkBufferValid(offset, data.size.toLong())
        MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, offset, data.size.toLong())
        this.writePos = offset + data.size
    }


    fun write(buffer: ByteBuffer, offset: Long = this.writePos) {
        checkNotClosed()
        val remaining = buffer.remaining().toLong()
        checkBufferValid(offset, remaining)

        val src = MemorySegment.ofBuffer(buffer)
        segment.asSlice(offset, remaining).copyFrom(src)

        // Update positions.
        this.writePos = offset + remaining
        buffer.position(buffer.position() + remaining.toInt())
    }


    fun read(buffer: ByteBuffer, size: Int = buffer.remaining(), offset: Long = this.readPos): Int {
        checkNotClosed()
        val length = size.toLong()
        checkBufferValid(offset, length)

        val srcSlice = segment.asSlice(offset, length)
        val dest = MemorySegment.ofBuffer(buffer)

        dest.copyFrom(srcSlice)

        this.readPos = offset + length
        buffer.position(buffer.position() + size)

        return size
    }


    @Throws(IllegalStateException::class)
    protected fun checkNotClosed() {
        if (closed || !arena.scope().isAlive)
            throw IllegalStateException("This file is already closed.")
    }


    protected fun checkBufferValid(offset: Long, length: Long) {
        require(offset >= 0) { "Offset must be non-negative, but was $offset" }
        require(length >= 0) { "Size must be non-negative, but was $length" }
        if (offset + length > size) {
            throw IndexOutOfBoundsException("Offset $offset + length $length exceeds size $size")
        }
    }


    fun force() {
        checkNotClosed()
        if (segment.isMapped)
            segment.force()
    }


    @Throws(Exception::class)
    override fun close() {
        if (closed)
            return

        var err: Throwable? = null

        if (arena.scope().isAlive && segment.isMapped)
            runCatching { segment.force() }.onFailure { err = it }

        runCatching { arena.close() }.onFailure {
            if (err == null)
                err = it
            else
                err.addSuppressed(it)
        }

        runCatching { fileChannel.close() }.onFailure {
            if (err == null)
                err = it
            else
                err.addSuppressed(it)
        }

        closed = true
        err?.let { throw it }
    }
}
