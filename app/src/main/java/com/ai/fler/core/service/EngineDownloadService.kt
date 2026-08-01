package com.ai.fler.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ai.fler.MainActivity
import com.ai.fler.R
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 引擎包下载前台服务。
 *
 * Android 14+ 合规要求：下载任务超过几秒必须通过前台服务显示通知。
 * 本服务在状态栏显示下载进度，完成后自动停止。
 */
@AndroidEntryPoint
class EngineDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_DOWNLOAD -> startDownload()
            ACTION_CANCEL -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startDownload() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            EngineDownloadEntryPoint::class.java,
        )
        val enginePackManager = entryPoint.enginePackManager()

        startForeground(NOTIFICATION_ID, buildNotification(0, 0, "准备下载..."))

        serviceScope.launch {
            enginePackManager.ensureEnginesReady().collectLatest { progress ->
                val notification = buildNotification(
                    progress.downloadedBytes,
                    progress.totalBytes,
                    buildContent(progress),
                    progress.overallProgress,
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)

                when (progress.phase) {
                    EnginePackManager.EngineProgress.Phase.COMPLETED -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    EnginePackManager.EngineProgress.Phase.FAILED -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> { /* 继续 */ }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "引擎包下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "下载 fler 引擎包"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        downloaded: Long,
        total: Long,
        content: String,
        progress: Float = -1f,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("fler 引擎包")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progress >= 0 && progress < 1.0f) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }

        return builder.build()
    }

    private fun buildContent(progress: EnginePackManager.EngineProgress): String {
        return when (progress.phase) {
            EnginePackManager.EngineProgress.Phase.DOWNLOADING -> {
                val mb = progress.downloadedBytes / (1024.0 * 1024.0)
                val totalMb = progress.totalBytes / (1024.0 * 1024.0)
                if (progress.totalBytes > 0) {
                    "下载中: %.1f / %.1f MB (%s)".format(mb, totalMb, progress.speed)
                } else {
                    "下载中: %.1f MB (%s)".format(mb, progress.speed)
                }
            }
            EnginePackManager.EngineProgress.Phase.VERIFYING -> "校验中..."
            EnginePackManager.EngineProgress.Phase.EXTRACTING -> "解压中: %.0f%%".format(progress.extractProgress * 100)
            EnginePackManager.EngineProgress.Phase.LOADING -> "加载引擎..."
            EnginePackManager.EngineProgress.Phase.COMPLETED -> "完成"
            EnginePackManager.EngineProgress.Phase.FAILED -> "失败: ${progress.errorMessage}"
            EnginePackManager.EngineProgress.Phase.IDLE -> "等待中..."
        }
    }

    companion object {
        private const val CHANNEL_ID = "fler_engine_download"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.ai.fler.ACTION_START_ENGINE_DOWNLOAD"
        const val ACTION_CANCEL = "com.ai.fler.ACTION_CANCEL_ENGINE_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, EngineDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, EngineDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    /**
     * Hilt EntryPoint 用于在 Service 中获取 EnginePackManager。
     *
     * @see https://dagger.dev/hilt/injecting-dependencies#entrypoints
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EngineDownloadEntryPoint {
        fun enginePackManager(): EnginePackManager
    }
}
