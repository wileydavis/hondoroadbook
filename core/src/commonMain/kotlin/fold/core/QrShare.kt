package fold.core

/**
 * Sharing a route phone to phone, offline, by a moving QR code: one phone shows a code that changes several times a
 * second, the other films it. Nothing goes over a network, there is no pairing, and Android and iPhone read each
 * other's codes.
 *
 * What travels is everything the apps read from a GPX ([Gpx.Track]): the name, the line, the planned route's points
 * with their names and the routing mode, every cue with its words and roll-chart fields, and the chart's settings.
 * The receiving phone writes it back out as a GPX ([Gpx.writeShared]), so it opens like any other file.
 *
 * The bytes: the track packed tightly ([pack]: coordinates to a millionth of a degree as varint deltas), then
 * compressed by the platform's raw DEFLATE (Android's Deflater, iOS's Compression framework, both RFC 1951), then cut
 * into chunks. Frame i < K carries chunk i; every later frame carries the XOR of a random half of the chunks, picked
 * by a generator both phones run the same way. Any K or so frames rebuild the message, whichever were missed, so a
 * glare, a shake or a late start costs a moment, never a restart. Each frame is text: "RB1:" and base45 (RFC 9285),
 * which QR's alphanumeric mode stores at 5.5 bits a character.
 */
object QrShare {
    const val PREFIX = "RB1:"
    private const val HEAD = 13          // version 1, id 4, length 3, chunk size 2, frame index 3

    // ---- the track, packed ------------------------------------------------------------------------------------------

    /** A long recording thinned to within a metre of its line (the length changes by hundredths of a percent). */
    fun thin(t: Gpx.Track, above: Int = 6000): Gpx.Track {
        if (t.lats.size <= above) return t
        val keep = Simplify.douglasPeucker(t.lats, t.lons, 1.0)
        return Gpx.Track(t.name, DoubleArray(keep.size) { t.lats[keep[it]] }, DoubleArray(keep.size) { t.lons[keep[it]] }, t.rteLats, t.rteLons, t.mode, t.cues, t.rteNames).also { it.chart = t.chart }
    }

    fun pack(t: Gpx.Track): ByteArray {
        val w = Out()
        w.byte(1)
        w.str(t.name); w.str(t.mode ?: ""); w.str(t.chart ?: "")
        w.coords(t.lats, t.lons)
        w.coords(t.rteLats, t.rteLons)
        w.uint(t.rteNames.size); for (n in t.rteNames) w.str(n)
        w.uint(t.cues.size)
        var pa = 0L; var po = 0L
        for (c in t.cues) {
            val a = e6(c.lat); val o = e6(c.lon); w.sint(a - pa); w.sint(o - po); pa = a; po = o
            w.str(c.turn.name); w.uint(c.level); w.str(c.note); w.str(c.rally ?: "")
        }
        return w.bytes()
    }

    fun unpack(b: ByteArray): Gpx.Track {
        val r = In(b)
        require(r.byte() == 1) { "a newer share code: update Roadbook" }
        val name = r.str(); val mode = r.str().ifEmpty { null }; val chart = r.str().ifEmpty { null }
        val (la, lo) = r.coords(); val (ra, ro) = r.coords()
        val names = List(r.uint()) { r.str() }
        var pa = 0L; var po = 0L
        val cues = List(r.uint()) {
            pa += r.sint(); po += r.sint()
            val turn = try { Turn.valueOf(r.str()) } catch (_: Throwable) { Turn.NOTE }
            val level = r.uint(); val note = r.str(); val rally = Cue.cleanRally(r.str().ifEmpty { null })
            Cue(pa / 1e6, po / 1e6, turn, note, level).also { it.rally = rally }
        }
        return Gpx.Track(name.ifEmpty { "Shared route" }, la, lo, ra, ro, mode, cues, names).also { it.chart = Cue.cleanRally(chart) }
    }

    /** The message for a track: packed, then compressed by the platform's raw DEFLATE when that makes it smaller. */
    fun message(t: Gpx.Track, deflate: (ByteArray) -> ByteArray?): ByteArray {
        val p = pack(thin(t))
        val z = try { deflate(p) } catch (_: Throwable) { null }
        return if (z != null && z.size < p.size) byteArrayOf(1) + z else byteArrayOf(0) + p
    }
    /** A received message back to a track (inflate is the platform's raw INFLATE). */
    @Throws(Throwable::class) fun track(m: ByteArray, inflate: (ByteArray) -> ByteArray?): Gpx.Track {
        require(m.isNotEmpty())
        val body = m.copyOfRange(1, m.size)
        return unpack(if (m[0].toInt() == 1) inflate(body) ?: error("could not unpack the share") else body)
    }

    private fun e6(v: Double) = kotlin.math.round(v * 1e6).toLong()

