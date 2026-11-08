package io.legado.app.ui.config

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.DialogFnreaderConfigBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.FnReaderRemote
import io.legado.app.service.FnReaderBridgeService
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import splitties.views.onClick
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接入 FnReader（飞牛 fnOS）服务器
 *
 * 设计对齐 FnReader 的「App 同步接口」：
 * 一次 `GET /api/m/sync` 就能拿到书架、进度、书源概况，所以这个入口以同步为核心，
 * 把导入书源、回传书架与进度、上传缓存、书源双向同步、网页书架、接入手册收在同一个菜单里。
 */
class FnReaderConfigDialog : BaseDialogFragment(R.layout.dialog_fnreader_config) {

    companion object {
        const val KEY_PREF = "fnReaderServerConfig"
        const val KEY_SERVER = "fnReaderServer"
        const val KEY_TOKEN = "fnReaderToken"

        /** 手机解析待命开关（1 = 开），服务常驻时飞牛可以把跑不动的书源派过来 */
        const val KEY_BRIDGE = "fnReaderBridgeOn"

        /** 最近一次心跳拿到的服务器版本号，空 = 当前离线 */
        const val KEY_ONLINE = "fnReaderOnlineVersion"
        private const val DOC_SYNC = "https://github.com/TZYLXS/FnReader/blob/main/docs/APP同步接口.md"
        private const val DOC_APK = "https://github.com/TZYLXS/FnReader/blob/main/docs/APK接入.md"
        private const val DOC_PHONE = "https://github.com/TZYLXS/FnReader/blob/main/docs/手机接入API.md"
        private const val DOC_README = "https://github.com/TZYLXS/FnReader/blob/main/README.md"

        /** 推书单批上限（文档 14.5 建议 ≤ 20 本，带 base64 封面时更要收敛） */
        private const val PUSH_BATCH = 15

        /** 封面 base64 的内联上限，超过就不带图（服务端单张上限 8 MB） */
        private const val MAX_COVER_BYTES = 3L * 1024 * 1024

        /** 书源双向同步的游标（服务端 serverTime，毫秒），下次按它算增量 */
        const val KEY_SOURCES_SINCE = "fnReaderSourcesSinceMs"

        /** 缓存上传单次请求体上限（文档 §16.5：服务端 32 MB，留一点余量） */
        private const val MAX_CACHE_REQUEST = 30L * 1024 * 1024

        /** 章节图片单张内联上限，超过就跳过（避免一本漫画把请求体撑爆） */
        private const val MAX_INLINE_IMAGE = 2L * 1024 * 1024
    }

    private val binding by viewBinding(DialogFnreaderConfigBinding::bind)

