package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.model.FnReaderRemote
import io.legado.app.ui.config.FnReaderConfigDialog
import io.legado.app.utils.getPrefString
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

/**
 * FnReader 手机解析桥常驻服务
 *
 * 开着的时候一直长轮询 `/api/m/remote/pending`：飞牛那边本机解析不了的书源
 * （要 WebView / 加密接口）会把 search / toc / content 任务派过来，这里用本机书源规则
 * 跑一遍，再把结果 POST 回 `/api/m/remote/result`。
 */
class FnReaderBridgeService : BaseService() {

    companion object {
        /** 单个任务上限；飞牛 /api/remote/job 最长只等 60 秒 */
        private const val JOB_TIMEOUT_MS = 45000L
        private const val TIMEOUT_MESSAGE = "解析超时（45 秒没出结果），这个书源本机也跑不动"

        @Volatile
        var isRun = false
            private set

        fun start(context: Context = appCtx) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FnReaderBridgeService::class.java)
            )
        }

        fun stop(context: Context = appCtx) {
            context.stopService(Intent(context, FnReaderBridgeService::class.java))
        }
    }

    private var loopJob: Job? = null
    private var notificationContent = "手机解析待命：等待飞牛派任务"
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentTitle("FnReader 手机解析")
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭",
                servicePendingIntent<FnReaderBridgeService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentAction.stop) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun upNotification() {
        notificationBuilder.setContentText(notificationContent)
        startForeground(NotificationId.FnReaderBridgeService, notificationBuilder.build())
    }

    override fun onDestroy() {
        isRun = false
        loopJob?.cancel()
        loopJob = null
        super.onDestroy()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastError = ""
            while (isActive) {
                val base = FnReaderRemote.normalizeBase(
                    getPrefString(FnReaderConfigDialog.KEY_SERVER, "").orEmpty()
                )
                val token = getPrefString(FnReaderConfigDialog.KEY_TOKEN, "").orEmpty()
                if (base.isEmpty() || token.isEmpty()) {
                    notificationContent = "还没填服务器地址或令牌，去「我的 → 接入 FnReader 服务器」里填"
                    upNotification()
                    delay(5000)
                    continue
                }
                val jobs = try {
                    FnReaderRemote.poll(base, token, waitMs = 15000, limit = 5)
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: e.javaClass.simpleName
                    if (msg != lastError) {
                        lastError = msg
                        notificationContent = "连接失败：$msg（重试中）"
                        upNotification()
                    }
                    delay(3000)
                    continue
                }
                lastError = ""
                // 每个任务单独一根协程：碰到又慢又卡的书源（要 WebView 之类）
                // 不会把整条待命堵死，其它任务照样跑。
                for (job in jobs) {
                    launch { runJob(base, token, job) }
                }
                delay(1000)
            }
        }
    }

    /**
     * 跑一个任务并回传。单个任务最多等 [JOB_TIMEOUT_MS]，卡住的书源直接判超时，
     * 别让飞牛那边一直干等到 504。
     */
    private suspend fun runJob(base: String, token: String, job: FnReaderRemote.Job) {
        notificationContent = "正在解析：${job.type}"
        upNotification()
        val result = runCatching {
            withTimeoutOrNull(JOB_TIMEOUT_MS) { FnReaderRemote.execute(job) }
                ?: throw FnReaderRemote.RemoteException(TIMEOUT_MESSAGE)
        }
        val ok = result.isSuccess
        val error = result.exceptionOrNull()?.let {
            if (it is FnReaderRemote.RemoteException) it.localizedMessage
            else (it.localizedMessage ?: it.javaClass.simpleName)
        } ?: "解析失败"
        runCatching {
            FnReaderRemote.submit(
                base = base,
                token = token,
                id = job.id,
                ok = ok,
                data = result.getOrNull(),
                error = if (ok) null else error
            )
        }
        notificationContent = if (ok) {
            val tag = job.payload.optString("key").ifEmpty {
                job.payload.optString("bookUrl").ifEmpty { job.payload.optString("chapterUrl") }
            }
            "已回传 ${job.type} 结果（$tag）"
        } else {
            "解析失败：$error"
        }
        upNotification()
    }

}

