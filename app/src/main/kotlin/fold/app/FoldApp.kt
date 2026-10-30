package fold.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import fold.core.Profile
import org.maplibre.android.MapLibre

class FoldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // We have no ACCESS_NETWORK_STATE permission, so tell the map library it is offline for good.
        // This also stops it from ever consulting ConnectivityManager (which would throw).
        MapLibre.setConnected(false)
        AppCompatDelegate.setDefaultNightMode(if (Prefs(this).night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        // no crash reporting leaves the phone by itself; a crash is written to a private file, and SEND FEEDBACK
        // offers to include it in the rider's own email — they read it before it goes anywhere
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { java.io.File(filesDir, "last-crash.txt").writeText("Roadbook ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n${java.util.Date()}\n\n" + android.util.Log.getStackTraceString(e).take(6000)) } catch (_: Throwable) {}
            prior?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Opens the rider's mail app with a feedback message started: version, phone, and the last crash if there was one. Nothing is sent until they press send. */
        fun feedback(ctx: android.content.Context, extra: String = "") {
            val crash = java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()
            val body = buildString {
                append("What happened:\n\n\n")
                append("— ").append("Roadbook ").append(BuildConfig.VERSION_NAME).append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
                if (extra.isNotEmpty()) append(extra).append('\n')
                if (crash != null) append("\nThe app's last crash (delete this if you would rather not send it):\n").append(crash)
            }
            val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("wiley@machinenusa.com")).putExtra(android.content.Intent.EXTRA_SUBJECT, "Roadbook feedback").putExtra(android.content.Intent.EXTRA_TEXT, body)
            try { ctx.startActivity(i) } catch (_: Throwable) { android.widget.Toast.makeText(ctx, "No mail app — write to wiley@machinenusa.com", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}

/** The only persisted settings. No history, no destinations, no tracks. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("fold", Context.MODE_PRIVATE)
    var night: Boolean
        get() = p.getBoolean("night", false)
        set(v) { p.edit().putBoolean("night", v).apply() }
    var profile: Profile
        get() = Profile.parse(p.getString("profile", null))
        set(v) { p.edit().putString("profile", v.name).apply() }
    var metric: Boolean
        get() = p.getBoolean("metric", false)
        set(v) { p.edit().putBoolean("metric", v).apply() }
    /** draw installed terrain (hillshade) packs under the roads */
    var terrain: Boolean
        get() = p.getBoolean("terrain", true)
        set(v) { p.edit().putBoolean("terrain", v).apply() }
    /** the noon-sun map: black ground, wide bright roads, big labels, nothing else; dark interface, least power */
    var sun: Boolean
        get() = p.getBoolean("sun", false)
        set(v) { p.edit().putBoolean("sun", v).apply() }
    /** which layer file the map is drawn from right now */
    val flavor: String get() = if (sun) "sun" else if (night) "dark" else "light"
    /** draw the packs' places (fuel, camping, water, beds, repair, food) on the map when zoomed in */
    var places: Boolean
        get() = p.getBoolean("places", true)
        set(v) { p.edit().putBoolean("places", v).apply() }
    /** Modes shown on the compare screen. */
    var modes: Set<Profile>
        get() = (p.getStringSet("modes", null) ?: setOf("FAST", "BACKROADS", "RIDE")).mapNotNull { try { Profile.valueOf(it) } catch (_: Throwable) { null } }.toSet().ifEmpty { setOf(Profile.FAST) }
        set(v) { p.edit().putStringSet("modes", v.map { it.name }.toSet()).apply() }
    var sound: Boolean
        get() = p.getBoolean("sound", false)
        set(v) { p.edit().putBoolean("sound", v).apply() }
    /** battery saver: the screen wakes for cues and sleeps after, the GPS eases between cues, the CPU rests */
    var batterySaver: Boolean
        get() = p.getBoolean("battery", false)
        set(v) { p.edit().putBoolean("battery", v).apply() }
    /** Screen orientation to hold, as an ActivityInfo.SCREEN_ORIENTATION_* value; -1 = follow the sensor. */
    var orientation: Int
        get() = p.getInt("orientation", -1)
        set(v) { p.edit().putInt("orientation", v).apply() }
    /** The one folder Fold may read files from (a tree URI the person granted). */
    /** the rider has seen the terms once (first launch) */
    var accepted: Boolean
        get() = p.getBoolean("accepted", false)
        set(v) { p.edit().putBoolean("accepted", v).apply() }
    var folder: String?
        get() = p.getString("folder", null)
        set(v) { p.edit().putString("folder", v).apply() }
}
