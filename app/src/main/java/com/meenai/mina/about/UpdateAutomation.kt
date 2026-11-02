package com.meenai.mina.about

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meenai.mina.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自动更新的后半段:检查发现新版本后,不等用户点,自己把 APK 下到应用专属目录,
 * 下好了发一条通知,点通知直接进安装。同一版本最多提醒一次;系统安装器仍会请用户确认。
 * 关于页的手动下载、安装不受影响。
 */
object UpdateAutomation {
    private const val CHANNEL = "update-ready"
    private const val NOTIFICATION_ID = 5001
    private const val PREFS = "auto-update"
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** App 每次打开时调:后台查一次版本,查到新版就接着自动下载、下好提醒。 */
    fun onAppOpened(context: Context, checker: UpdateChecker) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val updater = ApkUpdater(app)
        scope.launch {
            try {
                runCatching { checker.autoCheck() }
                runCatching { drive(app, updater, checker) }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun drive(app: Context, updater: ApkUpdater, checker: UpdateChecker) {
        val release = checker.state.value.update ?: return
        if (release.apkUrl == null) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "notified:" + release.version
        if (prefs.getBoolean(key, false)) return
        val state = runCatching { updater.state(release) }.getOrNull() ?: return
        when (state.phase) {
            DownloadPhase.Ready -> notify(app, release)
            DownloadPhase.Downloading, DownloadPhase.Paused -> {}
            else -> runCatching { updater.download(release) }
        }
        // 这次自己下的、或上一轮还挂着的下载,等它出结果:下好了提醒,失败或被清了就收手。
        val deadline = System.currentTimeMillis() + 45 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(5000)
            val s = runCatching { updater.state(release) }.getOrNull()
            when (s?.phase) {
                DownloadPhase.Ready -> {
                    notify(app, release)
                    prefs.edit().putBoolean(key, true).commit()
                    return
                }
                DownloadPhase.Idle, DownloadPhase.Failed, null -> return
                else -> {}
            }
        }
    }

    private fun notify(app: Context, release: ReleaseInfo) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.about_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getActivity(
                app, 0, Intent(app, UpdateInstallActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.about_update_ready, release.version))
                .setContentText(app.getString(R.string.about_update_notification_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }
}
