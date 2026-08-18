package com.ai.fler.features.analysis

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
import com.ai.fler.feature.project.AnalysisRunner
import com.ai.fler.feature.project.AnalysisStage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 分析前台服务（保活）。
 *
 * 分析管线由 [AnalysisRunner] 在 app 级协程后台执行，若不挂前台服务，
 * 进程退到后台/被系统回收时分析会被中断（Blutter 可运行数分钟）。
 * 本服务在分析期间保持前台 + 常驻通知（阶段/进度/项目名），
 * `START_STICKY` 让进程被回收后由系统重建。
 */
@AndroidEntryPoint
class AnalysisService : Service() {

    @Inject
    lateinit var analysisRunner: AnalysisRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                // 观察分析进度，实时更新通知
                scope.launch {
                    analysisRunner.analysisProgress.collect { progress ->
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, buildNotification(progress))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "分析任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Blutter 分析后台保活"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: com.ai.fler.feature.project.AnalysisProgress? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "fler 分析中"
        val text = when (progress?.stage) {
            AnalysisStage.Extracting -> "正在提取 so 文件"
            AnalysisStage.DetectingVersion -> "正在检测 Dart 版本"
            AnalysisStage.DownloadingEngine -> "正在下载引擎"
            AnalysisStage.LoadingEngine -> "正在加载引擎"
            AnalysisStage.Analyzing -> "正在分析 (${(progress.progress * 100).toInt()}%)"
            AnalysisStage.SavingResults -> "正在保存结果"
            AnalysisStage.Completed -> "分析完成"
            AnalysisStage.Failed -> "分析失败"
            else -> "后台分析运行中"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "fler_analysis"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_STOP = "com.ai.fler.ACTION_STOP_ANALYSIS"

        fun start(context: Context) {
            val intent = Intent(context, AnalysisService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AnalysisService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}