    /** 探测成功后记住可用的地址，后续各菜单直接复用，避免每次再试一遍 */
    private var resolvedBase: String? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.editServer.setText(getPrefString(KEY_SERVER, ""))
        binding.editToken.setText(getPrefString(KEY_TOKEN, ""))
        binding.tvSave.onClick {
            savePrefs()
            toastOnUi("已保存服务器地址与令牌")
        }
        binding.tvTest.onClick { syncFromServer(silent = false) }
        binding.tvSync.onClick { syncFromServer(silent = false) }
        binding.tvCancel.onClick { dismiss() }
        binding.tvStatus.text = if (bridgeOn()) {
            val online = getPrefString(KEY_ONLINE, "").orEmpty()
            if (online.isNotEmpty()) {
                "已连接 FnReader $online（保活中）\n本机已登记到网页「设置 → 软总线」，飞牛跑不了的书源会派过来"
            } else {
                "手机解析待命已开启，正在连接服务器…"
            }
        } else {
            getString(R.string.fnreader_sync_idle)
        }
        buildMethods()
        // 开关还开着的话，把服务拉起来（进程被杀后重进设置也能恢复待命）
        if (bridgeOn() && !FnReaderBridgeService.isRun) {
            runCatching { FnReaderBridgeService.start(requireContext()) }
        }
    }

    private fun savePrefs() {
        putPrefString(KEY_SERVER, binding.editServer.text?.toString()?.trim().orEmpty())
        putPrefString(KEY_TOKEN, binding.editToken.text?.toString()?.trim().orEmpty())
    }

    private fun buildMethods() {
        binding.listMethods.removeAllViews()
        addMethod(
            "① 导入书源（推荐的接法）",
            "下载 /api/m/sources.json（Legado 数组格式）直接导入，导入后搜索、目录、正文都走飞牛"
        ) { importSources() }
        addMethod(
            "② 全部同步到飞牛书架（新书自动建、重名跳过）",
            "本机书架上每本书都推给飞牛：飞牛没有的书自动建进书架（POST /api/m/books，带封面与简介），"
                    + "再把章节与位置回传（POST /api/m/progress/batch）；书名相同的不重复添加，"
                    + "封面一并回传（本机缓存图转 base64 落到 NAS 的 covers/），飞牛那边缺封面的会顺手补上"
                    + "（回传字段见 docs《app回传接受规范》）"
        ) { uploadProgress() }
        addMethod(
            "③ 打开飞牛阅读网页",
            "用 App 内置浏览器打开 FnReader 网页书架"
        ) { openWebShelf() }
        addMethod(
            "④ 接入手册",
            "同步接口、APK 接入、手机接入 API 三份文档都在这里"
        ) { showDocs() }
        addMethod(
            if (bridgeOn()) "⑤ 手机解析待命：已开启（点此关闭）"
            else "⑤ 手机解析待命：已关闭（点此开启）",
            "飞牛跑不了的书源（要 WebView / 加密接口）派给本机解析：开了之后保持后台常驻，"
                    + "网页搜索命中这类书源时由本机执行 search / toc / content 并回传结果；"
                    + "领任务时会把本机登记到网页「设置 → 软总线」，可以在那边单独暂停这台"
        ) { toggleBridge() }
        addMethod(
            "⑥ 接入书源桥（一个「飞牛书库」源读完所有）",
            "导入服务端自动生成的单品书源：搜索 / 详情 / 目录 / 正文全部走 /api/m/legado/*，"
                    + "本机不解析、不装书源规则也能看书；换服务器地址后重新导入一次即可"
                    + "（源规则里带着令牌，公网访问请用 https）"
        ) { importBridge() }
        addMethod(
            "⑦ 查看软总线（哪台设备在代理解析）",
            "GET /api/bus：列出已报到的手机、在线状态、累计代理解析次数；"
                    + "要暂停某一台，在飞牛网页「设置 → 软总线」里点一下就行"
        ) { showBus() }
        addMethod(
            "⑧ 上传本机缓存到飞牛（网页离线读正文）",
            "把已经缓存过的章节原文（book_cache 里的 .nb 文件）连同章节图片、封面原样推给飞牛"
                    + "（POST /api/m/cache，文档 §16），NAS 自己转码成段落文本存进 cache/<书名>/，"
                    + "之后网页阅读直接读本地文件、不再回头访问书源；整本一次上传，超过 30 MB 的本子跳过"
        ) { uploadCache() }
        addMethod(
            "⑨ 书源双向同步（最后一次修改为准）",
            "按 lastUpdateTime 双向对齐（GET/POST /api/m/sources/sync，文档 §17）：本机改过的书源推到飞牛，"
                    + "飞牛更进的拉回本地，删除也同步；首次按 bookSourceUrl 合并上传，之后只传本机改过的那几条"
        ) { syncSourcesBoth() }
    }

    private fun bridgeOn(): Boolean = getPrefString(KEY_BRIDGE, "0") == "1"

    /**
     * 手机解析待命开关。开启前先把可用地址定下来（探一次 /api/m/info 同源探测），
     * 免得服务里拿到的是那个恒回 invalid token 的网页代理地址。
     */
    private fun toggleBridge() {
        val ctx = requireContext()
        if (bridgeOn()) {
            putPrefString(KEY_BRIDGE, "0")
            runCatching { FnReaderBridgeService.stop(ctx) }
            buildMethods()
            binding.tvStatus.text = "手机解析待命已关闭"
            toastOnUi("已关闭手机解析待命")
            return
        }
        val base = serverBase() ?: return
        if (token().isEmpty()) {
            toastOnUi("先填访问令牌（FnReader 设置 → 手机接入 里那个）")
            return
        }
        val openBase = resolvedBase ?: base
        putPrefString(KEY_SERVER, openBase)
        putPrefString(KEY_BRIDGE, "1")
        runCatching { FnReaderBridgeService.start(ctx) }
        buildMethods()
        binding.tvStatus.text = "手机解析待命已开启，服务器：$openBase\n" +
            "领任务时会把本机登记到网页「设置 → 软总线」，可以在那边单独暂停"
        toastOnUi("已开启，回飞牛网页搜书即可借用本机解析")
    }

    private fun addMethod(title: String, desc: String, action: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeStore.textColorPrimary(ctx))
        })
        row.addView(TextView(ctx).apply {
            text = desc
            textSize = 12f
            setTextColor(ThemeStore.textColorSecondary(ctx))
            setPadding(0, dp(2), 0, 0)
        })
        row.setOnClickListener { action() }
        binding.listMethods.addView(row)
    }

    private fun dp(value: Int): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()

    // ---------------- 地址与令牌 ----------------

    private fun rawServer(): String = binding.editServer.text?.toString()?.trim().orEmpty()

    private fun token(): String = binding.editToken.text?.toString()?.trim().orEmpty()

    /**
     * 规范化服务器地址：缺协议补 http://；只保留用户填的端口与路径，不擅自拼 /app/legado-web
     * （FnReader 1.7.31.1 起接入地址就是「局域网IP:1122」直连，网页代理的 5666 不再推荐）
     *
     * 公网 / 域名用 http 时令牌是明文（文档「外网访问 → 安全要点」），这里只提示、不拦。
     */
    private fun serverBase(): String? {
        var s = rawServer()
        if (s.isEmpty()) {
            toastOnUi("先填服务器地址，例如 http://192.168.5.110:1122")
            return null
        }
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        runCatching {
            val uri = Uri.parse(s)
            val scheme = uri.scheme.orEmpty().lowercase()
            val host = uri.host.orEmpty()
            if (scheme == "http" && !isLanHost(host)) {
                toastOnUi("提醒：这不是内网地址，请用 https，否则令牌在网络上是明文")
            }
        }
        savePrefs()
        return s
    }

    /** 内网 / 本机地址：http 可以接受；其余（域名、公网 IP）建议走 https */
    private fun isLanHost(host: String): Boolean {
        if (host.isEmpty() || host == "localhost") return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() } 
        if (nums.size != 4 || nums.any { it !in 0..255 }) return false
        return nums[0] == 10 || nums[0] == 127 ||
                (nums[0] == 192 && nums[1] == 168) ||
                (nums[0] == 172 && nums[1] in 16..31)
    }

    /**
     * 依次尝试的地址：用户填的 → 同主机的 1122（FnReader 默认端口）→ 同主机 + /app/legado-web
     * 只填了 5666 的网页代理地址也能连上，因为会自动退化到 1122 去探。
     */
    private fun candidateBases(): List<String> {
        val base = serverBase() ?: return emptyList()
        val list = arrayListOf(base)
        runCatching {
            val uri = Uri.parse(base)
            val host = uri.host ?: return@runCatching
            val scheme = uri.scheme ?: "http"
            val port = if (uri.port > 0) uri.port else 80
            if (port != 1122) list.add("$scheme://$host:1122")
            val hasAppPath = base.contains("/app/", true)
            if (!hasAppPath) list.add("$base/app/legado-web")
        }
        return list
    }

    private fun requireServerAndToken(needToken: Boolean = true): String? {
        val bases = candidateBases()
        if (bases.isEmpty()) return null
        if (needToken && token().isEmpty()) {
            toastOnUi("先填访问令牌（FnReader 设置 → 手机接入 里那个）")
            return null
        }
        return resolvedBase ?: bases.first()
    }

    /** 自己调用的同步接口：令牌走请求头，不落在 URL 里 */
    private fun syncApi(base: String): String =
        "$base/api/m/sync?full=0"

    /** 书源数组（文档 2）：与 Legado 导出格式一致的数组，可直接导入 */
    private fun sourcesUrl(base: String): String =
        "$base/api/m/sources.json?all=1&token=${Uri.encode(token())}"

    // ---------------- 同步 ----------------

    private class SyncOutcome(
        val okBase: String?,
        val code: Int,
        val body: String,
        val triedUrl: String,
        val merged: String?
    )

    private fun syncFromServer(silent: Boolean) {
        val bases = candidateBases()
        if (bases.isEmpty()) return
        if (token().isEmpty()) {
            toastOnUi("先填访问令牌（FnReader 设置 → 手机接入 里那个）")
            return
        }
        // 先给个反馈，避免点下去像没反应
        binding.tvStatus.text = "同步中…（正在连接 ${bases.first()}）"
        execute {
            var lastCode = 0
            var lastBody = ""
            var lastUrl = bases.first()
            var okBase: String? = null
            var merged: String? = null
            for (base in bases) {
                val result = runCatching { request("GET", syncApi(base), token(), null) }.getOrNull()
                if (result == null) {
                    lastUrl = base
                    lastBody = "连接不上"
                    continue
                }
                lastCode = result.first
                lastBody = result.second
                lastUrl = base
                if (result.first in 200..299 && result.second.trim().startsWith("{")) {
                    okBase = base
                    // 书架、进度真正落到本地库（书架上没有的补进去，进度比本地新才覆盖）
                    merged = mergeShelfAndProgress(result.second)
                    break
                }
            }
            SyncOutcome(okBase, lastCode, lastBody, lastUrl, merged)
        }.onSuccess { result ->
            val okBase = result.okBase
            if (okBase != null) {
                resolvedBase = okBase
                binding.editServer.setText(okBase)
                putPrefString(KEY_SERVER, okBase)
                val summary = summaryOf(result.body)
                binding.tvStatus.text = if (result.merged != null) "$summary\n${result.merged}" else summary
                if (!silent) toastOnUi(result.merged ?: "同步完成")
            } else {
                val reason = if (result.code == 401) {
                    "令牌不对或已被重置：FnReader 设置 → 手机接入 里重置后会换新令牌，要重新填一次"
                } else {
                    explain(result.body)
                }
                binding.tvStatus.text = "同步失败：$reason\n试过：${result.triedUrl}"
                if (!silent) toastOnUi("同步失败：$reason")
            }
        }.onError {
            val msg = FnReaderRemote.describeError(it)
            binding.tvStatus.text = "连接失败：$msg"
            toastOnUi("连接失败：$msg")
        }
    }

    /**
     * 把 /api/m/sync 的 shelf 与 progress 合并进本地书架：
     * 书架上没有的书补进去；本地进度比服务器旧才覆盖（服务器时间更大的为准）。
     */
    private fun mergeShelfAndProgress(body: String): String? {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val shelf = obj.optJSONArray("shelf") ?: JSONArray()
        val progress = obj.optJSONArray("progress") ?: JSONArray()

        val remoteProgress = HashMap<String, JSONObject>()
        for (i in 0 until progress.length()) {
            val p = progress.optJSONObject(i) ?: continue
            val url = p.optString("bookUrl")
            if (url.isNotEmpty()) remoteProgress[url] = p
        }

        var added = 0
        var updated = 0
        for (i in 0 until shelf.length()) {
            val s = shelf.optJSONObject(i) ?: continue
            val url = s.optString("bookUrl")
            if (url.isEmpty()) continue
            val shelfTime = s.optLong("durChapterTime", 0L)
            val local = appDb.bookDao.getBook(url)
            if (local == null) {
                val book = Book(
                    bookUrl = url,
                    origin = s.optString("origin"),
                    originName = s.optString("originName")
                )
                book.name = s.optString("name")
                book.author = s.optString("author")
                book.kind = s.optString("kind").ifEmpty { null }
                book.coverUrl = s.optString("coverUrl").ifEmpty { null }
                book.intro = s.optString("intro").ifEmpty { null }
                book.latestChapterTitle = s.optString("lastChapter").ifEmpty { null }
                book.totalChapterNum = s.optInt("totalChapterNum", 0)
                book.latestChapterTime = if (shelfTime > 0) shelfTime else System.currentTimeMillis()
                book.durChapterTitle = s.optString("durChapterTitle").ifEmpty { null }
                book.durChapterIndex = s.optInt("durChapterIndex", 0)
                book.durChapterPos = s.optInt("durChapterPos", 0)
                book.durChapterTime = if (shelfTime > 0) shelfTime else 0L
                book.save()
                added++
                continue
            }
            val p = remoteProgress[url]
            val remoteTime = maxOf(shelfTime, p?.optLong("time", 0L) ?: 0L)
            if (remoteTime > local.durChapterTime) {
                local.durChapterIndex =
                    p?.optInt("index", local.durChapterIndex) ?: s.optInt("durChapterIndex", local.durChapterIndex)
                local.durChapterPos =
                    p?.optInt("pos", local.durChapterPos) ?: s.optInt("durChapterPos", local.durChapterPos)
                val title = p?.optString("title").orEmpty().ifEmpty { s.optString("durChapterTitle") }
                if (title.isNotEmpty()) local.durChapterTitle = title
                local.durChapterTime = remoteTime
                local.save()
                updated++
            }
        }
        // 只在 progress 里出现、书架上没有的书（例如服务器已删书）：不新建，跳过

        return "新增 $added 本　更新 $updated 条进度（本地书架 ${appDb.bookDao.all.size} 本）"
    }

    /**
     * 服务端出错时可能回一段纯文本（如 invalid token），翻成用户能照着做的中文
     */
    private fun explain(body: String): String {
        val plain = body.trim()
        val lower = plain.lowercase()
        return when {
            lower.contains("invalid token") || plain.contains("token 无效") ||
                plain.contains("token无效") || lower.contains("unauthorized") ->
                "令牌不对或已被重置（FnReader 设置 → 手机接入 → 重置后要在 App 里重新填）。" +
                    "顺带确认地址是应用自己的端口：http://IP:1122"
            lower.contains("<html") || plain.startsWith("<") ->
                "地址返回的是网页。这里只填 http://IP:端口（默认 1122），反代的话填 https://域名"
            plain.isEmpty() -> "服务器没有返回内容（可能是端口不对或服务没起来）"
            else -> plain.take(80)
        }
    }

    /** 把 /api/m/sync 的响应压成几行概况 */
    private fun summaryOf(body: String): String = runCatching {
        val obj = JSONObject(body)
        val sources = obj.optJSONObject("sources")
        val time = obj.optLong("serverTime")
        val timeText = if (time > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        } else {
            "-"
        }
        buildString {
            append("已连接 ${obj.optString("app", "FnReader")} ${obj.optString("version", "")}")
            append("　服务器时间 $timeText")
            append("\n书架 ${obj.optInt("shelfCount")} 本")
            append("　进度 ${obj.optInt("progressCount")} 条")
            append("\n书源 ${sources?.optInt("total") ?: 0} 个")
            append("（启用 ${sources?.optInt("enabled") ?: 0}）")
            if (obj.optBoolean("incr")) append("　增量")
        }
    }.getOrElse { "服务器返回的不是同步 JSON：${explain(body)}" }

    // ---------------- 各接入方式 ----------------

    private fun importSources() {
        val base = requireServerAndToken() ?: return
        val url = sourcesUrl(base)
        // OnLineImportActivity 只认 legado://import/bookSource?src=…，直接丢 http 地址会被当成没 src 参数而立刻退出
        val link = "legado://import/bookSource?src=${Uri.encode(url)}"
        startActivity<OnLineImportActivity> { data = Uri.parse(link) }
    }

    /**
     * 书源桥：服务端把书架/书源/缓存打包成一个「飞牛书库」源，
     * 导入后搜索、详情、目录、正文全部由飞牛执行（/api/m/legado 下的三条接口），本机零解析。
     */
    private fun importBridge() {
        val base = requireServerAndToken() ?: return
        val url = "$base/api/m/booksource?token=${Uri.encode(token())}"
        val link = "legado://import/bookSource?src=${Uri.encode(url)}"
        startActivity<OnLineImportActivity> { data = Uri.parse(link) }
        // 不在状态栏里回显带令牌的地址，只显示接口
        binding.tvStatus.text = "正在导入书源桥…\n$base/api/m/booksource（令牌放在请求里）"
    }

    /**
     * 软总线（文档 11）：网页端维护的「解析设备」清单。App 领任务时报 id/name/model/ver
     * 就会出现在这里；这个条目只做展示，暂停某台仍走网页设置。
     */
    private fun showBus() {
        val base = requireServerAndToken(needToken = false) ?: return
        execute {
            request("GET", "$base/api/bus", token(), null)
        }.onSuccess { r ->
            if (r.first !in 200..299) {
                toastOnUi("查不到软总线：HTTP ${r.first}")
                return@onSuccess
            }
            val obj = runCatching { JSONObject(r.second) }.getOrNull()
            val devices = obj?.optJSONArray("devices") ?: JSONArray()
            val text = buildString {
                for (i in 0 until devices.length()) {
                    val d = devices.optJSONObject(i) ?: continue
                    append("· ").append(d.optString("name").ifEmpty { d.optString("id") })
                    append(if (d.optBoolean("online")) "　在线" else "　离线")
                    if (!d.optBoolean("enabled", true)) append("　已暂停")
                    append("　代理解析 ").append(d.optInt("handled")).append(" 次")
                    val ver = d.optString("version")
                    if (ver.isNotEmpty()) append("　v").append(ver)
                    append("\n")
                }
                if (isEmpty()) append("还没有设备报到：打开 ⑤ 手机解析待命后，这里会出现本机")
                val pending = obj?.optInt("pending") ?: 0
                if (pending > 0) append("\n排队中的任务：$pending")
            }
            alert {
                setTitle("软总线")
                setMessage(text.trimEnd())
                okButton()
            }
        }.onError {
            toastOnUi("查不到软总线：${FnReaderRemote.describeError(it)}")
        }
    }

    /**
     * ③ 全部同步到飞牛书架（对齐文档 9 / 13 / 14：先 /api/m/books 把 NAS 没有的书推上去，带封面，再回传进度）
     *
     * - 本机每一本有书名的书都参与同步，不再像 1.4 那样「飞牛没有这本书就跳过」
     * - 飞牛书架上已有同名的书直接跳过，不再往 NAS 里塞第二本同名书
     * - 本机自己被多个源收录的同名书也只留一本
     * - 封面：在线封面给 coverUrl；本机缓存的封面图转 base64 走 coverData，NAS 落盘到 covers/
     */
    private fun uploadProgress() {
        val base = requireServerAndToken() ?: return
        binding.tvStatus.text = "全部同步中…（先比对飞牛书架）"
        execute {
            val (code, body) = request("GET", syncApi(base), token(), null)
            if (code !in 200..299) throw IllegalStateException("读取飞牛书架失败：${explain(body)}")

            val shelf = runCatching { JSONObject(body).optJSONArray("shelf") }.getOrNull() ?: JSONArray()
            val shelfUrls = HashSet<String>()
            val shelfNames = HashSet<String>()
            val shelfCovers = HashMap<String, String>()
            for (i in 0 until shelf.length()) {
                val o = shelf.optJSONObject(i) ?: continue
                val url = o.optString("bookUrl")
                val name = o.optString("name")
                if (url.isNotEmpty()) {
                    shelfUrls.add(url)
                    shelfCovers[url] = o.optString("coverUrl")
                }
                if (name.isNotEmpty()) shelfNames.add(normName(name))
            }

            val local = appDb.bookDao.flowAll().first()
                .filter { it.bookUrl.isNotEmpty() && !it.bookUrl.startsWith("file:") && it.name.isNotBlank() }
            val picked = LinkedHashMap<String, Book>()
            var localDup = 0
            for (book in local) {
                val key = normName(book.name)
                if (picked.containsKey(key)) {
                    localDup++
                    continue
                }
                picked[key] = book
            }

            val toPush = ArrayList<Book>()
            val toProgress = ArrayList<Book>()
            var skipped = 0
            var coverFix = 0
            for ((key, book) in picked) {
                when {
                    shelfUrls.contains(book.bookUrl) -> {
                        toProgress.add(book)
                        // 飞牛书架已有这本书但没有封面：把封面补推一次
                        // （/api/m/books 按 bookUrl 合并，只覆盖传上去的字段；带 coverData 时 NAS 落盘）
                        if (shelfCovers[book.bookUrl].isNullOrEmpty() && hasCover(book)) {
                            toPush.add(book)
                            coverFix++
                        }
                    }
                    shelfNames.contains(key) -> skipped++
                    else -> {
                        toPush.add(book)
                        toProgress.add(book)
                    }
                }
            }

            var added = 0
            var shelfTotal = shelf.length()
            if (toPush.isNotEmpty()) {
                // 文档 14.5：单批 ≤ 20 本；带 base64 封面时请求体会变大，更该分批
                for (batch in toPush.chunked(PUSH_BATCH)) {
                    val arr = JSONArray()
                    batch.forEach { arr.put(pushJson(it)) }
                    val payload = JSONObject().apply { put("books", arr) }.toString()
                    val push = request("POST", "$base/api/m/books", token(), payload)
                    if (push.first !in 200..299) throw IllegalStateException("推书失败：${explain(push.second)}")
                    val obj = runCatching { JSONObject(push.second) }.getOrNull()
                    added += obj?.optInt("added") ?: 0
                    shelfTotal = obj?.optInt("total") ?: (shelf.length() + added)
                }
            }

            val items = JSONArray()
            toProgress.forEach { book ->
                items.put(JSONObject().apply {
                    put("bookUrl", book.bookUrl)
                    put("name", book.name)
                    put("author", book.author)
                    put("index", book.durChapterIndex)
                    put("pos", book.durChapterPos)
                    put("title", book.durChapterTitle ?: "")
                })
            }
            var progressUpdated = items.length()
            var refilled = 0
            if (items.length() > 0) {
                val pr = request(
                    "POST",
                    "$base/api/m/progress/batch",
                    token(),
                    JSONObject().apply { put("items", items) }.toString()
                )
                if (pr.first !in 200..299) throw IllegalStateException("回传进度失败：${explain(pr.second)}")
                val obj = runCatching { JSONObject(pr.second) }.getOrNull()
                progressUpdated = obj?.optInt("updated") ?: items.length()
                // 规范 9：item 里没带 name 且书不存在时会在 missing 里返回 bookUrl；这里兜底补推一次
                val missing = obj?.optJSONArray("missing") ?: JSONArray()
                if (missing.length() > 0) {
                    val byUrl = picked.values.associateBy { it.bookUrl }
                    val retry = ArrayList<Book>()
                    for (i in 0 until missing.length()) {
                        val url = missing.optString(i)
                        byUrl[url]?.let { retry.add(it) }
                    }
                    if (retry.isNotEmpty()) {
                        for (batch in retry.chunked(PUSH_BATCH)) {
                            val arr = JSONArray()
                            batch.forEach { arr.put(pushJson(it)) }
                            val body = JSONObject().apply { put("books", arr) }.toString()
                            val push = request("POST", "$base/api/m/books", token(), body)
                            if (push.first !in 200..299) {
                                throw IllegalStateException("补推飞牛未收下的书失败：${explain(push.second)}")
                            }
                            val pr2 = runCatching { JSONObject(push.second) }.getOrNull()
                            val addNow = pr2?.optInt("added") ?: 0
                            added += addNow
                            shelfTotal += addNow
                            refilled += batch.size
                        }
                    }
                }
            }

            buildString {
                append("全部同步完成：新增 $added 本　更新 $progressUpdated 条进度")
                if (refilled > 0) append("　补推 $refilled 本")
                if (coverFix > 0) append("　补封面 $coverFix 本")
                if (skipped > 0) append("　重名跳过 $skipped 本")
                if (localDup > 0) append("　本机重复 $localDup 本")
                append("\n飞牛书架共 $shelfTotal 本（已发送 ${toProgress.size} 本进度，封面随书回传）")
            }
        }.onSuccess { summary ->
            binding.tvStatus.text = summary
            toastOnUi(summary.replace("\n", "　"))
        }.onError {
            val msg = FnReaderRemote.describeError(it)
            binding.tvStatus.text = "全部同步失败：$msg"
            toastOnUi("全部同步失败：$msg")
        }
    }

    /**
     * ⑧ 上传本机缓存（文档 §16）：把 book_cache 里已缓存的章节原文原样发给 /api/m/cache，
     * NAS 自己转码成段落文本，网页阅读从此读本地文件。
     * 不做任何预处理（文档明确要求 raw 原样），一次一本，超上限的本子跳过。
     */
    private fun uploadCache() {
        val base = requireServerAndToken() ?: return
        binding.tvStatus.text = "上传缓存中…（扫描本机 book_cache）"
        execute {
            val books = appDb.bookDao.flowAll().first()
                .filter { it.bookUrl.isNotEmpty() && !it.bookUrl.startsWith("file:") && it.name.isNotBlank() }
            var pushed = 0
            var chapterCount = 0
            var imageCount = 0
            var bytes = 0L
            var skipped = 0
            var failed = 0
            val detail = StringBuilder()
            for (book in books) {
                val folder = File(BookHelp.cachePath, book.getFolderName())
                if (!folder.isDirectory) continue
                val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".nb", true) }
                    .orEmpty()
                if (files.isEmpty()) continue
                val payload = cacheJson(book, folder, files)
                if (payload == null) {
                    skipped++
                    continue
                }
                val encoded = payload.toString()
                if (encoded.length > MAX_CACHE_REQUEST) {
                    skipped++
                    continue
                }
                val r = runCatching {
                    request("POST", "$base/api/m/cache", token(), encoded, readTimeoutMs = 60000)
                }.getOrNull()
                if (r == null || r.first !in 200..299) {
                    failed++
                    continue
                }
                val o = runCatching { JSONObject(r.second) }.getOrNull()
                if (o == null || !o.optBoolean("ok", true)) {
                    failed++
                    continue
                }
                pushed++
                chapterCount += o.optInt("chapters", files.size)
                imageCount += o.optInt("images", 0)
                bytes += o.optLong("bytes", encoded.toByteArray(Charsets.UTF_8).size.toLong())
                if (detail.length < 240) {
                    detail.append(folder.name)
                        .append("　").append(o.optInt("chapters", files.size)).append(" 章\n")
                }
            }
            buildString {
                append("缓存上传完成：上传 $pushed 本　共 $chapterCount 章　图片 $imageCount 张")
                if (skipped > 0) append("　过大跳过 $skipped 本")
                if (failed > 0) append("　失败 $failed 本")
                append("\n飞牛落盘 ${bytes / 1024} KB，网页阅读已改读本地文件")
                if (pushed == 0) append("\n本机还没有已缓存的章节：先在阅读里下载几章再点这里")
                if (detail.isNotEmpty()) append("\n").append(detail.toString().trimEnd())
            }
        }.onSuccess { summary ->
            binding.tvStatus.text = summary
            toastOnUi(summary.lineSequence().first())
        }.onError {
            val msg = FnReaderRemote.describeError(it)
            binding.tvStatus.text = "上传缓存失败：$msg"
            toastOnUi("上传缓存失败：$msg")
        }
    }

    /** 一本书的 /api/m/cache 载荷：章节原文 + 图片 + 封面（字段见文档 §16.1） */
    private fun cacheJson(book: Book, folder: File, files: Array<out File>): JSONObject? {
        val chapters = JSONArray()
        for (f in files) {
            val index = f.name.take(5).toIntOrNull() ?: continue
            val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            chapters.put(JSONObject().apply {
                put("index", index)
                put("title", appDb.bookChapterDao.getChapter(book.bookUrl, index)?.title ?: "")
                put("file", f.name)
                put("raw", raw)
            })
        }
        if (chapters.length() == 0) return null
        val images = JSONArray()
        val imgDir = File(folder, "images")
        if (imgDir.isDirectory) {
            imgDir.listFiles()?.filter { it.isFile && it.length() in 1L..MAX_INLINE_IMAGE }
                ?.forEach { img ->
                    val data = runCatching { img.readBytes() }.getOrNull() ?: return@forEach
                    images.put(JSONObject().apply {
                        put("name", img.name)
                        put("data", Base64.encodeToString(data, Base64.NO_WRAP))
                    })
                }
        }
        return JSONObject().apply {
            put("layout", "legado")
            put("book", JSONObject().apply {
                put("bookUrl", book.bookUrl)
                put("name", book.name)
                put("author", book.author)
                put("origin", book.origin)
                put("originName", book.originName)
                book.intro?.takeIf { it.isNotEmpty() }?.let { put("intro", it.take(500)) }
                put("durChapterIndex", book.durChapterIndex)
            })
            localCover(book)?.let { (mime, data) ->
                put("coverMime", mime)
                put("coverData", data)
            }
            put("chapters", chapters)
            if (images.length() > 0) put("images", images)
        }
    }

    /**
     * ⑨ 书源双向同步（文档 §17，lastUpdateTime 大的一方生效）。
     *
     * 首次（本地没记过 serverTime）：走一次性接口 POST /api/m/sources 按 bookSourceUrl 合并上传，
     * 只把服务端 serverTime 记成下次的 since。**不能**把「本机全量 + since=0」推给
     * /api/m/sources/sync —— 服务端会把这种全量推送当成整库快照，删掉本机没有的源（实测丢过 258 条）。
     * 之后每次：本地有改动（lastUpdateTime > since）就 POST 增量，服务端更进的版本会进 pull 回来；
     * 本地没改动就只 GET 增量，把服务端新增/修改的源落回本地。
     */
    private fun syncSourcesBoth() {
        val base = requireServerAndToken() ?: return
        binding.tvStatus.text = "书源双向同步中…（读取本地书源）"
        execute {
            val since = getPrefString(KEY_SOURCES_SINCE, "0")?.toLongOrNull() ?: 0L
            val parts = appDb.bookSourceDao.flowAll().first()
            var pushed = 0
            var applied = 0
            var skipped = 0
            var pulled = 0
            var removed = 0
            var total = 0
            var truncated = false
            val first = since <= 0L

            if (first) {
                // 首次：本机上千条源按 URL 合并上传（§17.5 + 13.2），不做整库替换
                val all = ArrayList<BookSource>()
                for (p in parts) {
                    appDb.bookSourceDao.getBookSource(p.bookSourceUrl)?.let { all.add(it) }
                }
                if (all.isNotEmpty()) {
                    val body = JSONArray().also { arr ->
                        all.forEach { arr.put(JSONObject(GSON.toJson(it))) }
                    }.toString()
                    val r = request("POST", "$base/api/m/sources", token(), body, readTimeoutMs = 180000)
                    if (r.first !in 200..299) throw IllegalStateException(explain(r.second))
                    val obj = JSONObject(r.second)
                    pushed = all.size
                    applied = obj.optInt("added")
                    skipped = obj.optInt("updated")
                    total = obj.optInt("total", total)
                }
                // 只为拿 serverTime 当下次的 since：since=现在，服务端不会回全量
                val stamp = request("GET", "$base/api/m/sources/sync?since=${System.currentTimeMillis()}", token(), null)
                if (stamp.first in 200..299) {
                    val obj = JSONObject(stamp.second)
                    total = obj.optInt("total", total)
                    putPrefString(
                        KEY_SOURCES_SINCE,
                        obj.optLong("serverTime", System.currentTimeMillis()).toString()
                    )
                } else {
                    putPrefString(KEY_SOURCES_SINCE, System.currentTimeMillis().toString())
                }
            } else {
                val changed = ArrayList<BookSource>()
                for (p in parts) {
                    if (p.lastUpdateTime > since) {
                        appDb.bookSourceDao.getBookSource(p.bookSourceUrl)?.let { changed.add(it) }
                    }
                }
                if (changed.isNotEmpty()) {
                    val payload = JSONObject().apply {
                        put("since", since)
                        put("updated", JSONArray().also { arr ->
                            changed.forEach { arr.put(JSONObject(GSON.toJson(it))) }
                        })
                        put("deleted", JSONArray())
                    }.toString()
                    val r = request("POST", "$base/api/m/sources/sync", token(), payload, readTimeoutMs = 180000)
                    if (r.first !in 200..299) throw IllegalStateException(explain(r.second))
                    val obj = JSONObject(r.second)
                    pushed = changed.size
                    applied = obj.optInt("applied")
                    skipped = obj.optInt("skipped")
                    pulled += mergePull(obj.optJSONArray("pull"))
                    removed += applyPullDeleted(obj.optJSONArray("pullDeleted"))
                    total = obj.optInt("total", total)
                    truncated = obj.optBoolean("truncated")
                    putPrefString(
                        KEY_SOURCES_SINCE,
                        obj.optLong("serverTime", System.currentTimeMillis()).toString()
                    )
                } else {
                    val r = request("GET", "$base/api/m/sources/sync?since=$since", token(), null, 120000)
                    if (r.first !in 200..299) throw IllegalStateException(explain(r.second))
                    val obj = JSONObject(r.second)
                    pulled += mergePull(obj.optJSONArray("sources"))
                    total = obj.optInt("total", total)
                    truncated = obj.optBoolean("truncated")
                    putPrefString(
                        KEY_SOURCES_SINCE,
                        obj.optLong("serverTime", System.currentTimeMillis()).toString()
                    )
                }
            }

            buildString {
                if (first) {
                    append("书源同步完成（首次接入）：合并上传 $pushed 个（新增 $applied　更新 $skipped）")
                } else {
                    append("书源同步完成：推送 $pushed 个（采纳 $applied　跳过 $skipped）　拉取 $pulled 个")
                }
                if (removed > 0) append("　删除 $removed 个")
                append("\n飞牛书源 $total 个　本地 ${appDb.bookSourceDao.allCount()} 个")
                if (truncated) append("\n服务端返回被截断，再点一次继续")
            }
        }.onSuccess { summary ->
            binding.tvStatus.text = summary
            toastOnUi(summary.lineSequence().first())
        }.onError {
            val msg = FnReaderRemote.describeError(it)
            binding.tvStatus.text = "书源同步失败：$msg"
            toastOnUi("书源同步失败：$msg")
        }
    }

    /** 把服务端更进的源按 bookSourceUrl 覆盖回本地（§17.1 / §17.2 的 pull），返回写入条数 */
    private suspend fun mergePull(arr: JSONArray?): Int {
        if (arr == null) return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val src = runCatching { GSON.fromJson(o.toString(), BookSource::class.java) }
                .getOrNull() ?: continue
            if (src.bookSourceUrl.isEmpty()) continue
            appDb.bookSourceDao.insert(src)
            n++
        }
        return n
    }

    /** 服务端删掉的源同步删本地（pullDeleted），返回删除条数 */
    private suspend fun applyPullDeleted(arr: JSONArray?): Int {
        if (arr == null) return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("bookSourceUrl")
            if (url.isEmpty()) continue
            appDb.bookSourceDao.delete(url)
            n++
        }
        return n
    }

