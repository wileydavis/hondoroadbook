package fold.core

import java.io.File
import java.text.Normalizer

/** One line of the search index. kind: 'P' place, 'S' street, 'A' address. */
class Hit(val kind: Char, val name: String, val context: String, val lat: Double, val lon: Double) {
    val label: String get() = if (context.isEmpty()) name else "$name, $context"
}

object TextNorm {
    private val abbrev = mapOf(
        "street" to "st", "avenue" to "ave", "road" to "rd", "drive" to "dr", "lane" to "ln", "boulevard" to "blvd",
        "court" to "ct", "circle" to "cir", "place" to "pl", "highway" to "hwy", "parkway" to "pkwy", "trail" to "trl",
        "terrace" to "ter", "north" to "n", "south" to "s", "east" to "e", "west" to "w",
        "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw", "saint" to "st", "mount" to "mt",
        "fort" to "ft", "route" to "rte", "county" to "co", "square" to "sq", "heights" to "hts"
    )
    private val strip = Regex("\\p{M}+")
    private val junk = Regex("[^a-z0-9 ]+")
    private val ws = Regex("\\s+")

    fun normalize(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(strip, "")
        val cleaned = d.replace('-', ' ').replace('.', ' ').replace(junk, " ").replace(ws, " ").trim()
        if (cleaned.isEmpty()) return ""
        return cleaned.split(' ').joinToString(" ") { abbrev[it] ?: it }
    }
}

/**
 * Search over a memory-mapped index file sorted by normalized key. Each line is
 * `key \t kind \t name \t context \t latE7 \t lonE7`. The file is never loaded into RAM,
 * so a continent-size index costs nothing until you type.
 *
 * Lookup: binary-search the whole normalized query as a key prefix ("main st manhattan" matches
 * "Main Street, Manhattan" directly); if that is thin, fall back to the first token as prefix and
 * require every token to appear somewhere in the key.
 */
class SearchFile private constructor(private val b: Bytes) {
    private val size = b.size

    private fun lineStart(pos: Long): Long { var p = pos; while (p > 0 && b.u8(p - 1) != NL) p--; return p }
    private fun lineEnd(pos: Long): Long { var p = pos; while (p < size && b.u8(p) != NL) p++; return p }
    private fun keyAt(ls: Long): String {
        var p = ls; val sb = StringBuilder()
        while (p < size) { val c = b.u8(p); if (c == TAB || c == NL) break; sb.append(c.toChar()); p++ }
        return sb.toString()
    }
    private fun lineAt(ls: Long): String {
        val le = lineEnd(ls); val arr = ByteArray((le - ls).toInt()); b.get(ls, arr); return String(arr, Charsets.UTF_8)
    }

