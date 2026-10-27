package fold.core

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Chunked read-only memory maps, so files above 2 GB work on the JVM and Android. */
class MappedBytes private constructor(private val chunks: Array<ByteBuffer>, override val size: Long) : Bytes {
    companion object {
        const val CHUNK_BITS = 30
        const val CHUNK = 1L shl CHUNK_BITS
        fun map(file: File): MappedBytes {
            FileChannel.open(file.toPath()).use { ch ->
                val size = ch.size()
                val n = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
                val chunks = Array(n) { i ->
                    val off = i * CHUNK; val len = minOf(CHUNK, size - off)
                    ch.map(FileChannel.MapMode.READ_ONLY, off, len).order(ByteOrder.LITTLE_ENDIAN)
                }
                return MappedBytes(chunks, size)
            }
        }
    }
    override fun i32(off: Long): Int = chunks[(off ushr CHUNK_BITS).toInt()].getInt((off and (CHUNK - 1)).toInt())
    override fun u16(off: Long): Int = chunks[(off ushr CHUNK_BITS).toInt()].getShort((off and (CHUNK - 1)).toInt()).toInt() and 0xFFFF
    override fun u8(off: Long): Int = chunks[(off ushr CHUNK_BITS).toInt()].get((off and (CHUNK - 1)).toInt()).toInt() and 0xFF
    override fun i64(off: Long): Long = chunks[(off ushr CHUNK_BITS).toInt()].getLong((off and (CHUNK - 1)).toInt())
    override fun get(off: Long, dst: ByteArray) {
        require(off >= 0 && off + dst.size <= size) { "read past end of file (${off + dst.size} > $size)" }
        var o = off; var i = 0
        while (i < dst.size) {
            val c = chunks[(o ushr CHUNK_BITS).toInt()]; val within = (o and (CHUNK - 1)).toInt()
            val n = minOf(dst.size - i, c.limit() - within)
            require(n > 0) { "read past end of file" }
            synchronized(c) { c.position(within); c.get(dst, i, n) }
            i += n; o += n
        }
    }
}

actual fun mapFile(path: String): Bytes = MappedBytes.map(File(path))

actual fun decomposeNfd(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)

/** A little-endian stream sink, for the packer. */
class StreamSink(out: OutputStream) : Sink {
    private val d = DataOutputStream(out.buffered(1 shl 20))
    override fun i32(v: Int) { d.writeInt(Integer.reverseBytes(v)) }
    override fun i64(v: Long) { d.writeLong(java.lang.Long.reverseBytes(v)) }
    override fun u16(v: Int) { d.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt()) }
    override fun u8(v: Int) { d.write(v) }
    override fun bytes(b: ByteArray) { d.write(b) }
    override fun flush() { d.flush() }
}
fun GraphWriter.write(out: OutputStream) = write(StreamSink(out))
fun Graph.Companion.open(file: File): Graph = Graph.open(file.path)
fun SearchFile.Companion.open(file: File): SearchFile = SearchFile.open(file.path)
