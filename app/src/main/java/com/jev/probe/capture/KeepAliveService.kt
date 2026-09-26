package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.jev.probe.core.Prefs

/**
 * A minimal foreground service whose only job is to keep the app process at
 * foreground importance so MIUI/HyperOS "Greezer" does not freeze the
 * accessibility service (which otherwise dies within seconds — see P1 report).
 * Not a full fix on its own: the user must also grant autostart / no battery
 * restriction, but this holds the process while the app is set up and running.
 *
 * Second job (round 3): a heartbeat that notices when the accessibility service
 * has silently fallen off the enabled list (OEM battery management and reinstalls
 * do this) and posts a one-shot notification deep-linking to the settings page.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    /** True once the dropout notification is up; reset when the service is seen
     *  enabled again, so a later dropout notifies again but the current one
     *  never repeats. */
    private var dropNotified = false

    private val heartbeat = object : Runnable {
        override fun run() {
            runCatching { checkAccessibilityAlive() }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val channelId = "jev_keepalive"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Jev 助手运行中", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Jev 助手运行中")
            .setContentText("在聊天旁读消息、给回复建议")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    /**
     * Whether our (disguised) accessibility service is still on the system's
     * enabled list. When it silently drops, the capture service is dead with no
     * visible sign — the bubble just never appears again.
     */
    private fun accessibilityAlive(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        // Colon-separated flattened component names; match exactly (case-
        // insensitively) so a similarly named service cannot satisfy the check.
        return enabled.split(':').any { it.equals(ACCESSIBILITY_COMPONENT, ignoreCase = true) }
    }

    private fun checkAccessibilityAlive() {
        if (accessibilityAlive()) { dropNotified = false; return }
        // Master switch off = the user stopped the assistant on purpose; the
        // missing accessibility entry is expected then, not a fault.
        if (dropNotified || !prefs.enabled) return
        dropNotified = true
        notifyAccessibilityDropped()
    }

    /** One-shot notification; tapping it lands on the accessibility settings page. */
    private fun notifyAccessibilityDropped() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Jev 助手状态", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, CHANNEL_STATUS)
            .setContentTitle("无障碍服务已掉线")
            .setContentText("读不到新消息了，点这里去重新开启")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // No POST_NOTIFICATIONS grant (Android 13+): notify() is simply a no-op.
        runCatching { nm.notify(NOTIF_ACCESSIBILITY_DROPPED, notif) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Heartbeat cadence: one Settings.Secure read per tick, negligible cost. */
        private const val HEARTBEAT_MS = 30_000L

        private const val CHANNEL_STATUS = "jev_status"
        private const val NOTIF_ACCESSIBILITY_DROPPED = 2

        /** Flattened component name of the disguised accessibility service, as it
         *  appears in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. The class
         *  name is deliberately NOT our own (see AndroidManifest / HANDOFF §8):
         *  do not "fix" it to match. */
        private const val ACCESSIBILITY_COMPONENT =
            "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
