// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import io.github.flowerblackg.janus.logging.Logger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.TODO
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.system.exitProcess

sealed class JanusMessage private constructor() {
    /* ---------------- Begin of Companion ---------------- */
    companion object {
        const val MAGIC_STRING = "jANu"
        const val PROTOCOL_VERSION: Long = 1

        const val HEADER_LENGTH = 16
        const val MAX_BODY_SIZE = 1L * 1024 * 1024 * 1024

        /** override this. */
        const val typeCode = 0

        val registry = ConcurrentHashMap<Int, MessageObjectPool<JanusMessage>>()

        /**
         * Must ensure message is really Janus Protocol Message (whose magic matches).
         * @param data Should points to the beginning of message data body.
         *             `data.limit` can be trusted.
         */
        @Throws(Exception::class)
        fun decode(data: ByteBuffer, msgType: Int): JanusMessage {
            init()

            val msg = create(msgType)
            runCatching { msg.decodeBody(data) }.exceptionOrNull()?.let {
                recycle(msg)
                throw it
            }

            return msg
        }


        /**
         *
         * @return Pair(msgType, bodyLength) If null, header check failed.
         */
        fun decodeHeader(data: ByteBuffer, checkMsgType: Boolean = true): Pair<Int, Long>? {
            init()
            if (data.remaining() < HEADER_LENGTH)
                return null

            val magicBytes = ByteArray(MAGIC_STRING.length)
            data.get(magicBytes)
            val magic = String(magicBytes, StandardCharsets.US_ASCII)
            if (magic != MAGIC_STRING)
                return null

            val type = data.getInt()
            val bodyLength = data.getLong()

            if (checkMsgType)
                registry[type] ?: return null

            return Pair(type, bodyLength)
        }


        /**
         * @param data Points to the beginning of the whole message.
         *             It can contain more data than this exact message.
         *             This method only consumes this message (if no exception).
         */
        @Throws(Exception::class)
        fun decode(data: ByteBuffer): JanusMessage {
            init()

            val (type, bodyLength) = decodeHeader(data, checkMsgType = true) ?: throw Exception("Failed to decode header.")

            if (bodyLength > MAX_BODY_SIZE || bodyLength + data.position() > data.limit())
                throw Exception("body length too large")

            val oriDataLimit = data.limit()
            val bodyEnd = data.position() + bodyLength.toInt()
            data.limit(bodyEnd)

            val bodyView = data.slice()
            data.position(bodyEnd)
            data.limit(oriDataLimit)

            return decode(bodyView, type)
        }

        fun create(msgType: Int): JanusMessage {
            init()
            val pool = registry[msgType] ?: throw Exception("unknown message type: $msgType")
            return pool.borrow()
        }

        fun recycle(vararg msgs: JanusMessage) {
            init()
            for (msg in msgs)
                registry[msg.type]?.recycle(msg)
        }
    }


    /* ---------------- End of Companion ---------------- */

    /** Simply copy this line to each message struct and mark `override`. */
    open val type get() = typeCode
    /** override and implement this. */
    open val bodyLength: Long get() = 0

    @Throws(Exception::class)
    open fun decodeBody(data: ByteBuffer) {}
    @Throws(Exception::class)
    open fun encodeBody(container: ByteBuffer) {}
    open fun reset() {}


    fun encode(buffer: ByteBuffer) {
        buffer.put(MAGIC_STRING.toByteArray(StandardCharsets.US_ASCII))
            .putInt(type)
            .putLong(bodyLength)
        encodeBody(buffer)
    }


    fun toByteBuffer(): ByteBuffer {
        val totalSize = HEADER_LENGTH + bodyLength.toInt()
        val buffer = ByteBuffer.allocate(totalSize)
        encode(buffer)
        buffer.flip() // Prepare the buffer for reading/sending
        return buffer
    }


    /* ---------------- Start of Protocol Message Structs ---------------- */


    /*

    class Template : JanusMessage() {
        companion object {
            const val typeCode = TODO()
        }

        override val type get() = typeCode
        override val bodyLength get() = TODO()


        override fun decodeBody(data: ByteBuffer) {
            TODO()
        }

        override fun encodeBody(container: ByteBuffer) {
            TODO()
        }

        override fun reset() {
            TODO()
        }
    }

     */


    class CommonResponse : JanusMessage() {
        companion object {
            const val typeCode = 0xA001
        }

        override val type get() = typeCode
        override val bodyLength get() = (Int.SIZE_BYTES * 2 + msg.size).toLong()

        var code = 0
        var msg = ByteArray(0)
        val msgString: String
            get() = String(msg, StandardCharsets.UTF_8)
        val data: ByteArray get() = msg

