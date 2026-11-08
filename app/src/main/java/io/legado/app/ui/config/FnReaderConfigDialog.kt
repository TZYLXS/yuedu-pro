package io.legado.app.ui.config

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogFnreaderConfigBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.FnReaderBridgeService
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.browser.WebViewActivity
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
 * 把导入书源、回传进度、WebDAV、HTTP API、网页书架、接入手册收在同一个菜单里。
 */
class FnReaderConfigDialog : BaseDialogFragment(R.layout.dialog_fnreader_config) {

    companion object {
        const val KEY_PREF = "fnReaderServerConfig"
        const val KEY_SERVER = "fnReaderServer"
        const val KEY_TOKEN = "fnReaderToken"

        /** 手机解析待命开关（1 = 开），服务常驻时飞牛可以把跑不动的书源派过来 */
        const val KEY_BRIDGE = "fnReaderBridgeOn"
        private const val DOC_SYNC = "https://github.com/TZYLXS/FnReader/blob/main/docs/APP同步接口.md"
        private const val DOC_APK = "https://github.com/TZYLXS/FnReader/blob/main/docs/APK接入.md"
        private const val DOC_PHONE = "https://github.com/TZYLXS/FnReader/blob/main/docs/手机接入API.md"
        private const val DOC_README = "https://github.com/TZYLXS/FnReader/blob/main/README.md"
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
        binding.tvStatus.text = getString(R.string.fnreader_sync_idle)
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
            "② 复制同步接口地址",
            "GET /api/m/sync?token=…，一次拿到书架、进度、书源概况，可给别的客户端用"
        ) { copyText(syncUrl(serverBase() ?: return@addMethod), "已复制同步接口地址") }
        addMethod(
            "③ 回传阅读进度到飞牛",
            "把本机书架上每本书的章节与位置 POST 到 /api/m/progress/batch，不在飞牛书架的书会被跳过"
        ) { uploadProgress() }
        addMethod(
            "④ WebDAV（备份与恢复）",
            "把书架备份挂到飞牛 /dav/backup/；/dav/books/ 可浏览已下载的 txt"
        ) { showWebDav() }
        addMethod(
            "⑤ HTTP API（自建客户端）",
            "查看 /api/m/* 接口表与令牌，含 sync / shelf / toc / content / progress"
        ) { showHttpApi() }
        addMethod(
            "⑥ 打开飞牛阅读网页",
            "用 App 内置浏览器打开 FnReader 网页书架"
        ) { openWebShelf() }
        addMethod(
            "⑦ 接入手册",
            "同步接口、APK 接入、手机接入 API 三份文档都在这里"
        ) { showDocs() }
        addMethod(
            if (bridgeOn()) "⑧ 手机解析待命：已开启（点此关闭）"
            else "⑧ 手机解析待命：已关闭（点此开启）",
            "飞牛跑不了的书源（要 WebView / 加密接口）派给本机解析：开了之后保持后台常驻，"
                    + "网页搜索命中这类书源时由本机执行 search / toc / content 并回传结果"
        ) { toggleBridge() }
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
        binding.tvStatus.text = "手机解析待命已开启，服务器：$openBase\n飞牛遇到本机解析不了的书源会派过来"
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
     * （FnReader 的手机 API 走它自己监听的 1122，网页代理的 /app/legado-web 前缀对 API 不适用）
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
        savePrefs()
        return s
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

    /** 同步接口（文档 1）：一次拿到书架、进度、书源概况 */
    private fun syncUrl(base: String): String =
        "$base/api/m/sync?token=${Uri.encode(token())}"

    /** 书源数组（文档 2）：与 Legado 导出格式一致的数组，可直接导入 */
    private fun sourcesUrl(base: String): String =
        "$base/api/m/sources.json?all=1&token=${Uri.encode(token())}"

    private fun davUrl(base: String): String {
        val uri = Uri.parse(base)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: base
        return "$scheme://$host:1122/dav/"
    }

