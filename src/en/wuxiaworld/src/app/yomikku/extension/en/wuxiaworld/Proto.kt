package app.yomikku.extension.en.wuxiaworld

import java.io.ByteArrayOutputStream

/**
 * Just enough of the protobuf wire format for Wuxiaworld's gRPC-web API: a message is read into its fields by number,
 * and a request is written from nested fields.
 */
internal class ProtoMessage(bytes: ByteArray) {

    private val fields = mutableMapOf<Int, MutableList<Any>>()

    init {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < bytes.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> (0 until 8).fold(0L) { acc, i -> acc or ((bytes[pos + i].toLong() and 0xff) shl (8 * i)) }.also { pos += 8 }
                2 -> {
                    val length = varint().toInt()
                    bytes.copyOfRange(pos, pos + length).also { pos += length }
                }
                5 -> (0 until 4).fold(0L) { acc, i -> acc or ((bytes[pos + i].toLong() and 0xff) shl (8 * i)) }.also { pos += 4 }
                else -> throw IllegalStateException("Unsupported wire type in field $number")
            }
            fields.getOrPut(number) { mutableListOf() }.add(value)
        }
    }

    fun long(number: Int): Long? = fields[number]?.firstOrNull() as? Long

    fun int(number: Int): Int? = long(number)?.toInt()

    fun bool(number: Int): Boolean? = long(number)?.let { it != 0L }

    fun string(number: Int): String? = (fields[number]?.firstOrNull() as? ByteArray)?.decodeToString()

    fun strings(number: Int): List<String> = fields[number].orEmpty().filterIsInstance<ByteArray>().map { it.decodeToString() }

    fun message(number: Int): ProtoMessage? = (fields[number]?.firstOrNull() as? ByteArray)?.let(::ProtoMessage)

    fun messages(number: Int): List<ProtoMessage> = fields[number].orEmpty().filterIsInstance<ByteArray>().map(::ProtoMessage)

    /** A google.protobuf.StringValue in field [number]. */
    fun wrappedString(number: Int): String? = message(number)?.string(1)

    /** Wuxiaworld's DecimalValue: whole units plus nanos. */
    fun decimal(number: Int): Double? = message(number)?.let { (it.long(1) ?: 0L) + (it.int(2) ?: 0) / 1e9 }

    companion object {
        /** The first data frame of a gRPC-web response body. */
        fun fromGrpcWeb(body: ByteArray): ProtoMessage {
            var pos = 0
            while (pos + 5 <= body.size) {
                val flag = body[pos].toInt()
                val length = ((body[pos + 1].toInt() and 0xff) shl 24) or ((body[pos + 2].toInt() and 0xff) shl 16) or
                    ((body[pos + 3].toInt() and 0xff) shl 8) or (body[pos + 4].toInt() and 0xff)
                if (flag == 0) return ProtoMessage(body.copyOfRange(pos + 5, pos + 5 + length))
                pos += 5 + length
            }
            throw IllegalStateException("Empty response")
        }
    }
}

/** A protobuf message to send, built from fields. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun int(number: Int, value: Long) = apply {
        varint((number shl 3).toLong())
        varint(value)
    }

    fun bytes(number: Int, value: ByteArray) = apply {
        varint(((number shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(number: Int, value: String) = bytes(number, value.encodeToByteArray())

    fun message(number: Int, build: ProtoWriter.() -> Unit) = bytes(number, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    /** This message in a gRPC-web data frame. */
    fun toGrpcWeb(): ByteArray {
        val message = toByteArray()
        val size = message.size
        return byteArrayOf(0, (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + message
    }
}