        val success get() = code == 0

        override fun decodeBody(data: ByteBuffer) {
            if (data.remaining() < Int.SIZE_BYTES * 2) {
                throw Exception("length ${data.remaining()} is too few for body")
            }

            code = data.getInt()
            val msgLen = data.getInt()

            if (data.remaining() < msgLen) {
                throw Exception("length ${data.remaining()} is less than msg-len $msgLen")
            }

            msg = ByteArray(msgLen)
            data.get(msg)
        }


        override fun encodeBody(container: ByteBuffer) {
            container.putInt(code)
            container.putInt(msg.size)
            container.put(msg)
        }


        override fun reset() {
            msg = byteArrayOf()
        }
    }


    class Hello : JanusMessage() {
        companion object {
            const val typeCode = 0x1000
        }

        override val type get() = typeCode
        override val bodyLength get() = protocolVersions.size.toLong() * Long.SIZE_BYTES

        val protocolVersions = mutableListOf<Long>()

        override fun decodeBody(data: ByteBuffer) {
            protocolVersions.clear()
            while (data.remaining() >= Long.SIZE_BYTES) {
                protocolVersions.add(data.getLong())
            }
        }

        override fun encodeBody(container: ByteBuffer) {
            protocolVersions.forEach { container.putLong(it) }
        }

        override fun reset() {
            this.protocolVersions.clear()
        }
    }


    class Auth : JanusMessage() {
        companion object {
            const val typeCode = 0x1001
        }

        override val type get() = typeCode
        override val bodyLength get() = challenge.size.toLong()

        var challenge = ByteArray(0)

        override fun decodeBody(data: ByteBuffer) {
            challenge = ByteArray(data.remaining())
            data.get(challenge)
        }

        override fun encodeBody(container: ByteBuffer) {
            container.put(challenge)
        }

        override fun reset() {
            challenge = byteArrayOf()
        }
    }


    class GetSystemTimeMillis : JanusMessage() {
        companion object {
            const val typeCode = 0x1801
        }

        override val type get() = typeCode
        override val bodyLength get() = 0L
    }


    class FetchFileTree : JanusMessage() {
        companion object {
            const val typeCode = 0x2001
        }

        override val type get() = typeCode
        override val bodyLength get() = 0L
    }

}


private var initialized = false
private fun registerMsgType(msgType: Int, creator: () -> JanusMessage) {
    if (JanusMessage.registry[msgType] != null) {
        Logger.error("CRITICAL!!! Duplicated message type: 0x${msgType.toHexString()}.", trace = Throwable())
        exitProcess(-1)  // This should be something like static assert.
    }

    JanusMessage.registry[msgType] = MessageObjectPool(creator)
}

private fun registerMsgTypes() {
    // Add a line for each message struct.
    registerMsgType(JanusMessage.CommonResponse.typeCode) { JanusMessage.CommonResponse() }
    registerMsgType(JanusMessage.Hello.typeCode) { JanusMessage.Hello() }
    registerMsgType(JanusMessage.Auth.typeCode) { JanusMessage.Auth() }
    registerMsgType(JanusMessage.GetSystemTimeMillis.typeCode) { JanusMessage.GetSystemTimeMillis() }
    registerMsgType(JanusMessage.FetchFileTree.typeCode) { JanusMessage.FetchFileTree() }
}

private fun checkAllMsgTypesRegistered() {
    val msgClasses = JanusMessage::class.sealedSubclasses
    for (kClass in msgClasses) {
        val companion = kClass.companionObject
        val companionInstance = kClass.companionObjectInstance

        if (companion == null || companionInstance == null) {
            Logger.error("CRITICAL!!! Failed to get companion object instance for $kClass", trace = Throwable())
            exitProcess(-1)
        }

        val typeCodeProp = companion.declaredMemberProperties.find { it.name == JanusMessage::typeCode.name } ?: run {
            Logger.error("CRITICAL!!! Failed to get typeCode property for $kClass", trace = Throwable())
            exitProcess(-1)
        }

        val typeCode = typeCodeProp.getter.call(companionInstance) as? Int ?: run {
            Logger.error("CRITICAL!!! Failed to get typeCode property for $kClass", trace = Throwable())
            exitProcess(-1)
        }

        JanusMessage.registry[typeCode] ?: run {
            Logger.error("CRITICAL!!! $kClass not registered.", trace = Throwable())
            exitProcess(-1)
        }
    }
}

private fun init() {
    if (initialized)
        return

    registerMsgTypes()
    checkAllMsgTypesRegistered()

    initialized = true
}
