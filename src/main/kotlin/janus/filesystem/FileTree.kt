// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import io.github.flowerblackg.janus.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.name


@Serializable
enum class FileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER
}


object PathSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Path", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Path) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Path = Path(decoder.decodeString())
}


@Serializable
data class FileTree(
    var type: FileType = FileType.OTHER,
    var name: String = "",
    var children: MutableList<FileTree> = ArrayList(),
    @Transient
    var parent: FileTree? = null,
    @Serializable(with = PathSerializer::class)
    var path: Path = Path("")
) {
    companion object {
        @ExperimentalSerializationApi
        fun from(bytes: ByteArray): FileTree? {
            val tree = ProtoBuf.decodeFromByteArray(FileTree.serializer(), bytes)

            if (!tree.isSafe()) {
                Logger.warn("Malicious FileTree detected: Contains illegal path references.")
                return null
            }

            tree.fixParentReferences()
            return tree
        }
    }

    fun isSafe(basePath: Path = this.path): Boolean {
        return runCatching {
            val resolvedPath = basePath.resolve(this.path).normalize().toAbsolutePath()
            val absoluteBase = basePath.toAbsolutePath().normalize()

            // Check if the resolved path still starts with the base directory
            if (!resolvedPath.startsWith(absoluteBase)) {
                return false
            }

            // Recurse through children
            children.all { it.isSafe(basePath) }
        }.getOrNull() ?: false
    }


    fun fixParentReferences() {
        for (child in children) {
            child.parent = this
            child.fixParentReferences()
        }
    }

    fun hasDuplicatedNamesInDirectory(recursive: Boolean = false): Boolean {
        if (type != FileType.DIRECTORY)
            return false

        val names = HashSet<String>()
        for (child in children) {
            if (names.contains(child.name)) {
                return true
            }
            names.add(child.name)
        }

        if (!recursive)
            return false

        for (child in children) {
            if (child.hasDuplicatedNamesInDirectory(true)) {
                return true
            }
        }

        return false
    }
}


suspend fun Path.globFiles(): FileTree? = withContext(Dispatchers.IO) {
    return@withContext globFilesInternal(null, this@globFiles, null)
}


suspend fun Path.globFilesRelative(): FileTree? = withContext(Dispatchers.IO) {
    val rootPath = this@globFilesRelative.toAbsolutePath()
    return@withContext globFilesInternal(rootPath, rootPath, null)
}


private suspend fun globFilesInternal(root: Path?, current: Path, parent: FileTree? = null): FileTree? = withContext(Dispatchers.IO) {
    val attrs = try {
        Files.readAttributes(current, BasicFileAttributes::class.java)
    } catch (e: Exception) {
        Logger.error("Failed to read attributes of $current: ${e.message}")
        return@withContext null
    }

    val relativePath = root?.relativize(current.toAbsolutePath()) ?: current

    val node = FileTree(
        type = when {
            attrs.isDirectory -> FileType.DIRECTORY
            attrs.isSymbolicLink -> FileType.SYMLINK
            attrs.isRegularFile -> FileType.FILE
            else -> FileType.OTHER
        },
        path = relativePath,
        name = current.name,
        parent = parent
    )

    if (node.type == FileType.DIRECTORY) {
        val childPaths = try {
            Files.newDirectoryStream(current).use { it.toList() }
        } catch (e: Exception) {
            Logger.error("Failed to list children of $current: ${e.message}")
            emptyList()
        }

        val children = if (childPaths.size < 16) {
            childPaths.map { globFilesInternal(root, it, node) }
        } else {
            childPaths.map { async { globFilesInternal(root, it, node) } }.awaitAll()
        }

        node.children = children.filterNotNull().toMutableList()
    }

    return@withContext node
}

