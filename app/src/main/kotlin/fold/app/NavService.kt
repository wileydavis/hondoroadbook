package fold.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import fold.core.FixState
import fold.core.Fix
import fold.core.NavController
import fold.core.NavSink
import fold.core.NavView
import fold.core.ToneKind
import fold.core.Turn
import java.io.File

/**
 * Owns a ride. The [NavController] (the route, the follower, what has been said and chimed) lives here,
 * fed by this service's own GPS listener and ticked by its own timer, under a partial wake lock and a
 * location foreground service — so the screen can go dark, the activity can be torn down, and the voice
 * and the pips carry on. Every half minute the session is checkpointed to a private file; after a
 * process death the app offers to resume from it. The activity only observes: it draws what [view] says.
 */
class NavService : Service() {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    private var lastCheckpoint = 0L
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val c = controller ?: return
            c.update(Fix(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else -1.0, if (loc.hasBearing()) loc.bearing.toDouble() else -1.0, loc.elapsedRealtimeNanos / 1_000_000L, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0))
            onFix?.invoke(c)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    private val tick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            c.sound = Tone.enabled; c.speechReady = Speech.enabled && Speech.ready
            val now = android.os.SystemClock.elapsedRealtime()
            val v = try { c.tick(now) } catch (_: Throwable) { null }
            if (v != null) {
                if (now - lastNotify > 5000) { lastNotify = now; notifyLine(v) }
                if (now - lastCheckpoint > 30_000) { lastCheckpoint = now; writeCheckpoint(c, now) }
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { ui.post { onStopRequested?.invoke() ?: stop(this) }; return START_NOT_STICKY }
        // restarted by the system after process death: the checkpoint is for the app to offer, not for us to replay blind
        if (intent == null || controller == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(this), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, notification(this))
        } catch (_: Throwable) { failed("Android would not let navigation run in the background"); stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = try { (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roadbook:nav").also { it.acquire() } } catch (_: Throwable) { null }
        if (lm == null) {
            lm = getSystemService(LOCATION_SERVICE) as LocationManager
            try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
            catch (e: Throwable) { failed("GPS is not available (${e.javaClass.simpleName})"); stopSelf(); return START_NOT_STICKY }
        }
        running = true
        ui.removeCallbacks(tick); ui.post(tick)
        return START_NOT_STICKY
    }

    private fun failed(why: String) { running = false; lastFailure = why; ui.post { onFailure?.invoke(why) } }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(tick)
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wake = null
        super.onDestroy()
    }

    private fun notifyLine(v: NavView) {
        val c = controller ?: return
        val m = v.next
        val line = when {
            v.arrived -> getString(R.string.arrived)
            v.fixState == FixState.NONE || v.fixState == FixState.STALE -> "Waiting for GPS"
            !v.onRoute -> getString(R.string.off_track) + " · " + Units.cue(v.offDist)
            m == null -> Units.distance(v.remaining) + " to go"
            else -> Units.cue(v.distToNext) + " · " + (if (m.turn == Turn.ARRIVE) "Destination" else v.phrase()) + " · " + Units.distance(v.remaining) + " to go"
        }
        val t = if (c.destName.isNotEmpty()) "To " + c.destName else "Navigating"
        if (t == title && line == text) return
        title = t; text = line
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(this)) } catch (_: Throwable) {}
    }

    private fun writeCheckpoint(c: NavController, now: Long) {
        try { val f = checkpointFile(this); val tmp = File(f.parentFile, "active.tmp"); tmp.writeText(c.checkpoint(now)); if (!tmp.renameTo(f)) f.writeText(c.checkpoint(now)) } catch (_: Throwable) {}
    }

    companion object {
        const val CHANNEL = "nav"; const val ID = 8
        const val ACTION_STOP = "fold.app.END_NAVIGATION"
        @Volatile var running = false; private set
        @Volatile var lastFailure: String? = null
        @Volatile private var title = "Navigating"
        @Volatile private var text = ""
        /** the ride, for as long as there is one; the activity reads views from it and never updates it itself */
        @Volatile var controller: NavController? = null; private set
        /** observers, called on the main thread: a fix arrived; the End button; the service could not start */
        var onFix: ((NavController) -> Unit)? = null
        var onStopRequested: (() -> Unit)? = null
        var onFailure: ((String) -> Unit)? = null

        /** The phone's sounds and voice, driven by the controller's decisions. */
        val sink = object : NavSink {
            override fun tone(kind: ToneKind, level: Int, progress: Double) {
                when (kind) { ToneKind.PIP -> Tone.pip(progress); ToneKind.TURN -> Tone.turn(); ToneKind.OFF_ROUTE -> Tone.offRoute(); ToneKind.BACK -> Tone.back(); ToneKind.ARRIVE -> Tone.arrive(); ToneKind.DANGER -> Tone.danger(level); ToneKind.NOTE -> Tone.note() }
            }
            override fun say(phrase: String) = Speech.say(phrase)
            override fun prepare(phrase: String) = Speech.prepare(phrase)
        }

        fun checkpointFile(ctx: Context) = File(File(ctx.filesDir, "nav").apply { mkdirs() }, "active.nav")

        /** Takes ownership of a session and starts guiding it. */
        fun start(ctx: Context, c: NavController) {
            controller?.let { if (it !== c) it.end() }
            controller = c; lastFailure = null
            title = if (c.destName.isNotEmpty()) "To " + c.destName else "Navigating"; text = "Starting…"
            try { ctx.startForegroundService(Intent(ctx, NavService::class.java)) } catch (e: Throwable) { lastFailure = "Could not start the navigation service"; onFailure?.invoke(lastFailure!!) }
        }

        /** Ends the ride: the session is closed, the checkpoint deleted (a deliberate END is not something to resume). */
        fun stop(ctx: Context) {
            controller?.end(); controller = null
            try { checkpointFile(ctx).delete() } catch (_: Throwable) {}
            try { ctx.stopService(Intent(ctx, NavService::class.java)) } catch (_: Throwable) {}
        }

        /** A ride left by a dead process, if any: the checkpoint's text, for the app to offer. */
        fun leftover(ctx: Context): String? { val f = checkpointFile(ctx); return if (controller == null && f.exists() && f.length() > 0) try { f.readText() } catch (_: Throwable) { null } else null }

        private fun notification(ctx: Context): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val end = PendingIntent.getService(ctx, 2, Intent(ctx, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_track)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "End navigation", end).build())
                .setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .build()
        }
    }
}
