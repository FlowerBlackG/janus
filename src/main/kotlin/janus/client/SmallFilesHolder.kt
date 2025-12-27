// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.client

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.MemoryMappedFile
import io.github.flowerblackg.janus.filesystem.SyncPlan
import io.github.flowerblackg.janus.filesystem.getPermissionMask
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.use


private const val SMALL_FILES_HOLDER_ARCHIVE_SIZE_THRESHOLD = 128 * 1024 * 1024
private const val SMALL_FILES_HOLDER_N_FILES_THRESHOLD = 1024  // NEVER SET THIS TO 0 OR LOWER.


data class SmallFilesHolder(
    val ws: Config.WorkspaceConfig,
    val files: MutableList<SyncPlan> = mutableListOf(),
    var archiveSize: Long = 0L
) {
    /**
     * @param file You should ensure it is file and needs UPLOAD.
     */
    fun add(file: SyncPlan) {
        files += file
        archiveSize += file.fileSize
        archiveSize += file.path.toString().replace('\\', '/').toByteArray().size
        archiveSize += Long.SIZE_BYTES + Long.SIZE_BYTES
    }

    operator fun plusAssign(file: SyncPlan) = add(file)

    fun toByteBuffer(clearSelf: Boolean = true): ByteBuffer {

        val buf = ByteBuffer.allocate(archiveSize.toInt())

        for (file in files) {
            val realPath = ws.path.resolve(file.path)
            val pathBytes = file.path.toString().replace('\\', '/').toByteArray()
            buf.putInt(pathBytes.size)
                .putInt(realPath.getPermissionMask())
                .putLong(file.fileSize)
                .put(pathBytes)

            MemoryMappedFile.openAndMap(path = realPath, mode = FileChannel.MapMode.READ_ONLY).use { mmf ->
                mmf.read(buffer = buf, size = file.fileSize.toInt())
            }
        }

        if (clearSelf) {
            files.clear()
            archiveSize = 0L
        }

        return buf.flip()
    }

    fun isNearlyFull(): Boolean {
        val archiveLargeEnough = archiveSize >= SMALL_FILES_HOLDER_ARCHIVE_SIZE_THRESHOLD
        val nFilesEnough = files.size >= SMALL_FILES_HOLDER_N_FILES_THRESHOLD
        return archiveLargeEnough || nFilesEnough
    }
}