/** 书名归一化：去书名号、空白（含全角）、大小写，用于「重名跳过」判定 */
    private fun normName(name: String): String =
        name.replace("《", "").replace("》", "")
            .replace("\u3000", "")
            .replace(Regex("\\s+"), "")
            .lowercase()

    /**
     * 只有 http(s) 封面飞牛那边能拉到（或至少能当 URL 存着）；
     * 本机生成的封面是图片缓存 / file: 路径，这种要走 base64 上传，不能当 URL 发。
     */
    private fun remoteCover(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /** 本机有封面吗（在线 URL 或本地图片文件都算） */
    private fun hasCover(book: Book): Boolean =
        remoteCover(book.coverUrl) != null || localCover(book) != null

    /**
     * 读本机封面文件，返回 mime 与 base64。阅读把封面缓存在应用私有目录，
     * 这里直接读文件；超过上限或读不出来就放弃（服务端单张上限 8 MB）。
     */
    private fun localCover(book: Book): Pair<String, String>? {
        val url = book.coverUrl?.trim().orEmpty()
        if (url.isEmpty() || remoteCover(url) != null || url.startsWith("content://", true)) return null
        val file = File(url.removePrefix("file://"))
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_COVER_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = coverMimeOf(bytes) ?: return null
        return mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun coverMimeOf(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    /** 推给 /api/m/books 的一本书：在线封面给 coverUrl，本地封面给 coverData（文档 14.2 / 回传规范 §1.3） */
    private fun pushJson(book: Book): JSONObject = JSONObject().apply {
        put("bookUrl", book.bookUrl)
        put("name", book.name)
        put("author", book.author)
        remoteCover(book.coverUrl)?.let { put("coverUrl", it) }
        localCover(book)?.let { (mime, data) ->
            put("coverMime", mime)
            put("coverData", data)
        }
        book.intro?.takeIf { it.isNotEmpty() }?.let { put("intro", it.take(500)) }
        put("originName", "手机端")
        if (book.totalChapterNum > 0) put("totalChapterNum", book.totalChapterNum)
        put("index", book.durChapterIndex)
        book.durChapterTitle?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
    }

    private fun openWebShelf() {
        val base = requireServerAndToken(needToken = false) ?: return
        startActivity<WebViewActivity> {
            putExtra("url", base)
            putExtra("title", "FnReader")
        }
    }

    private fun showDocs() {
        val docs = arrayListOf(
            "App 同步接口（sync/sources.json/进度）",
            "APK 接入（书源导入与接口对应）",
            "手机接入 API（/api/m/* 全表）",
            "README（总体说明）"
        )
        val urls = arrayListOf(DOC_SYNC, DOC_APK, DOC_PHONE, DOC_README)
        requireContext().selector(docs) { _, index ->
            openInBrowser(urls[index])
        }
    }

    private fun openInBrowser(url: String) {
        try {
            requireContext().openUrl(Uri.parse(url))
        } catch (e: Exception) {
            toastOnUi("没有可用的浏览器：${e.localizedMessage}")
        }
    }

    // ---------------- 网络请求 ----------------

    /** @return HTTP 状态码 to 响应文本（前 400 字用于提示） */
    private fun request(
        method: String,
        url: String,
        token: String,
        jsonBody: String?,
        readTimeoutMs: Int = 12000
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 6000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-Api-Token", token)
            conn.setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                (conn.outputStream as OutputStream).use { out ->
                    out.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn?.disconnect()
        }
    }

}
