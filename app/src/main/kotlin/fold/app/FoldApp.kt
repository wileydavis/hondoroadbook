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
    /** Modes shown on the compare screen. */
    var modes: Set<Profile>
        get() = (p.getStringSet("modes", null) ?: setOf("FAST", "BACKROADS", "RIDE")).mapNotNull { try { Profile.valueOf(it) } catch (_: Throwable) { null } }.toSet().ifEmpty { setOf(Profile.FAST) }
        set(v) { p.edit().putStringSet("modes", v.map { it.name }.toSet()).apply() }
    var sound: Boolean
        get() = p.getBoolean("sound", false)
        set(v) { p.edit().putBoolean("sound", v).apply() }
    /** Screen orientation to hold, as an ActivityInfo.SCREEN_ORIENTATION_* value; -1 = follow the sensor. */
    var orientation: Int
        get() = p.getInt("orientation", -1)
        set(v) { p.edit().putInt("orientation", v).apply() }
    /** The one folder Fold may read files from (a tree URI the person granted). */
    var folder: String?
        get() = p.getString("folder", null)
        set(v) { p.edit().putString("folder", v).apply() }
}
