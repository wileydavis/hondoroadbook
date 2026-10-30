package fold.app

import kotlin.math.roundToInt

/** Distance/speed formatting. Default is miles/feet; metric can be toggled in Maps. */
object Units {
    var metric = false

    fun distance(m: Double): String {
        if (metric) {
            return when {
                m < 950 -> "${(m / 10).roundToInt() * 10} m"
                m < 10_000 -> "%.1f km".format(m / 1000)
                else -> "${(m / 1000).roundToInt()} km"
            }
        }
        val ft = m * 3.28084
        return when {
            ft < 950 -> "${(ft / 10).roundToInt() * 10} ft"
            ft < 52_800 -> "%.1f mi".format(ft / 5280)
            else -> "${(ft / 5280).roundToInt()} mi"
        }
    }

    /** Rounded for the HUD: coarse steps so the number doesn't flicker. */
    fun cue(m: Double): String {
        if (metric) {
            return when {
                m < 100 -> "${(m / 10).roundToInt() * 10} m"
                m < 1000 -> "${(m / 50).roundToInt() * 50} m"
                m < 10_000 -> "%.1f km".format(m / 1000)
                else -> "${(m / 1000).roundToInt()} km"
            }
        }
        val ft = m * 3.28084
        return when {
            ft < 300 -> "${(ft / 10).roundToInt() * 10} ft"
            ft < 1000 -> "${(ft / 50).roundToInt() * 50} ft"
            ft < 2640 -> "${(ft / 100).roundToInt() * 100} ft"
            ft < 52_800 -> "%.1f mi".format(ft / 5280)
            else -> "${(ft / 5280).roundToInt()} mi"
        }
    }

    fun speed(ms: Double): String = if (metric) "${(ms * 3.6).roundToInt()} km/h" else "${(ms * 2.23694).roundToInt()} mph"
    /** height above sea level, to the nearest 10 ft / 5 m */
    fun elevation(m: Double): String = if (metric) "${((m / 5).roundToInt() * 5)} m" else "${String.format("%,d", (m * 3.28084 / 10).roundToInt() * 10)} ft"
    /** the clock time [sec] from now, in the phone's own format ("3:42 PM") */
    fun clockFromNow(ctx: android.content.Context, sec: Double): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(System.currentTimeMillis() + (sec * 1000).toLong()))

    fun time(sec: Double): String {
        val m = (sec / 60).roundToInt()
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}
