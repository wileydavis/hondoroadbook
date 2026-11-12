package fold.core

import kotlin.math.abs

/**
 * A QR code encoder, so both apps draw the same codes and the moving share code needs no library on either phone.
 * A port of Project Nayuki's QR Code generator (MIT licence), cut to what the share code needs: one alphanumeric
 * segment (uppercase, digits, " $%*+-./:"), error correction L or M, the version chosen to fit, the mask chosen by
 * the standard's penalty score.
 *
 * [modules] is the symbol, row by row, true for dark, without the quiet zone (draw four modules of light around it).
 */
class QrCode private constructor(val version: Int, val size: Int, private val m: Array<BooleanArray>) {
    /** is the module at column [x], row [y] dark? */
    fun dark(x: Int, y: Int): Boolean = m[y][x]

    enum class Ecc(val formatBits: Int, val row: Int) { L(1, 0), M(0, 1) }

    companion object {
        const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

        /** How many alphanumeric characters fit in one symbol of this version and level. */
        fun capacityChars(version: Int, ecc: Ecc): Int {
            val bits = dataCodewords(version, ecc) * 8 - 4 - countBits(version)
            return bits / 11 * 2 + (if (bits % 11 >= 6) 1 else 0)
        }

        /** The smallest symbol (from [minVersion]) that holds [text]; null if not even version 40 does. */
        fun encodeAlphanumeric(text: String, ecc: Ecc = Ecc.M, minVersion: Int = 1): QrCode? {
            for (ch in text) require(ALPHANUMERIC.indexOf(ch) >= 0) { "not alphanumeric: $ch" }
            var ver = minVersion
            while (ver <= 40 && capacityChars(ver, ecc) < text.length) ver++
            if (ver > 40) return null
            // the segment: mode 0010, the count, pairs in 11 bits, a last single in 6
            val bb = Bits()
            bb.put(0x2, 4); bb.put(text.length, countBits(ver))
            var i = 0
            while (i + 1 < text.length) { bb.put(ALPHANUMERIC.indexOf(text[i]) * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11); i += 2 }
            if (i < text.length) bb.put(ALPHANUMERIC.indexOf(text[i]), 6)
            val cap = dataCodewords(ver, ecc) * 8
            bb.put(0, minOf(4, cap - bb.n))
            bb.put(0, (8 - bb.n % 8) % 8)
            var pad = 0xEC
            while (bb.n < cap) { bb.put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val data = ByteArray(bb.n / 8) { k -> var v = 0; for (j in 0 until 8) v = (v shl 1) or (if (bb.get(k * 8 + j)) 1 else 0); v.toByte() }
            return build(ver, ecc, data)
        }

        private fun countBits(ver: Int) = when { ver <= 9 -> 9; ver <= 26 -> 11; else -> 13 }

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28))
        private val NUM_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49))

        private fun rawModules(ver: Int): Int {
            var r = (16 * ver + 128) * ver + 64
            if (ver >= 2) { val na = ver / 7 + 2; r -= (25 * na - 10) * na - 55; if (ver >= 7) r -= 36 }
            return r
        }
        private fun dataCodewords(ver: Int, ecc: Ecc) = rawModules(ver) / 8 - ECC_PER_BLOCK[ecc.row][ver] * NUM_BLOCKS[ecc.row][ver]

        private fun build(ver: Int, ecc: Ecc, data: ByteArray): QrCode {
            val size = ver * 4 + 17
            val mod = Array(size) { BooleanArray(size) }
            val fn = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, d: Boolean) { mod[y][x] = d; fn[y][x] = true }
            // timing, finders, alignment, format (a placeholder), version
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4))
                for (dy in -4..4) for (dx in -4..4) { val x = cx + dx; val y = cy + dy; val d = maxOf(abs(dx), abs(dy)); if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4) }
            val align = alignmentPositions(ver, size); val na = align.size
            for (i in 0 until na) for (j in 0 until na) {
                if ((i == 0 && j == 0) || (i == 0 && j == na - 1) || (i == na - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            fun formatBits(mask: Int) {
                val d = ecc.formatBits shl 3 or mask
                var rem = d; repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
                val bits = (d shl 10 or rem) xor 0x5412
                fun bit(i: Int) = (bits ushr i) and 1 != 0
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
                for (i in 9 until 15) set(14 - i, 8, bit(i))
                for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
                for (i in 8 until 15) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true)
            }
            formatBits(0)
            if (ver >= 7) {
                var rem = ver; repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = ver shl 12 or rem
                for (i in 0 until 18) { val b = (bits ushr i) and 1 != 0; val a = size - 11 + i % 3; val c = i / 3; set(a, c, b); set(c, a, b) }
            }
            // the codewords with their error correction, interleaved, in the zigzag
            val all = eccAndInterleave(ver, ecc, data)
            var bi = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j; val up = (right + 1) and 2 == 0; val y = if (up) size - 1 - vert else vert
                    if (!fn[y][x] && bi < all.size * 8) { mod[y][x] = (all[bi ushr 3].toInt() ushr (7 - (bi and 7))) and 1 != 0; bi++ }
                }
                right -= 2
            }
            // the mask with the lowest penalty
            fun applyMask(k: Int) {
                for (y in 0 until size) for (x in 0 until size) {
                    val inv = when (k) {
                        0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (inv && !fn[y][x]) mod[y][x] = !mod[y][x]
                }
            }
            var best = 0; var bestP = Int.MAX_VALUE
            for (k in 0 until 8) { applyMask(k); formatBits(k); val p = penalty(mod, size); if (p < bestP) { bestP = p; best = k }; applyMask(k) }
            applyMask(best); formatBits(best)
            return QrCode(ver, size, mod)
        }

        private fun alignmentPositions(ver: Int, size: Int): IntArray {
            if (ver == 1) return IntArray(0)
            val na = ver / 7 + 2
            val step = (ver * 8 + na * 3 + 5) / (na * 4 - 4) * 2
            val r = IntArray(na); r[0] = 6
            var pos = size - 7
            for (i in na - 1 downTo 1) { r[i] = pos; pos -= step }
            return r
        }

        private fun eccAndInterleave(ver: Int, ecc: Ecc, data: ByteArray): ByteArray {
            val nb = NUM_BLOCKS[ecc.row][ver]; val eccLen = ECC_PER_BLOCK[ecc.row][ver]
            val raw = rawModules(ver) / 8
            val nShort = nb - raw % nb; val shortLen = raw / nb
            val div = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until nb) {
                val len = shortLen - eccLen + (if (i < nShort) 0 else 1)
                val dat = data.copyOfRange(k, k + len); k += len
                val block = ByteArray(shortLen + 1)
                dat.copyInto(block)
                rsRemainder(dat, div).copyInto(block, shortLen + 1 - eccLen)
                blocks.add(block)
            }
            val out = ByteArray(raw); var o = 0
            for (i in 0 until blocks[0].size) for (j in blocks.indices) if (i != shortLen - eccLen || j >= nShort) out[o++] = blocks[j][i]
            return out
        }
        private fun gfMul(x: Int, y: Int): Int { var z = 0; for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }; return z and 0xFF }
        private fun rsDivisor(degree: Int): IntArray {
            val r = IntArray(degree); r[degree - 1] = 1; var root = 1
            for (i in 0 until degree) { for (j in 0 until degree) { r[j] = gfMul(r[j], root); if (j + 1 < degree) r[j] = r[j] xor r[j + 1] }; root = gfMul(root, 2) }
            return r
        }
        private fun rsRemainder(data: ByteArray, div: IntArray): ByteArray {
            val r = IntArray(div.size)
            for (b in data) { val f = (b.toInt() and 0xFF) xor r[0]; for (i in 0 until r.size - 1) r[i] = r[i + 1]; r[r.size - 1] = 0; for (i in r.indices) r[i] = r[i] xor gfMul(div[i], f) }
            return ByteArray(r.size) { r[it].toByte() }
        }

        private fun penalty(m: Array<BooleanArray>, size: Int): Int {
            var p = 0
            fun addHistory(run0: Int, h: IntArray) { var run = run0; if (h[0] == 0) run += size; for (i in h.size - 1 downTo 1) h[i] = h[i - 1]; h[0] = run }
            fun count(h: IntArray): Int { val n = h[1]; val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
                return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0) }
            fun terminate(color: Boolean, run0: Int, h: IntArray): Int { var run = run0; if (color) { addHistory(run, h); run = 0 }; run += size; addHistory(run, h); return count(h) }
            for (pass in 0..1) for (a in 0 until size) {
                var color = false; var run = 0; val h = IntArray(7)
                for (b in 0 until size) {
                    val c = if (pass == 0) m[a][b] else m[b][a]
                    if (c == color) { run++; if (run == 5) p += 3 else if (run > 5) p++ }
                    else { addHistory(run, h); if (!color) p += count(h) * 40; color = c; run = 1 }
                }
                p += terminate(color, run, h) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) { val c = m[y][x]; if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3 }
            var darkN = 0; for (row in m) for (c in row) if (c) darkN++
            val total = size * size
            p += ((abs(darkN * 20 - total * 10) + total - 1) / total - 1) * 10
            return p
        }
    }

    private class Bits { private val a = ArrayList<Boolean>(); val n get() = a.size
        fun put(v: Int, len: Int) { for (i in len - 1 downTo 0) a.add((v ushr i) and 1 != 0) }
        fun get(i: Int) = a[i] }
}
