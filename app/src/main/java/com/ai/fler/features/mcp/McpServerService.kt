package com.ai.fler.features.mcp

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
import javax.inject.Inject

/**
 * MCP 服务器前台服务（局域网模式保活，常驻通知）。
 *
 * 局域网模式启动后进入前台，避免 App 退到后台/进程被杀导致 MCP 服务中断。
 * START_STICKY：进程被系统回收后尝试重启。
 */
@AndroidEntryPoint
class McpServerService : Service() {

    @Inject
    lateinit var manager: McpServerManager

    // 注入即实例化：隧道管理器随 MCP 前台服务存活，
    // 依据服务器状态自动建/断外网隧道（无需用户停留在设置页）。
    @Inject
    lateinit var tunnelManager: McpTunnelManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                manager.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP 服务器",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "MCP Server 局域网模式保活"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("fler MCP 服务器")
            .setContentText("正在运行，供 AI 代理连接")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "fler_mcp_server"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_STOP = "com.ai.fler.ACTION_STOP_MCP_SERVER"

        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, McpServerService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
