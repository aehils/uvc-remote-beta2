package com.expiation.reemanremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.expiation.reemanremote.ui.rightNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a routine or the lamps going while the app is out of sight, for a phone call or another
 * app: Android leaves a service like this running, with the screen off too, and it shows what the
 * robot is doing and STOP in a notification that stays up for as long as the run does. Swiping the
 * app away ends the run. If the phone itself dies or leaves the Wi-Fi, nothing here can switch
 * the lamps off, since the robot has no timer of its own for them.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = RobotSession.get(application)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val first = rightNow(session.controller.state.value)
        val started = runCatching {
            val n = ongoing(first.headline, first.detail)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID_ONGOING, n)
            }
        }.isSuccess
        if (!started) {
            stopSelf() // the session sees no service, so leaving the app ends the run as it used to
            return
        }
        session.serviceUp = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reemanremote:run")
            .apply { acquire(MAX_LOCK_MS) }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "reemanremote:run")
            .apply { acquire() }
        scope.launch {
            session.controller.state
                .map { rightNow(it).let { now -> now.headline to now.detail } }
                .distinctUntilChanged()
                .collect { (headline, detail) ->
                    getSystemService(NotificationManager::class.java).notify(ID_ONGOING, ongoing(headline, detail))
                    delay(UPDATE_MS) // the countdown changes every second; Android drops updates that fast
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) session.controller.stop()
        return START_NOT_STICKY
    }

    /** Swiped out of the recent apps: the run ends here, and the service with it once OFF is accepted. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session.controller.endRun("app closed")
    }

    override fun onDestroy() {
        scope.cancel()
        session.serviceUp = false
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun ongoing(headline: String, detail: String?): Notification {
        channels(this)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_tab_tasks)
            .setContentTitle(headline)
            .setContentText(detail)
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "STOP", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.expiation.reemanremote.STOP"
        private const val CHANNEL_ONGOING = "run"
        private const val CHANNEL_ALERTS = "alerts"
        private const val ID_ONGOING = 1000
        private const val UPDATE_MS = 5_000L
        private const val MAX_LOCK_MS = 12 * 60 * 60 * 1000L // past any run; never held for good if something goes wrong
        const val ID_FINISHED = 1001
        const val ID_PROBLEM = 1002

        fun start(context: Context) {
            val intent = Intent(context, RunService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }

        /** Something to know while the app is out of sight: a routine ended, or needs someone. */
        fun alert(context: Context, id: Int, title: String, text: String?) {
            channels(context)
            context.getSystemService(NotificationManager::class.java).notify(
                id,
                builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_tab_tasks)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .build(),
            )
        }

        /** Brings the app back as it was, as its icon would. */
        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Routine under way", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Routine finished or stopped", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun builder(context: Context, channel: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channel)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
    }
}
