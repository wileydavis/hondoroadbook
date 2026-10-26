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
import android.os.IBinder
import android.os.Looper
import fold.core.Cue
import fold.core.Geo
import fold.core.Gpx
import java.io.File

/**
 * A pre-run: the rider drives the course, the phone writes the line, and every stop to add a cue or a
 * note lands on it. The result is a GPX track with authored cues, ready to follow at speed.
 *
 * State lives here (a process singleton) so the activity can come and go; [RecordService] keeps the
 * GPS running and the process alive while the screen is off, and rewrites a recovery file every
 * minute so a crash or a dead battery loses at most a minute.
 */
object Recorder {
    val lats = ArrayList<Double>(); val lons = ArrayList<Double>(); val times = ArrayList<Long>()
    val cues = ArrayList<Cue>()
    @Volatile var running = false; private set
    var startedAt = 0L; private set
    var distance = 0.0; private set
    /** last recorded position, for placing cues */
    var lastLat = Double.NaN; var lastLon = Double.NaN
    var onChange: (() -> Unit)? = null
    private var dirty = false
    private var lastFlush = 0L

    fun start(ctx: Context) {
        if (running) return
        clear(); lastFailure = null; running = true; startedAt = System.currentTimeMillis()
        try { ctx.startForegroundService(Intent(ctx, RecordService::class.java)) } catch (_: Throwable) { running = false; throw IllegalStateException("Could not start recording service") }
        onChange?.invoke()
    }

    fun stop(ctx: Context) {
        running = false
        try { ctx.stopService(Intent(ctx, RecordService::class.java)) } catch (_: Throwable) {}
        flush(ctx, true)
        onChange?.invoke()
    }

    /** After Android restarted the service (process death mid-ride): the journal is the recording; keep going. */
    fun resumeAfterRestart() { running = true; dirty = true; onChange?.invoke() }

    /** Acquisition never started: not recording, whatever start() said a moment ago. What was already journaled stays. */
    @Volatile var lastFailure: String? = null
    fun failed(why: String) { running = false; lastFailure = why; onChange?.invoke() }

    /** Drops the recording and its recovery file. */
    fun discard(ctx: Context) { clear(); recoveryFile(ctx).delete() }

    private fun clear() { lats.clear(); lons.clear(); times.clear(); cues.clear(); distance = 0.0; lastLat = Double.NaN; lastLon = Double.NaN; dirty = false }

    @Synchronized fun addFix(lat: Double, lon: Double, timeMs: Long, accuracy: Double) {
        if (!running || accuracy > 60.0) return
        if (lats.isNotEmpty()) {
            val d = Geo.fastDistance(lats.last(), lons.last(), lat, lon)
            // a point every 5 m or every 10 s, whichever comes first; standing still writes nothing
            if (d < 5.0 && timeMs - times.last() < 10_000) return
            if (d < 2.0) return
            distance += d
        }
        lats.add(lat); lons.add(lon); times.add(timeMs); lastLat = lat; lastLon = lon; dirty = true
        onChange?.invoke()
    }

    @Synchronized fun addCue(c: Cue) { cues.add(c); dirty = true; onChange?.invoke() }
    @Synchronized fun removeCue(c: Cue) { cues.remove(c); dirty = true; onChange?.invoke() }

    val elapsedMs: Long get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
    val hasData: Boolean get() = lats.size >= 2 || cues.isNotEmpty()

    fun gpx(name: String): String = synchronized(this) { Gpx.writeTrack(name, lats.toDoubleArray(), lons.toDoubleArray(), times.toLongArray(), ArrayList(cues)) }

    fun defaultName(): String = "Pre-run " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date(if (startedAt == 0L) System.currentTimeMillis() else startedAt))

    fun recoveryFile(ctx: Context) = File(File(ctx.filesDir, "rec").apply { mkdirs() }, "recording.gpx")

    /** Rewrites the recovery file if anything changed (at most once a minute unless forced). */
    fun flush(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!dirty || (!force && now - lastFlush < 60_000)) return
        try {
            val f = recoveryFile(ctx); val tmp = File(f.parentFile, "recording.tmp")
            tmp.writeText(gpx(defaultName())); tmp.renameTo(f)
            dirty = false; lastFlush = now
        } catch (_: Throwable) {}
    }

    /** Loads an unfinished recording left by a crash. Returns true if there was one with data. */
    fun recover(ctx: Context): Boolean {
        val f = recoveryFile(ctx)
        if (!f.exists() || running) return false
        val tr = try { Gpx.parse(f.readText()) } catch (_: Throwable) { null } ?: run { f.delete(); return false }
        clear()
        for (i in tr.lats.indices) { lats.add(tr.lats[i]); lons.add(tr.lons[i]); times.add(0L); if (i > 0) distance += Geo.fastDistance(tr.lats[i - 1], tr.lons[i - 1], tr.lats[i], tr.lons[i]) }
        cues.addAll(tr.cues)
        startedAt = f.lastModified()
        dirty = true
        return hasData
    }
}

/** Foreground service: a notification, GPS at 1 Hz, nothing else. Stops itself with the recording. */
class RecordService : Service() {
    private lateinit var lm: LocationManager
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Recorder.addFix(loc.latitude, loc.longitude, System.currentTimeMillis(), if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0)
            Recorder.flush(this@RecordService)
            if (System.currentTimeMillis() - lastNotify > 15_000) { lastNotify = System.currentTimeMillis(); try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification()) } catch (_: Throwable) {} }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Recorder.stop(this); stopSelf(); return START_NOT_STICKY }
        if (!Recorder.running) {
            // sticky restart after process death: resume from the journal, or stop honestly if there is nothing to resume
            if (intent == null && Recorder.recover(this)) Recorder.resumeAfterRestart()
            else { stopSelf(); return START_NOT_STICKY }
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, notification())
        } catch (_: Throwable) { Recorder.failed("Android would not let the recording run in the background"); stopSelf(); return START_NOT_STICKY }
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
        catch (e: Throwable) { Recorder.failed("GPS is not available (${e.javaClass.simpleName})"); stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mins = Recorder.elapsedMs / 60_000
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Recording pre-run")
            .setContentText(Units.distance(Recorder.distance) + " · " + (mins / 60) + ":" + "%02d".format(mins % 60) + " · " + Recorder.cues.size + " cues")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        try { lm.removeUpdates(listener) } catch (_: Throwable) {}
        Recorder.flush(this, true)
        super.onDestroy()
    }

    companion object { const val CHANNEL = "rec"; const val ID = 7; const val ACTION_STOP = "fold.app.STOP_RECORDING" }
}
