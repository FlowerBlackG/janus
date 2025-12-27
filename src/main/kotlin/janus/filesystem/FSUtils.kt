// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption


object FSUtils {

    val defaultFs = FileSystems.getDefault()

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


    /**
     * Helper to check if a relative path matches the ignore configuration.
     * Adapts basic .gitignore syntax to Java PathMatchers.
     *
     * @author Google Gemini 3.0 Pro
     */
    fun shouldIgnore(relativePath: Path, isDirectory: Boolean, ignoreList: Iterable<String>): Boolean {
        val fs = defaultFs
        var ignored = false

        for (rawLine in ignoreList) {
            var line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#"))
                continue

            val isNegative = line.startsWith("!")

            var pattern = if (isNegative) line.drop(1).trim() else line
            var expectsDirectory = pattern.endsWith("/")

            if (expectsDirectory && !isDirectory)
                continue

            // Handle directory specific ignores (simple check)
            // If pattern ends with /, it expects a directory, but here we simply match the string prefix
            if (expectsDirectory) {
                pattern = pattern.dropLast(1)
            }

            // Convert gitignore syntax to glob
            val globPattern = when {
                // Absolute path relative to root (e.g., /build)
                pattern.startsWith("/") -> "glob:${pattern.drop(1)}"
                // Recursive match (e.g., *.class or build/)
                else -> "glob:{${pattern},**/${pattern}}"
            }

            fs.runCatching { getPathMatcher(globPattern) }.onSuccess { matcher ->
                if (matcher.matches(relativePath))
                    ignored = !isNegative
            }
        }

        return ignored
    }

}
