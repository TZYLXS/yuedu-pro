package io.legado.app.model

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FnReader 手机端解析桥（服务端 v1.7.26 起，1.8.x 增加软总线）
 *
 * 有些书源要 WebView 或加密接口，飞牛那边跑不了，就把任务丢给手机：
 *   GET  /api/m/remote/pending?wait=&limit=&id=&name=&model=&ver=   领任务（长轮询）
 *   POST /api/m/remote/result                                       回结果
 * 任务类型：search / toc / content，就地用本机的书源规则执行。
 *
 * 令牌一律走请求头 X-Api-Token，不放进 URL——URL 会进浏览器历史、反向代理和
 * 服务器日志，令牌留在里面等于一直明文外泄。文档「外网访问 → 安全要点」也是这个口径。
 */
object FnReaderRemote {

    class RemoteException(message: String) : Exception(message)

    data class Job(
        val id: String,
        val type: String,
        val payload: JSONObject,
        val age: Long
    )

    /** 补协议、去尾部斜杠；空串原样返回 */
    fun normalizeBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        return s
    }

    /**
     * 领任务。wait 最多 30 秒，服务端没有任务就挂着等。
     *
     * 1.8.0 起带上 id / name / model / ver，服务端会自动把本机登记成
     * 「软总线」里的解析设备（网页 设置 → 软总线 能看到它、也能单独暂停它）。
     */
    suspend fun poll(
        base: String,
        token: String,
        waitMs: Int = 15000,
        limit: Int = 5,
        deviceId: String = "",
        deviceName: String = "",
        deviceModel: String = "",
        appVer: String = ""
    ): List<Job> = withContext(Dispatchers.IO) {
        val query = StringBuilder("?wait=$waitMs&limit=$limit")
        if (deviceId.isNotEmpty()) query.append("&id=").append(Uri.encode(deviceId))
        if (deviceName.isNotEmpty()) query.append("&name=").append(Uri.encode(deviceName))
        if (deviceModel.isNotEmpty()) query.append("&model=").append(Uri.encode(deviceModel))
        if (appVer.isNotEmpty()) query.append("&ver=").append(Uri.encode(appVer))
        val url = "$base/api/m/remote/pending$query"
        val (code, body) = request("GET", url, token, null, waitMs + 15000)
        if (code !in 200..299) throw RemoteException(explain(code, body))
        val array = runCatching { JSONObject(body).optJSONArray("jobs") }.getOrNull()
            ?: throw RemoteException("返回的不是任务 JSON：${body.take(80)}")
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                Job(
                    id = it.optString("id"),
                    type = it.optString("type"),
                    payload = it.optJSONObject("payload") ?: JSONObject(),
                    age = it.optLong("age")
                )
            }
        }.filter { it.id.isNotEmpty() && it.type.isNotEmpty() }
    }

    /**
     * 心跳 / 探活：GET /api/m/info。返回服务器版本号，失败抛异常。
     * 服务里定时调一次，保证「连上了就一直在线」而不只是开局能连。
     */
    suspend fun info(base: String, token: String): String = withContext(Dispatchers.IO) {
        val url = "$base/api/m/info"
        val (code, body) = request("GET", url, token, null, 10000)
        if (code !in 200..299) throw RemoteException(explain(code, body))
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: throw RemoteException("返回的不是 JSON：${body.take(60)}")
        obj.optString("version").ifEmpty { obj.optString("app", "FnReader") }
    }

    /**
     * 回结果：成功带 data，失败带 error。
     */
    suspend fun submit(
        base: String,
        token: String,
        id: String,
        ok: Boolean,
        data: JSONObject? = null,
        error: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("id", id)
            put("ok", ok)
            if (data != null) put("data", data)
            if (!error.isNullOrBlank()) put("error", error)
        }
        val url = "$base/api/m/remote/result"
        val (code, body) = request("POST", url, token, payload.toString(), 20000)
        if (code !in 200..299) throw RemoteException(explain(code, body))
    }

    /**
     * 执行一个任务，返回要回传给服务端的 data。
     */
    suspend fun execute(job: Job): JSONObject = withContext(Dispatchers.IO) {
        when (job.type) {
            "search" -> search(job.payload)
            "toc" -> toc(job.payload)
            "content" -> content(job.payload)
            else -> throw RemoteException("不支持的任务类型：${job.type}")
        }
    }

    // ---------------- 三种任务 ----------------

    private fun source(sourceUrl: String): BookSource {
        if (sourceUrl.isEmpty()) throw RemoteException("任务里没有书源地址")
        appDb.bookSourceDao.getBookSource(sourceUrl)?.let { return it }
        // 飞牛库里存的地址和本机可能差个结尾斜杠 / http(s)，按归一化再找一遍
        val key = normalizeUrl(sourceUrl)
        appDb.bookSourceDao.allTextEnabled.firstOrNull {
            normalizeUrl(it.bookSourceUrl) == key
        }?.let { return it }
        throw RemoteException("本机没有这个书源，先在「导入书源」里加进来：$sourceUrl")
    }

    private fun normalizeUrl(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    private suspend fun search(payload: JSONObject): JSONObject {
        val bs = source(payload.optString("source"))
        val key = payload.optString("key")
        if (key.isEmpty()) throw RemoteException("任务里没有搜索关键词")
        val list = WebBook.searchBookAwait(bs, key, 1)
        val books = JSONArray()
        list.forEach { b ->
            books.put(JSONObject().apply {
                put("name", b.name)
                put("author", b.author)
                put("bookUrl", b.bookUrl)
                put("coverUrl", b.coverUrl.orEmpty())
                put("intro", b.intro.orEmpty())
                put("kind", b.kind.orEmpty())
                put("lastChapter", b.latestChapterTitle.orEmpty())
                put("origin", b.origin)
                put("originName", b.originName)
            })
        }
        return JSONObject().put("books", books)
    }

    private suspend fun toc(payload: JSONObject): JSONObject {
        val bs = source(payload.optString("source"))
        val bookUrl = payload.optString("bookUrl")
        if (bookUrl.isEmpty()) throw RemoteException("任务里没有书籍地址")
        val book = Book(
            bookUrl = bookUrl,
            tocUrl = bookUrl,
            origin = bs.bookSourceUrl,
            originName = bs.bookSourceName
        ).apply {
            name = payload.optString("name")
            author = payload.optString("author")
        }
        val result = WebBook.getChapterListAwait(bs, book)
        val chapters = result.getOrThrow()
        val array = JSONArray()
        chapters.forEachIndexed { index, chapter ->
            array.put(JSONObject().apply {
                put("index", if (chapter.index > 0) chapter.index else index)
                put("title", chapter.title)
                put("url", runCatching { chapter.getAbsoluteURL() }.getOrElse { chapter.url })
            })
        }
        return JSONObject().put("chapters", array)
    }

    private suspend fun content(payload: JSONObject): JSONObject {
        val bs = source(payload.optString("source"))
        val chapterUrl = payload.optString("chapterUrl")
        if (chapterUrl.isEmpty()) throw RemoteException("任务里没有章节地址")
        val bookUrl = payload.optString("bookUrl").ifEmpty { chapterUrl }
        val book = Book(
            bookUrl = bookUrl,
            tocUrl = bookUrl,
            origin = bs.bookSourceUrl,
            originName = bs.bookSourceName
        )
        val chapter = BookChapter(
            url = chapterUrl,
            title = payload.optString("title"),
            baseUrl = bookUrl,
            bookUrl = bookUrl,
            index = payload.optInt("index", 0)
        )
        val text = WebBook.getContentAwait(
            bookSource = bs,
            book = book,
            bookChapter = chapter,
            needSave = false
        )
        val paragraphs = JSONArray()
        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) paragraphs.put(trimmed)
        }
        return JSONObject().apply {
            put("paragraphs", paragraphs)
            put("title", chapter.title)
        }
    }

    // ---------------- 网络 ----------------

    /** @return HTTP 状态码 to 响应文本 */
    private fun request(
        method: String,
        url: String,
        token: String,
        jsonBody: String?,
        readTimeoutMs: Int
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-Api-Token", token)
            conn.setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { out -> out.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn?.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String {
        val plain = body.trim()
        val lower = plain.lowercase()
        return when {
            code == 401 || lower.contains("invalid token") || lower.contains("unauthorized") ->
                "令牌不对或已被重置（FnReader 设置 → 手机接入 → 重置后，App 里要重新填）"
            code == 403 -> "令牌没有访问这个接口的权限（HTTP 403）"
            code == 404 -> "接口不存在：地址里可能带了多余的路径（HTTP 404）"
            plain.isEmpty() -> "服务端没有返回内容（HTTP $code）"
            plain.startsWith("<") -> "地址返回的是网页，不是手机接口"
            code >= 500 -> "服务端出错（HTTP $code）${plain.take(60)}"
            else -> "HTTP $code ${plain.take(80)}"
        }
    }

    /**
     * 把网络异常翻成用户能对照着排查的一句话：
     * 地址不通 / 端口不对 / 证书不受信任，三种原因的处理办法完全不同。
     */
    fun describeError(e: Throwable): String = when (e) {
        is RemoteException -> e.message ?: "请求失败"
        is java.net.SocketTimeoutException -> "等待超时（服务端没响应：端口被挡、服务卡住或网络太慢）"
        is java.net.UnknownHostException -> "找不到主机（地址写错，或手机 DNS 解析不了这个域名）"
        is java.net.ConnectException -> "连不上端口（服务没起来 / 端口不对 / 防火墙拦了）"
        is javax.net.ssl.SSLException -> "证书不受信任（自签或已过期；先换内网 http 地址确认能通）"
        is java.io.IOException -> "网络错误：${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

}