    // ---- frames -----------------------------------------------------------------------------------------------------

    /** How many bytes of chunk fit one frame drawn at [version] (level M): the share screen's codes stay at that size. */
    fun chunkFor(version: Int = 20): Int = (QrCode.capacityChars(version, QrCode.Ecc.M) - PREFIX.length) / 3 * 2 - HEAD

    /** The frames of one message: [frame] gives frame i as text, for i = 0, 1, 2… without end. */
    class Encoder(val message: ByteArray, chunk: Int = chunkFor()) {
        val chunk = chunk.coerceIn(16, 65535)
        val k = maxOf(1, (message.size + this.chunk - 1) / this.chunk)
        val id = crc32(message)
        private val chunks = Array(k) { i -> ByteArray(this.chunk).also { message.copyInto(it, 0, i * this.chunk, minOf(message.size, (i + 1) * this.chunk)) } }
        init { require(message.size < 1 shl 24) { "too large to share by code" } }
        fun frame(i: Int): String {
            val body = ByteArray(chunk)
            if (i < k) chunks[i].copyInto(body)
            else { val mask = mixMask(id, i, k); for (j in 0 until k) if (mask[j]) for (x in 0 until chunk) body[x] = (body[x].toInt() xor chunks[j][x].toInt()).toByte() }
            val h = ByteArray(HEAD)
            h[0] = 1; put(h, 1, id.toLong(), 4); put(h, 5, message.size.toLong(), 3); put(h, 8, chunk.toLong(), 2); put(h, 10, i.toLong(), 3)
            return PREFIX + Base45.encode(h + body)
        }
        /** the frames to show, as codes: a minute's worth is far more than any message needs */
        fun code(i: Int): QrCode = QrCode.encodeAlphanumeric(frame(i), QrCode.Ecc.M)!!
    }

    /** Collects frames from the camera, in any order, repeats and strangers ignored, until the message is whole. */
    class Decoder {
        var id = -1; private set
        var k = 0; private set
        var size = 0; private set
        private var chunk = 0
        // rows of a GF(2) system kept in echelon form: pivot column -> (coefficients, data)
        private val rows = HashMap<Int, Pair<BooleanArray, ByteArray>>()
        private val seen = HashSet<Int>()
        /** chunks known so far (0..k): the progress bar */
        val known get() = rows.size
        var message: ByteArray? = null; private set

        /** Offer one scanned code's text. True when it was a frame of this message that taught something new. */
        fun add(text: String): Boolean {
            if (message != null || !text.startsWith(PREFIX)) return false
            val b = try { Base45.decode(text.substring(PREFIX.length)) } catch (_: Throwable) { return false }
            if (b.size <= HEAD || b[0].toInt() != 1) return false
            val fid = get(b, 1, 4).toInt(); val fsize = get(b, 5, 3).toInt(); val fchunk = get(b, 8, 2).toInt(); val i = get(b, 10, 3).toInt()
            if (fchunk <= 0 || b.size != HEAD + fchunk) return false
            if (id != fid) { // a new message (or the first): start over
                id = fid; size = fsize; chunk = fchunk; k = maxOf(1, (fsize + fchunk - 1) / fchunk); rows.clear(); seen.clear()
            }
            if (!seen.add(i)) return false
            val coef = if (i < k) BooleanArray(k).also { it[i] = true } else mixMask(id, i, k)
            val data = b.copyOfRange(HEAD, b.size)
            // reduce against the known rows, then take the first free column as this row's pivot
            for (j in 0 until k) if (coef[j]) rows[j]?.let { (c, d) -> for (x in 0 until k) if (c[x]) coef[x] = !coef[x]; for (x in 0 until chunk) data[x] = (data[x].toInt() xor d[x].toInt()).toByte() }
            val p = (0 until k).firstOrNull { coef[it] } ?: return false
            // keep the system reduced: clear column p from the other rows
            for ((_, row) in rows) if (row.first[p]) { for (x in 0 until k) if (coef[x]) row.first[x] = !row.first[x]; for (x in 0 until chunk) row.second[x] = (row.second[x].toInt() xor data[x].toInt()).toByte() }
            rows[p] = coef to data
            if (rows.size == k) {
                // reduced: every row is now one chunk (later pivots were cleared from earlier rows; finish the back-substitution)
                for (col in k - 1 downTo 0) { val (c, d) = rows[col]!!; for ((r, row) in rows) if (r != col && row.first[col]) { for (x in 0 until k) if (c[x]) row.first[x] = !row.first[x]; for (x in 0 until chunk) row.second[x] = (row.second[x].toInt() xor d[x].toInt()).toByte() } }
                val out = ByteArray(size)
                for (j in 0 until k) { val d = rows[j]!!.second; d.copyInto(out, j * chunk, 0, minOf(chunk, size - j * chunk)) }
                if (crc32(out) == id) message = out else { rows.clear(); seen.clear() }
            }
            return true
        }
    }

