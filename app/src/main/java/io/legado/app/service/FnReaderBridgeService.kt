package io.legado.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.model.FnReaderAutoSync
import io.legado.app.model.FnReaderRemote
import io.legado.app.ui.config.FnReaderConfigDialog
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
        /**
         * 任务上限跟服务端的等待时间对齐（FnReader 1.8.x：搜索/目录 20 秒、正文 25 秒
         * 等不到就自己退回本机解析）。这里留一点余量后主动判超时，
         * 免得飞牛那边早就放弃了，手机还在啃一个啃不动的源。
         */
        private const val JOB_TIMEOUT_SEARCH = 18000L
        private const val JOB_TIMEOUT_TOC = 18000L
        private const val JOB_TIMEOUT_CONTENT = 23000L
        /**
         * 简介补全（bookinfo）服务端只等 10 秒（见 docs/APP同步接口.md 18.2「超时 10 秒」），
         * 手机这边必须比它早放手，否则飞牛早就按原样返回了，我们还在啃一个啃不动的详情页。
         */
        private const val JOB_TIMEOUT_BOOKINFO = 9000L
        private const val JOB_TIMEOUT_DEFAULT = 20000L

        /** 检索/目录/正文队列并发数：太大会把站点打到限流，太小又会被慢源拖住 */
        private const val SEARCH_CONCURRENCY = 2

        /** 书源同步 / 缓存上传的自动节拍：5 分钟一轮，连上就跑 */
        private const val AUTO_SYNC_INTERVAL = 5 * 60 * 1000L

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

        private fun timeoutOf(type: String): Long = when (type) {
            "search" -> JOB_TIMEOUT_SEARCH
            "toc" -> JOB_TIMEOUT_TOC
            "content" -> JOB_TIMEOUT_CONTENT
            "rank" -> JOB_TIMEOUT_CONTENT
            "bookinfo" -> JOB_TIMEOUT_BOOKINFO
            else -> JOB_TIMEOUT_DEFAULT
        }
    }

    /** 软总线设备身份：领任务时一起报上去，网页「设置 → 软总线」里就能看到这台机器 */
    private val deviceId: String by lazy { AppConst.androidId }
    private val deviceName: String by lazy { Build.MODEL.ifEmpty { "Android 手机" } }
    private val deviceModel: String by lazy {
        val maker = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        if (maker.isEmpty() || model.startsWith(maker, true)) model else "$maker $model"
    }
    private val appVersion: String by lazy { AppConst.appInfo.versionName }

    private var loopJob: Job? = null

    /** 一条待办：领回来的任务，连同它回来时用的连接上下文 */
    private class QueuedJob(
        val base: String,
        val token: String,
        val job: FnReaderRemote.Job,
        val onlineText: String
    )

    /**
     * 软总线上跑两条队列，互不挤占：
     * - 检索队列（search / toc / content / rank）：并发 2，某个慢源卡住只堵这一条；
     * - 简介队列（只装 bookinfo，补简介与封面）：单并发，永远不排在检索任务后面。
     */
    private val searchQueue = Channel<QueuedJob>(Channel.UNLIMITED)
    private val infoQueue = Channel<QueuedJob>(Channel.UNLIMITED)
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
            var onlineText = ""
            var lastInfoAt = 0L
            var lastAutoSyncAt = 0L
            // 两条队列各自的常驻工作者：检索 2 并发，简介单并发
            repeat(SEARCH_CONCURRENCY) {
                launch {
                    for (item in searchQueue) {
                        runJob(item.base, item.token, item.job, item.onlineText)
                    }
                }
            }
            launch {
                for (item in infoQueue) {
                    runJob(item.base, item.token, item.job, item.onlineText)
                }
            }
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
                // 保活心跳：连上之后每 60 秒探一次 /api/m/info，
                // 一来确认令牌没被重置、地址没变，二来让「在线」是持续状态而不是开局一次性。
                if (System.currentTimeMillis() - lastInfoAt > 60_000L) {
                    val heartbeat = runCatching { FnReaderRemote.info(base, token) }
                    lastInfoAt = System.currentTimeMillis()
                    if (heartbeat.isSuccess) {
                        val version = heartbeat.getOrThrow()
                        putPrefString(FnReaderConfigDialog.KEY_ONLINE, version)
                        onlineText = "已连接 FnReader $version　待命中"
                    } else {
                        putPrefString(FnReaderConfigDialog.KEY_ONLINE, "")
                        onlineText = "连不上服务器：${FnReaderRemote.describeError(heartbeat.exceptionOrNull()!!)}"
                        notificationContent = "$onlineText（$base）"
                        upNotification()
                    }
                }
                // ⑧⑨ 不再让人手点：连上服务端后每 5 分钟自动跑一次书源同步 + 缓存上传；
                // 服务端没这两个接口时各自跳过，不会一直报错。
                if (System.currentTimeMillis() - lastAutoSyncAt > AUTO_SYNC_INTERVAL) {
                    lastAutoSyncAt = System.currentTimeMillis()
                    launch {
                        val r = runCatching { FnReaderAutoSync.run(base, token) }.getOrNull()
                        val summary = r?.summary().orEmpty()
                        if (summary.isNotEmpty()) {
                            notificationContent = summary
                            upNotification()
                        }
                    }
                }
                val jobs = try {
                    FnReaderRemote.poll(
                        base = base,
                        token = token,
                        waitMs = 15000,
                        limit = 5,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceModel = deviceModel,
                        appVer = appVersion
                    )
                } catch (e: Exception) {
                    val msg = FnReaderRemote.describeError(e)
                    if (msg != lastError) {
                        lastError = msg
                        notificationContent = "连接失败：$msg（重试中）"
                        upNotification()
                    }
                    delay(3000)
                    continue
                }
                lastError = ""
                if (jobs.isEmpty()) {
                    notificationContent = onlineText.ifEmpty { "已连接　待命中" }
                    upNotification()
                }
                // 任务按类型分进两条队列：bookinfo（简介/封面）走独立队列，
                // 不和搜索抢并发；其余统一进检索队列，由 2 个工作者轮流跑。
                for (job in jobs) {
                    val item = QueuedJob(base, token, job, onlineText)
                    if (job.type == "bookinfo") {
                        infoQueue.trySend(item)
                    } else {
                        searchQueue.trySend(item)
                    }
                }
                delay(1000)
            }
        }
    }

    /**
     * 跑一个任务并回传。按任务类型定超时（搜索/目录 18 秒、正文/榜单 23 秒），
     * 卡住的书源直接判超时，别让飞牛那边一直干等到超时。
     */
    private suspend fun runJob(
        base: String,
        token: String,
        job: FnReaderRemote.Job,
        onlineText: String
    ) {
        notificationContent = "正在解析：${job.type}"
        upNotification()
        val timeout = timeoutOf(job.type)
        val timeoutMessage = "解析超时（${timeout / 1000} 秒没出结果），这个书源本机也跑不动"
        val result = runCatching {
            withTimeoutOrNull(timeout) { FnReaderRemote.execute(job) }
                ?: throw FnReaderRemote.RemoteException(timeoutMessage)
        }
        val ok = result.isSuccess
        val error = result.exceptionOrNull()?.let { FnReaderRemote.describeError(it) } ?: "解析失败"
        runCatching {
            FnReaderRemote.submit(
                base = base,
                token = token,
                id = job.id,
                ok = ok,
                data = result.getOrNull(),
                error = if (ok) null else error,
                deviceId = deviceId
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
        // 干完活回到「在线待命」
        delay(1200)
        notificationContent = onlineText.ifEmpty { "已连接　待命中" }
        upNotification()
    }

}

