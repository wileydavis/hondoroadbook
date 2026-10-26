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
import android.os.PowerManager

/**
 * Keeps navigation alive with the screen off. On a bike the screen goes dark to save the battery and the
 * rider follows the voice and the chimes; without a foreground service Android would throttle GPS to a
 * few fixes an hour the moment the activity stops, and the guidance would fall silent.
 *
 * The service does three things and nothing else: it is a foreground service of type location (so every
 * GPS listener in the process keeps its 1 Hz fixes), it asks for fixes itself (so the provider stays
 * warm even if the activity is gone), and it holds a partial wake lock (so the follower and the cue
 * timers keep running between fixes). The route, the follower and the sound live in the activity and
 * [MainActivity.NavSession], as before; the notification shows the next cue and ends navigation.
 */
class NavService : Service() {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { onStopRequested?.invoke(); stopSelf(); return START_NOT_STICKY }
        // restarted by the system after process death: there is no route to resume, so do not pretend
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Navigating"; text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(this), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, notification(this))
        } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        if (wake == null) {
            wake = try { (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roadbook:nav").also { it.acquire() } } catch (_: Throwable) { null }
        }
        if (lm == null) {
            lm = getSystemService(LOCATION_SERVICE) as LocationManager
            try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) } catch (_: Throwable) {}
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wake = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "nav"; const val ID = 8
        const val ACTION_STOP = "fold.app.END_NAVIGATION"
        private const val EXTRA_TITLE = "title"; private const val EXTRA_TEXT = "text"
        @Volatile var running = false; private set
        @Volatile private var title = "Navigating"
        @Volatile private var text = ""
        /** set by the activity: the notification's End button lands here, on the main thread */
        var onStopRequested: (() -> Unit)? = null

        fun start(ctx: Context, title: String, text: String) {
            try { ctx.startForegroundService(Intent(ctx, NavService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)) } catch (_: Throwable) {}
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, NavService::class.java)) } catch (_: Throwable) {} }

        /** The notification's words, refreshed from the HUD a few times a minute; no service call needed. */
        fun update(ctx: Context, newTitle: String, newText: String) {
            if (!running) return
            if (newTitle == title && newText == text) return
            title = newTitle; text = newText
            try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(ctx)) } catch (_: Throwable) {}
        }

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
