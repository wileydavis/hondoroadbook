package fold.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.close
import platform.posix.fstat
import platform.posix.memcpy
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open
import platform.posix.stat
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

/**
 * The graph file mapped with mmap: the kernel pages in what the router touches and drops it under
 * pressure, so a 700 MB state costs no memory to open. arm64 reads unaligned words natively and the
 * file is little-endian like the chip, so the accessors are pointer reads.
 */
@OptIn(ExperimentalForeignApi::class)
class MmapBytes private constructor(private val base: CPointer<ByteVar>, override val size: Long) : Bytes {
    companion object {
        fun map(path: String): MmapBytes {
            val fd = open(path, O_RDONLY)
            require(fd >= 0) { "cannot open $path" }
            try {
                val size = memScoped { val st = alloc<stat>(); require(fstat(fd, st.ptr) == 0) { "cannot stat $path" }; st.st_size }
                require(size > 0) { "empty file $path" }
                val p = mmap(null, size.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)
                require(p != null && p.rawValue.toLong() != -1L) { "cannot map $path" }
                return MmapBytes(p!!.reinterpret(), size)
            } finally { close(fd) }
        }
    }
    override fun u8(off: Long): Int = (base + off)!!.pointed.value.toInt() and 0xFF
    override fun u16(off: Long): Int = (base + off)!!.reinterpret<ShortVar>().pointed.value.toInt() and 0xFFFF
    override fun i32(off: Long): Int = (base + off)!!.reinterpret<IntVar>().pointed.value
    override fun i64(off: Long): Long = (base + off)!!.reinterpret<LongVar>().pointed.value
    override fun get(off: Long, dst: ByteArray) {
        require(off >= 0 && off + dst.size <= size) { "read past end of file (${off + dst.size} > $size)" }
        if (dst.isEmpty()) return
        dst.usePinned { memcpy(it.addressOf(0), base + off, dst.size.toULong()) }
    }
    /** The mapping lives as long as the process needs the pack; call when a pack is replaced. */
    fun release() { munmap(base, size.toULong()) }
}

actual fun mapFile(path: String): Bytes = MmapBytes.map(path)

@OptIn(ExperimentalForeignApi::class)
actual fun decomposeNfd(s: String): String = NSString.create(string = s).decomposedStringWithCanonicalMapping
