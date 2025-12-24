// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption


fun moveFile(src: Path, dst: Path, deleteSrcOnFailure: Boolean = false): Result<Unit> {
    runCatching {
        Files.move(
            src,
            dst,
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
        )
    }.exceptionOrNull()?.runCatching {
        Files.move(
            src,
            dst,
            StandardCopyOption.REPLACE_EXISTING
        )
    }?.onFailure {
        if (deleteSrcOnFailure)
            runCatching { Files.deleteIfExists(src) }

        return Result.failure(Exception("Failed to move file: $src to $dst"))
    }

    return Result.success(Unit)
}
