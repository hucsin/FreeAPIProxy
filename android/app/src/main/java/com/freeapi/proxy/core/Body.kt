package com.freeapi.proxy.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 请求体来源。
 *
 * [replayable] = true 表示请求体已整体进内存，socket 直连失败后可以重放给 fetch 路径
 * （这正是 node 用 `Buffer.concat(chunks)` 换来的能力）。
 * 大体积请求体走流式，代价是失去降级能力——用 16MB 阈值权衡，见 [BodyReader.MEMORY_LIMIT]。
 */
sealed class BodySource {
    /** null 表示长度未知 → 上游用 chunked 分帧 */
    abstract val contentLength: Long?
    abstract val replayable: Boolean
    /** 把「已解码的载荷字节」写出，返回写入字节数（不含 chunked 分帧开销） */
    abstract fun copyPayloadTo(out: OutputStream): Long
}

/** 无请求体。 */
object EmptyBody : BodySource() {
    override val contentLength: Long? = 0L
    override val replayable: Boolean = true
    override fun copyPayloadTo(out: OutputStream): Long = 0L
}

/** 已进内存的请求体，可重放。 */
class ByteArrayBody(private val bytes: ByteArray) : BodySource() {
    override val contentLength: Long = bytes.size.toLong()
    override val replayable: Boolean = true
    override fun copyPayloadTo(out: OutputStream): Long {
        out.write(bytes)
        return bytes.size.toLong()
    }
}

/** 定长流式请求体（超过内存阈值时使用），不可重放。 */
class FixedStreamBody(private val reader: BufReader, private val length: Long) : BodySource() {
    override val contentLength: Long = length
    override val replayable: Boolean = false
    override fun copyPayloadTo(out: OutputStream): Long {
        val n = reader.readInto(out, length)
        if (n < length) throw IOException("client request body truncated: $n/$length")
        return n
    }
}

/** chunked 请求体流式解码，不可重放。 */
class ChunkedStreamBody(private val reader: BufReader) : BodySource() {
    override val contentLength: Long? = null
    override val replayable: Boolean = false

    override fun copyPayloadTo(out: OutputStream): Long {
        var total = 0L
        val tmp = ByteArray(32 * 1024)
        while (true) {
            val sizeLine = reader.readLine() ?: throw IOException("EOF inside chunk size line")
            val size = parseChunkSize(sizeLine)
            if (size == 0L) {
                skipTrailers(reader)
                break
            }
            var remain = size
            while (remain > 0) {
                val want = minOf(remain, tmp.size.toLong()).toInt()
                val got = reader.readSome(tmp, 0, want)
                if (got < 0) throw IOException("EOF inside chunk data")
                out.write(tmp, 0, got)
                remain -= got
                total += got
            }
            reader.readLine() // 每个 chunk 数据后面的 CRLF
        }
        return total
    }
}

object BodyReader {
    /**
     * 超过 16MB 的请求体不再进内存。LLM 请求体通常 < 1MB，这条线既能保住
     * socket→fetch 降级能力，又不会被大上传打爆堆内存。
     */
    const val MEMORY_LIMIT: Long = 16L * 1024 * 1024

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    @Throws(IOException::class)
    fun read(head: RequestHead, reader: BufReader, clientOut: OutputStream?): BodySource {
        val te = head.header("transfer-encoding").orEmpty().lowercase()
        val chunked = te.isNotEmpty()
        val cl = head.header("content-length")?.trim()?.toLongOrNull() ?: -1L

        if (!chunked && cl <= 0L) return EmptyBody

        // 客户端发了 Expect: 100-continue 时它正等我们放行，先回 100 再读体，
        // 否则会一直干等到超时（node 版没处理这个，属于修正）。
        if (clientOut != null &&
            head.header("expect").orEmpty().contains("100-continue", ignoreCase = true)
        ) {
            clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
        }

        if (chunked) return ChunkedStreamBody(reader)

        if (cl <= MEMORY_LIMIT) {
            val bos = ByteArrayOutputStream(if (cl in 1..(1 shl 20)) cl.toInt() else 0)
            val n = reader.readInto(bos, cl)
            if (n < cl) throw IOException("client request body truncated: $n/$cl")
            return ByteArrayBody(bos.toByteArray())
        }
        return FixedStreamBody(reader, cl)
    }

    /** 该方法/该请求是否名正言顺带 body（用于决定要不要发 Content-Length: 0）。 */
    fun declaresBody(head: RequestHead): Boolean =
        head.hasHeader("content-length") ||
            head.hasHeader("transfer-encoding") ||
            head.method.uppercase() in BODY_METHODS
}

internal fun parseChunkSize(line: String): Long {
    val semi = line.indexOf(';')
    val hex = (if (semi >= 0) line.substring(0, semi) else line).trim()
    if (hex.isEmpty()) throw IOException("empty chunk size")
    return try {
        hex.toLong(16)
    } catch (t: NumberFormatException) {
        throw IOException("bad chunk size: ${hex.take(32)}")
    }
}

internal fun skipTrailers(reader: BufReader) {
    var guard = 0
    while (true) {
        val l = reader.readLine() ?: return
        if (l.isEmpty()) return
        if (++guard > 64) return
    }
}

/**
 * 把写入的字节重新编码成 chunked 分帧。
 * close()/finish() 只补终止块 `0\r\n\r\n`，**不会**关掉底层流——底层是活着的 socket。
 */
class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {

    private var finished = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        out.write(Integer.toHexString(len).toByteArray(Charsets.ISO_8859_1))
        out.write(HttpHead.CRLF_BYTES)
        out.write(b, off, len)
        out.write(HttpHead.CRLF_BYTES)
    }

    override fun flush() = out.flush()

    fun finish() {
        if (finished) return
        finished = true
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    override fun close() = finish()
}
