package fold.packer

/** Open-addressing long->int hash map. Missing = -1. Not thread-safe. */
class LongIntMap(initialCapacity: Int = 1 shl 16) {
    private var keys = LongArray(cap(initialCapacity))
    private var vals = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0; private set

    private fun cap(n: Int): Int { var c = 16; while (c < n) c = c shl 1; return c }
    private fun idx(k: Long): Int { var h = k * -7046029254386353131L; h = h xor (h ushr 32); return (h.toInt()) and (keys.size - 1) }

    fun get(k: Long): Int {
        var i = idx(k)
        while (used[i]) { if (keys[i] == k) return vals[i]; i = (i + 1) and (keys.size - 1) }
        return -1
    }
    fun put(k: Long, v: Int) {
        if ((size + 1) * 10 > keys.size * 6) grow()
        var i = idx(k)
        while (used[i]) { if (keys[i] == k) { vals[i] = v; return }; i = (i + 1) and (keys.size - 1) }
        used[i] = true; keys[i] = k; vals[i] = v; size++
    }
    fun increment(k: Long) { val v = get(k); put(k, if (v < 0) 1 else v + 1) }
    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = IntArray(keys.size); used = BooleanArray(keys.size); size = 0
        for (i in ok.indices) if (ou[i]) put(ok[i], ov[i])
    }
}
