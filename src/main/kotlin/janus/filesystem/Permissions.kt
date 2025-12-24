// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem


import io.github.flowerblackg.janus.logging.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission


/**
 * Get POSIX permissions. (e.g., 0644).
 * On Windows, simplify this to Read/Write/Execute for the current user.
 */
fun Path.getPermissionMask(): Int = runCatching {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        val posix = Files.getPosixFilePermissions(this)
        posixToMask(posix)
    }
    else {
        var mask = 0
        if (Files.isReadable(this))
            mask = mask or 0b0_100_100_100
        if (Files.isWritable(this))
            mask = mask or 0b0_010_010_010
        if (Files.isExecutable(this))
            mask = mask or 0b0_001_001_001
        mask
    }
}.getOrNull() ?: 0


fun Path.applyPermissionMask(mask: Int) {
    val fs = FileSystems.getDefault()

    if (fs.supportedFileAttributeViews().contains("posix")) {
        runCatching {
            val perms = mutableSetOf<PosixFilePermission>()
            // Owner
            if (mask and 0b0_100_000_000 != 0)
                perms.add(PosixFilePermission.OWNER_READ)
            if (mask and 0b0_010_000_000 != 0)
                perms.add(PosixFilePermission.OWNER_WRITE)
            if (mask and 0b0_001_000_000 != 0)
                perms.add(PosixFilePermission.OWNER_EXECUTE)

            // Group
            if (mask and 0b0_000_100_000 != 0)
                perms.add(PosixFilePermission.GROUP_READ)
            if (mask and 0b0_000_010_000 != 0)
                perms.add(PosixFilePermission.GROUP_WRITE)
            if (mask and 0b0_000_001_000 != 0)
                perms.add(PosixFilePermission.GROUP_EXECUTE)

            // Others
            if (mask and 0b0_000_000_100 != 0)
                perms.add(PosixFilePermission.OTHERS_READ)
            if (mask and 0b0_000_000_010 != 0)
                perms.add(PosixFilePermission.OTHERS_WRITE)
            if (mask and 0b0_000_000_001 != 0)
                perms.add(PosixFilePermission.OTHERS_EXECUTE)

            Files.setPosixFilePermissions(this, perms)
        }.exceptionOrNull()?.let { Logger.error("Failed to apply permission bits to $this") }
    }
    else {
        val file = this.toFile()

        val isReadable = (mask and 0b100_100_100 != 0)
        val isWritable = (mask and 0b010_000_000 != 0)
        val isExecutable = (mask and 0b001_001_001 != 0)

        file.setReadable(isReadable, false)
        file.setWritable(isWritable, false)
        file.setExecutable(isExecutable, false)
    }
}


private fun posixToMask(perms: Set<PosixFilePermission>): Int {
    var mask = 0
    if (perms.contains(PosixFilePermission.OWNER_READ))
        mask = mask or 0x100
    if (perms.contains(PosixFilePermission.OWNER_WRITE))
        mask = mask or 0x080
    if (perms.contains(PosixFilePermission.OWNER_EXECUTE))
        mask = mask or 0x040
    if (perms.contains(PosixFilePermission.GROUP_READ))
        mask = mask or 0x020
    if (perms.contains(PosixFilePermission.GROUP_WRITE))
        mask = mask or 0x010
    if (perms.contains(PosixFilePermission.GROUP_EXECUTE))
        mask = mask or 0x008
    if (perms.contains(PosixFilePermission.OTHERS_READ))
        mask = mask or 0x004
    if (perms.contains(PosixFilePermission.OTHERS_WRITE))
        mask = mask or 0x002
    if (perms.contains(PosixFilePermission.OTHERS_EXECUTE))
        mask = mask or 0x001
    return mask
}
