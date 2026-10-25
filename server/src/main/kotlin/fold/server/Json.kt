package fold.server

/** The little JSON this service needs: a writer that escapes properly and a parser for the catalog. */
object Json {
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2); sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
    fun num(d: Double, decimals: Int = 6): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        var s = String.format(java.util.Locale.ROOT, "%.${decimals}f", d)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
    fun obj(vararg pairs: Pair<String, String>): String = pairs.joinToString(",", "{", "}") { str(it.first) + ":" + it.second }
    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    /** Parses one JSON document into Map / List / String / Double / Boolean / null. */
    fun parse(text: String): Any? = Parser(text).document()

    private class Parser(val s: String) {
        var i = 0
        fun document(): Any? { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; return v }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> string()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }
        fun number(): Double { val st = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++; return s.substring(st, i).toDouble() }
        fun string(): String {
            i++; val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> { val e = s[i++]; when (e) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }; else -> sb.append(e) } }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = string(); ws(); require(s[i] == ':') { "expected : at $i" }; i++; m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("expected , or } at $i") } }
        }
        fun arr(): List<Any?> {
            i++; val l = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l.add(value()); ws(); when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("expected , or ] at $i") } }
        }
    }
}
