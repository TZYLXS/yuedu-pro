package io.legado.app.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.constant.AppConst
import io.legado.app.ui.config.FnReaderSyncLogActivity
import splitties.init.appCtx

/**
 * T1 通知分道：同步结果固定 id + 独立 channel，不碰前台「手机解析」常驻通知。
 * 点击结果通知进入 App 内「同步记录」页。
 */
object FnReaderSyncNotifier {

    /** 固定 id，避免每轮同步生成一串通知 */
    const val NOTIFICATION_ID = 0x464E02
    private const val CHANNEL_ID = AppConst.channelIdFnReaderSync

    fun ensureChannel(context: Context = appCtx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FnReader 同步结果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "书源首次合并、增量上传/下载/删除的结果"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun notify(result: FnReaderAutoSync.Result, context: Context = appCtx) {
        ensureChannel(context)
        val intent = Intent(context, FnReaderSyncLogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)
        val text = result.countText()
        val bigText = buildString {
            append(text)
            if (result.note.isNotEmpty()) append("\n").append(result.note)
            append("\n耗时 ").append(result.durationMs).append(" ms")
            if (result.error.isNotEmpty()) append("\n失败：").append(result.error)
            if (result.sources.isNotEmpty()) append("\n").append(result.sources)
            append("\n点击查看同步记录")
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (result.error.isEmpty()) "FnReader 同步完成" else "FnReader 同步失败")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }
}