    private fun copyText(text: String, tip: String) {
        requireContext().sendToClip(text)
        toastOnUi(tip)
    }

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
                val result = runCatching { request("GET", syncUrl(base), token(), null) }.getOrNull()
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
                binding.tvStatus.text = "同步失败：${explain(result.body)}\n试过：${result.triedUrl}"
                if (!silent) toastOnUi("同步失败：${explain(result.body)}")
            }
        }.onError {
            binding.tvStatus.text = "连接失败：${it.localizedMessage}"
            toastOnUi("连接失败：${it.localizedMessage}")
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
                "令牌不对，或者填的是飞牛的网页代理地址。FnReader 手机接口在应用自己的端口：http://IP:1122"
            lower.contains("<html") || plain.startsWith("<") ->
                "地址返回的是网页。这里只填 http://IP:端口（默认 1122）"
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
        startActivity<OnLineImportActivity> { data = Uri.parse(url) }
    }

    private fun uploadProgress() {
        val base = requireServerAndToken() ?: return
        execute {
            val books = appDb.bookDao.flowAll().first()
                .filter { it.bookUrl.isNotEmpty() && !it.bookUrl.startsWith("file:") }
            val items = JSONArray()
            books.forEach { book ->
                items.put(
                    JSONObject().apply {
                        put("bookUrl", book.bookUrl)
                        put("index", book.durChapterIndex)
                        put("pos", book.durChapterPos)
                        put("title", book.durChapterTitle ?: "")
                    }
                )
            }
            val payload = JSONObject().apply { put("items", items) }.toString()
            items.length() to request("POST", "$base/api/m/progress/batch", token(), payload)
        }.onSuccess { result ->
            val localCount = result.first
            val code = result.second.first
            if (code in 200..299) {
                val obj = runCatching { JSONObject(result.second.second) }.getOrNull()
                val updated = obj?.optInt("updated") ?: 0
                val total = obj?.optInt("total") ?: localCount
                toastOnUi("已回传 $updated/$total 条进度（本机有 $localCount 本）")
            } else {
                toastOnUi("回传失败：HTTP $code ${result.second.second.take(60)}")
            }
        }.onError {
            toastOnUi("回传失败：${it.localizedMessage}")
        }
    }

    private fun showWebDav() {
        val base = requireServerAndToken() ?: return
        val dav = davUrl(base)
        val tk = token()
        alert {
            setTitle("WebDAV 接入")
            setMessage(
                "地址：$dav\n用户名：任意\n密码：$tk\n\n" +
                    "· /dav/books/  书架里已下载的书籍（只读，.txt）\n" +
                    "· /dav/backup/ 可读写，可接「我的 → 备份与恢复 → WebDAV」"
            )
            positiveButton("复制地址") { requireContext().sendToClip(dav) }
            neutralButton("打开备份设置") {
                startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.BACKUP_CONFIG) }
            }
            negativeButton(android.R.string.cancel) {}
        }
    }

    private fun showHttpApi() {
        val base = requireServerAndToken() ?: return
        val apiBase = "$base/api/m/"
        val tk = token()
        alert {
            setTitle("HTTP API")
            setMessage(
                "接口基址：$apiBase\n请求头：X-Api-Token: $tk\n\n" +
                    "GET  /sync 书架+进度+书源（可带 since 增量、full=1 带完整规则）\n" +
                    "GET  /sources.json 书源数组（?all=1 含停用）\n" +
                    "GET  /shelf 书架\n" +
                    "GET  /toc?url= 目录\n" +
                    "GET  /content?url=&index= 正文\n" +
                    "GET  /cover?url= 封面\n" +
                    "POST /progress 单条进度\n" +
                    "POST /progress/batch 批量进度"
            )
            positiveButton("复制接口地址") { requireContext().sendToClip(apiBase) }
            okButton()
        }
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
        jsonBody: String?
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
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
