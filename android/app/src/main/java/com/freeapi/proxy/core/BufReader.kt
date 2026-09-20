package com.freeapi.proxy.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 自带缓冲的读取器。
 *
 * 刻意不用 BufferedInputStream：CONNECT 隧道在解析完请求头之后，需要把
 * "已经被预读进缓冲区"的那几个字节原样倒给上游，否则 TLS 握手的前几个字节会丢。
 * 只有自己掌控读位置才能做到这一点（见 [drainBuffered]）。
 *
 * 另外 HTTP 头一律按 ISO-8859-1 逐字节映射成 Char 解析，不做 UTF-8 解码——
 * 头字段按 RFC 7230 是 ASCII/obs-text，用 UTF-8 解会在遇到高位字节时抛异常。
 */
class BufReader(private val input: InputStream, bufSize: Int = 16 * 1024) {

    private val buf = ByteArray(bufSize)
    private var pos = 0
    private var limit = 0

    private fun fill(): Boolean {
        if (pos < limit) return true
        pos = 0
        limit = 0
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                throw e
            }
            if (n < 0) return false
            if (n == 0) continue
            limit = n
            return true
        }
    }

    fun readByte(): Int {
        if (!fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    /** 读一行，以 LF 结束（自动去掉尾部 CR）。EOF 且无内容返回 null。 */
    @Throws(IOException::class)
    fun readLine(maxLen: Int = 32 * 1024): String? {
        val sb = StringBuilder(80)
        var any = false
        while (true) {
            val b = readByte()
            if (b < 0) return if (any) sb.toString() else null
            any = true
            if (b == LF) {
                if (sb.isNotEmpty() && sb[sb.length - 1].code == CR) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > maxLen) throw IOException("line too long (>$maxLen)")
        }
    }

    /** 读最多 len 字节；EOF 返回 -1。 */
    @Throws(IOException::class)
    fun readSome(dst: ByteArray, off: Int = 0, len: Int = dst.size): Int {
        if (!fill()) return -1
        val n = minOf(len, limit - pos)
        System.arraycopy(buf, pos, dst, off, n)
        pos += n
        return n
    }

    /** 精确读 n 字节到 out，返回实际拷贝字节数（不足说明被截断）。 */
    @Throws(IOException::class)
    fun readInto(out: OutputStream, n: Long, flush: Boolean = false): Long {
        var remain = n
        var total = 0L
        val tmp = ByteArray(32 * 1024)
        while (remain > 0) {
            val want = minOf(remain, tmp.size.toLong()).toInt()
            val got = readSome(tmp, 0, want)
            if (got < 0) break
            out.write(tmp, 0, got)
            if (flush) out.flush()
            remain -= got
            total += got
        }
        return total
    }

    /** 读到 EOF 为止，边读边拷。 */
    @Throws(IOException::class)
    fun readToEnd(out: OutputStream, flush: Boolean = false): Long {
        var total = 0L
        val tmp = ByteArray(32 * 1024)
        while (true) {
            val got = readSome(tmp)
            if (got < 0) break
            out.write(tmp, 0, got)
            if (flush) out.flush()
            total += got
        }
        return total
    }

    /** 读出缓冲区里剩余的所有字节，并清空缓冲（CONNECT 隧道交接时用）。 */
    fun drainBuffered(): ByteArray {
        if (pos >= limit) return EMPTY
        val out = buf.copyOfRange(pos, limit)
        pos = limit
        return out
    }

    companion object {
        const val CR = '\r'.code
        const val LF = '\n'.code
        val EMPTY = ByteArray(0)
    }
}
