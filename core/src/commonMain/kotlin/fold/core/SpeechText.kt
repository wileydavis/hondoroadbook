package fold.core

/**
 * What a cue's words should sound like. A voice reads what it is given: "US-287" as "U S dash two eight seven",
 * "RD" as "R D", a roll chart's "R ON RANCH RD !!" as letters and shouting. This turns the written cue into the
 * spoken one before either app's voice sees it:
 *
 *  - road numbers read the way people say them: US-287 → "U S two eighty-seven", I-90 → "I ninety",
 *    CR 14B → "county road fourteen B", FS 1001 → "forest road ten oh one";
 *  - the usual abbreviations spelled out: RD, HWY, AVE, JCT, XING, MI, FT, MPH…;
 *  - a roll chart's capitals read as words, its L and R as left and right, its ! marks dropped (the caution is
 *    said already); common acronyms (BLM, ATV, GPS…) stay letters.
 *
 * Conservative on purpose: anything it does not recognise is left for the voice to read as written.
 */
object SpeechText {
    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0–99 in words */
    fun twoDigits(n: Int): String = when {
        n < 20 -> ONES[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> TENS[n / 10] + "-" + ONES[n % 10]
    }

    /** A road number the way it is said: 90 → "ninety", 287 → "two eighty-seven", 205 → "two oh five", 1001 → "ten oh one". */
    fun roadNumber(n: Int): String = when {
        n < 100 -> twoDigits(n)
        n < 1000 -> if (n % 100 == 0) ONES[n / 100] + " hundred" else ONES[n / 100] + " " + (if (n % 100 < 10) "oh " + ONES[n % 100] else twoDigits(n % 100))
        n < 10000 -> if (n % 100 == 0 && n % 1000 != 0) twoDigits(n / 100) + " hundred" else twoDigits(n / 100) + " " + (if (n % 100 == 0) "hundred" else if (n % 100 < 10) "oh " + ONES[n % 100] else twoDigits(n % 100))
        else -> n.toString()
    }

    // a road's prefix → how it is said
    private val PREFIX = mapOf(
        "US" to "U S", "I" to "I", "SR" to "state route", "SH" to "state highway", "CR" to "county road", "CO RD" to "county road",
        "FR" to "forest road", "FS" to "forest road", "FSR" to "forest road", "NF" to "forest road", "NFS" to "forest road",
        "BLM" to "B L M road", "HWY" to "highway", "RTE" to "route", "RT" to "route", "TR" to "trail", "TRL" to "trail", "EXIT" to "exit",
    )
    // two-letter state and province codes before a number: "MT-84" → "M T eighty-four"
    // (not the ones that are also words a chart writes before a number: IN, OR, ON, ME, OK, HI, AT…)
    private val STATES = ("AL AK AZ AR CA CO CT DE FL GA ID IL IA KS KY LA MD MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH PA RI SC SD TN TX UT VT VA WA WV WI WY " +
        "AB BC MB NB NL NS PE QC SK YT NT NU").split(' ').toSet()

    private val WORDS = mapOf(
        "RD" to "road", "HWY" to "highway", "AVE" to "avenue", "AV" to "avenue", "BLVD" to "boulevard", "LN" to "lane", "PKWY" to "parkway",
        "TRL" to "trail", "JCT" to "junction", "XING" to "crossing", "XRD" to "crossroads", "MTN" to "mountain", "CRK" to "creek", "CYN" to "canyon",
        "RR" to "railroad", "FWY" to "freeway", "EXPY" to "expressway", "CTR" to "center", "BR" to "bridge",
        "MI" to "miles", "MILE" to "mile", "KM" to "kilometres", "FT" to "feet", "YD" to "yards", "YDS" to "yards", "MPH" to "miles an hour", "KPH" to "kilometres an hour", "KMH" to "kilometres an hour",
        "APPROX" to "about", "W/" to "with", "W/O" to "without", "ODO" to "odometer", "NC" to "do not cut", "SS" to "special stage", "CG" to "cattle guard",
        "L" to "left", "R" to "right", "KL" to "keep left", "KR" to "keep right", "SO" to "straight on", "TL" to "turn left", "TR" to "turn right",
        "BL" to "bear left", "BR" to "bear right",
        // FIA codes the planner offers for layout B
        "KPL" to "keep left", "KPR" to "keep right", "KPS" to "keep straight", "FPP" to "follow the main track", "FRO" to "follow the road", "HP" to "off track",
        "PP" to "main track", "DN" to "dune", "DNX" to "dunes",
    )
    // words that read as "left/right" only in a roll chart's capitals; in ordinary text "R" or "BR" could be anything
    private val CHART_ONLY = setOf("L", "R", "KL", "KR", "SO", "TL", "TR", "BL", "BR", "CG", "NC", "SS", "ODO", "PP", "HP", "DN", "DNX", "FPP", "FRO", "KPL", "KPR", "KPS")
    // these stay as letters (the voice spells them)
    private val ACRONYMS = setOf("BLM", "USFS", "ATV", "OHV", "UTV", "GPS", "ATC", "FTC", "MTC", "CP", "USA", "UK", "NPS", "BNSF", "ID")

    fun forVoice(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        // a roll chart's emphasis: "!!" says nothing a voice can say
        s = s.replace(Regex("!{2,}"), " ").replace("&", " and ").replace("~", " about ")
        val letters = s.filter { it.isLetter() }
        val shouted = letters.length >= 2 && letters.none { it.isLowerCase() }
        // road numbers: "US-287", "I 90", "CR 14B", "MT-84", "FS1001"
        s = Regex("\\b(US|I|SR|SH|CR|CO RD|FR|FSR|FS|NFS|NF|BLM|HWY|RTE|RT|TRL|TR|EXIT|[A-Z]{2})[- ]?(\\d{1,4})([A-Z])?\\b", RegexOption.IGNORE_CASE).replace(s) { m ->
            val p = m.groupValues[1].uppercase(); val num = m.groupValues[2].toInt(); val suffix = m.groupValues[3].uppercase()
            val said = PREFIX[p] ?: if (p in STATES && m.groupValues[1].all { it.isUpperCase() }) p.toCharArray().joinToString(" ") else return@replace m.value
            // a lowercase word before a number ("at 12") is not a road: only capitals, or a known prefix written as one
            if (PREFIX[p] != null && m.groupValues[1] != m.groupValues[1].uppercase() && p !in setOf("HWY", "RTE", "EXIT", "TRL")) return@replace m.value
            said + " " + roadNumber(num) + (if (suffix.isNotEmpty()) " $suffix" else "")
        }
        // decimals with a unit: "0.4 MI" → "0.4 miles"; the voice reads the number itself
        // whole words, case-insensitively for the street abbreviations, exactly for a chart's capitals
        s = Regex("(?<![\\w/])([A-Za-z]{1,6}/?[A-Za-z]?)(?![\\w/])\\.?").replace(s) { m ->
            val w = m.groupValues[1]; val up = w.uppercase()
            val sub = WORDS[up]
            when {
                up in ACRONYMS && w == up -> if (up == "ID" && !shouted) w else w.toCharArray().joinToString(" ")
                sub == null -> m.value
                up in CHART_ONLY -> if (shouted || w == up && w.length >= 2) sub else m.value
                // "MI" alone after a number is miles; "Mi" in a name is not
                up == "MI" || up == "FT" || up == "YD" || up == "YDS" -> if (w == up || w == w.lowercase()) sub else m.value
                else -> if (w == up || w.first().isUpperCase()) sub else m.value
            }
        }
        // the rest of a chart's capitals as ordinary words, so "RANCH" is not spelled out
        if (shouted) s = Regex("\\b[A-Z][A-Z']+\\b").replace(s) { m -> if (m.value in ACRONYMS) m.value.toCharArray().joinToString(" ") else m.value.lowercase() }
        s = s.replace(Regex("\\s*-\\s+|\\s+-\\s*"), ", ").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.;:])"), "$1").trim()
        return s
    }
}