    /** which chunks frame [i] mixes: a fair coin per chunk from a generator seeded by the message and the frame, never none */
    private fun mixMask(id: Int, i: Int, k: Int): BooleanArray {
        // splitmix64 from (message, frame): 64 fair coins a step
        var x = (id.toLong() shl 32) xor i.toLong()
        val m = BooleanArray(k)
        var any = false
        var j = 0
        while (j < k) {
            x += -0x61c8864680b583ebL
            var z = x; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; z = z xor (z ushr 31)
            for (b in 0 until 64) { if (j >= k) break; m[j] = (z ushr b) and 1L != 0L; any = any || m[j]; j++ }
        }
        if (!any) m[i % k] = true
        return m
    }

    private fun put(b: ByteArray, at: Int, v: Long, n: Int) { for (j in 0 until n) b[at + j] = (v ushr (8 * (n - 1 - j))).toByte() }
    private fun get(b: ByteArray, at: Int, n: Int): Long { var v = 0L; for (j in 0 until n) v = (v shl 8) or (b[at + j].toLong() and 0xFF); return v }

    fun crc32(b: ByteArray): Int {
        var c = -1
        for (x in b) { c = c xor (x.toInt() and 0xFF); repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 } }
        return c.inv()
    }

    // ---- varints --------------------------------------------------------------------------------------------------
    private class Out {
        private var a = ByteArray(1024); private var n = 0
        fun byte(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v.toByte() }
        fun uint(v0: Long) { var v = v0; while (v >= 0x80) { byte(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; byte(v.toInt()) }
        fun uint(v: Int) = uint(v.toLong())
        fun sint(v: Long) = uint((v shl 1) xor (v shr 63))
        fun str(s: String) { val u = s.encodeToByteArray(); uint(u.size); for (x in u) byte(x.toInt()) }
        fun coords(la: DoubleArray, lo: DoubleArray) { uint(la.size); var pa = 0L; var po = 0L; for (i in la.indices) { val a = e6(la[i]); val o = e6(lo[i]); sint(a - pa); sint(o - po); pa = a; po = o } }
        fun bytes() = a.copyOf(n)
    }
    private class In(val a: ByteArray) {
        private var i = 0
        fun byte(): Int = a[i++].toInt() and 0xFF
        fun ulong(): Long { var v = 0L; var s = 0; while (true) { val b = byte(); v = v or ((b and 0x7F).toLong() shl s); if (b < 0x80) return v; s += 7; require(s < 64) } }
        fun uint(): Int = ulong().toInt().also { require(it >= 0 && it <= a.size * 8 + 16) { "corrupt share" } }
        fun sint(): Long { val v = ulong(); return (v ushr 1) xor -(v and 1) }
        fun str(): String { val n = uint(); require(i + n <= a.size); return a.decodeToString(i, i + n).also { i += n } }
        fun coords(): Pair<DoubleArray, DoubleArray> { val n = uint(); val la = DoubleArray(n); val lo = DoubleArray(n); var pa = 0L; var po = 0L
            for (k in 0 until n) { pa += sint(); po += sint(); la[k] = pa / 1e6; lo[k] = po / 1e6 }; return la to lo }
    }
}

/** RFC 9285: two bytes as three characters of QR's alphanumeric set. */
object Base45 {
    private const val A = QrCode.ALPHANUMERIC
    fun encode(b: ByteArray): String {
        val sb = StringBuilder(b.size * 3 / 2 + 2)
        var i = 0
        while (i + 1 < b.size) { val n = (b[i].toInt() and 0xFF) * 256 + (b[i + 1].toInt() and 0xFF); sb.append(A[n % 45]).append(A[n / 45 % 45]).append(A[n / 2025]); i += 2 }
        if (i < b.size) { val n = b[i].toInt() and 0xFF; sb.append(A[n % 45]).append(A[n / 45]) }
        return sb.toString()
    }
    fun decode(s: String): ByteArray {
        val out = ByteArray(s.length / 3 * 2 + (if (s.length % 3 == 2) 1 else 0)); var o = 0
        require(s.length % 3 != 1)
        fun v(c: Char) = A.indexOf(c).also { require(it >= 0) }
        var i = 0
        while (i + 2 < s.length) { val n = v(s[i]) + v(s[i + 1]) * 45 + v(s[i + 2]) * 2025; require(n <= 0xFFFF); out[o++] = (n ushr 8).toByte(); out[o++] = n.toByte(); i += 3 }
        if (i < s.length) { val n = v(s[i]) + v(s[i + 1]) * 45; require(n <= 0xFF); out[o++] = n.toByte() }
        return out
    }
}