    /** First line whose key >= prefix. */
    private fun lowerBound(prefix: String): Long {
        var lo = 0L; var hi = size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val ls = lineStart(mid)
            val k = keyAt(ls)
            if (k < prefix) lo = lineEnd(ls) + 1 else hi = ls
        }
        return lo
    }

    fun query(q: String, limit: Int = 25): List<Hit> = queryScored(q, limit).map { it.second }

    /** Scored results so several packs' results can be merged and ranked together. */
    fun queryScored(q: String, limit: Int = 25): List<Pair<Int, Hit>> {
        val nq0 = TextNorm.normalize(q)
        if (nq0.isEmpty() || size == 0L) return emptyList()
        // a pasted postal address carries things the index never has: ZIP code, state, country. Drop them.
        val raw = nq0.split(' ')
        val keep = ArrayList<String>()
        var i0 = 0
        while (i0 < raw.size) {
            val t = raw[i0]
            val zip = (t.length == 5 || t.length == 9) && t.all(Char::isDigit) && i0 > 0
            if (zip) { if (i0 + 1 < raw.size && raw[i0 + 1].length == 4 && raw[i0 + 1].all(Char::isDigit)) i0++ }   // ZIP+4
            else if (t != "usa" && t != "us") keep.add(t)
            i0++
        }
        var tokens: List<String> = keep
        while (tokens.size > 1 && (tokens.last() in STATES || tokens.last() == "united" || tokens.last() == "states")) tokens = tokens.dropLast(1)
        if (tokens.size >= 2 && tokens.takeLast(2).joinToString(" ") in STATE_NAMES_2) tokens = tokens.dropLast(2)
        if (tokens.isEmpty()) return emptyList()
        val nq = tokens.joinToString(" ")
        val out = ArrayList<Pair<Int, Hit>>()
        val seen = HashSet<String>()
        scan(nq, tokens, 4000, out, seen)
        // "8 N Colville St" when only 4 and 30 are mapped: find the street and the nearest numbers
        if (out.isEmpty() && tokens.size > 1 && tokens[0].all(Char::isDigit)) {
            val want = tokens[0].toLong()
            val rest = tokens.drop(1); val nrest = rest.joinToString(" ")
            scan(nrest, rest, 4000, out, seen)
            // probe the neighbouring house numbers directly: the index is sorted by key, so each is one binary search
            var found = 0
            for (delta in 1..80) { for (cand in longArrayOf(want - delta, want + delta)) {
                if (cand <= 0 || found >= 6) continue
                val before = out.size
                scan("$cand $nrest", listOf(cand.toString()) + rest, 3, out, seen)
                if (out.size > before) found++
            }; if (found >= 6) break }
            for (i in out.indices) {
                val (sc, h) = out[i]
                val num = h.name.takeWhile(Char::isDigit).toLongOrNull()
                out[i] = when {
                    h.kind == 'S' -> (sc + 30) to h
                    h.kind == 'A' && num != null -> (sc + 25 - minOf(25L, Math.abs(num - want) / 2).toInt()) to h
                    else -> out[i]
                }
            }
        }
        if (tokens.size > 1) {
            // thin result: try each token as the prefix (longest first), so "manhattan main" still finds Main St
            var tries = 0
            for (t in tokens.sortedByDescending { it.length }) {
                if (out.size >= 8 || tries >= 3) break
                if (t == nq) continue
                scan(t, tokens, 120_000, out, seen); tries++
            }
        }
        out.sortByDescending { it.first }
        return out.take(limit)
    }

    private fun scan(prefix: String, tokens: List<String>, maxLines: Int, out: ArrayList<Pair<Int, Hit>>, seen: HashSet<String>) {
        var p = lowerBound(prefix)
        var n = 0
        val nq = tokens.joinToString(" ")
        while (p < size && n < maxLines) {
            val line = lineAt(p)
            p += line.toByteArray(Charsets.UTF_8).size + 1
            n++
            val tab = line.indexOf('\t'); if (tab < 0) continue
            val key = line.substring(0, tab)
            if (!key.startsWith(prefix)) break
            if (!seen.add(line.substring(tab + 1))) continue
            var ok = true; var score = 0
            for (t in tokens) {
                val pos = key.indexOf(t)
                if (pos < 0) { ok = false; break }
                if (pos == 0 || key[pos - 1] == ' ') score += 10
                if (pos + t.length == key.length || key[pos + t.length] == ' ') score += 4
            }
            if (!ok) continue
            val parts = line.split('\t'); if (parts.size < 6) continue
            val kind = parts[1].firstOrNull() ?: continue
            val lat = parts[4].toIntOrNull() ?: continue; val lon = parts[5].toIntOrNull() ?: continue
            score += when (kind) { 'P' -> 12; 'S' -> 6; else -> 0 }
            if (key.startsWith(nq)) score += 20
            if (key == nq) score += 40
            // an alias line (East Missoula filed under "missoula") ranks below the real thing
            if (key != TextNorm.normalize(parts[2] + " " + parts[3])) score -= 12
            score -= (key.length - nq.length).coerceAtLeast(0) / 4
            out.add(score to Hit(kind, parts[2], parts[3], lat / 1e7, lon / 1e7))
        }
    }

    companion object {
        private const val NL = '\n'.code; private const val TAB = '\t'.code
        private val STATES = setOf("al","ak","az","ar","ca","co","ct","de","dc","fl","ga","hi","id","il","in","ia","ks","ky","la","me","md","ma","mi","mn","ms","mo","mt","ne","nv","nh","nj","nm","ny","nc","nd","oh","ok","or","pa","ri","sc","sd","tn","tx","ut","vt","va","wa","wv","wi","wy",
            "alabama","alaska","arizona","arkansas","california","colorado","connecticut","delaware","florida","georgia","hawaii","idaho","illinois","indiana","iowa","kansas","kentucky","louisiana","maine","maryland","massachusetts","michigan","minnesota","mississippi","missouri","montana","nebraska","nevada","ohio","oklahoma","oregon","pennsylvania","tennessee","texas","utah","vermont","virginia","washington","wisconsin","wyoming","mexico","canada")
        private val STATE_NAMES_2 = setOf("new hampshire","new jersey","new mexico","new york","north carolina","north dakota","rhode island","south carolina","south dakota","west virginia","baja california","british columbia")
        fun open(file: File): SearchFile = SearchFile(Bytes.map(file))
        fun fromBytes(bytes: ByteArray): SearchFile = SearchFile(Bytes.wrap(bytes))
        /** Formats one index line; the packer sorts these before writing. */
        fun line(kind: Char, name: String, ctx: String, latE7: Int, lonE7: Int, key: String? = null): String {
            val n = name.replace('\t', ' ').replace('\n', ' ').trim(); val c = ctx.replace('\t', ' ').replace('\n', ' ').trim()
            return (key ?: TextNorm.normalize("$n $c")) + "\t" + kind + "\t" + n + "\t" + c + "\t" + latE7 + "\t" + lonE7
        }
        private val directions = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw")
        /** Index lines for one entry: the plain key, plus a key without a leading direction ("E Main St" also under "main st"). */
        fun lines(kind: Char, name: String, ctx: String, latE7: Int, lonE7: Int): List<String> {
            val first = line(kind, name, ctx, latE7, lonE7)
            val key = first.substringBefore('\t')
            val sp = key.indexOf(' ')
            if (sp > 0 && key.substring(0, sp) in directions) return listOf(first, line(kind, name, ctx, latE7, lonE7, key.substring(sp + 1)))
            return listOf(first)
        }
    }
}
