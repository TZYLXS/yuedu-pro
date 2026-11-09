package io.legado.app.model

import android.net.Uri
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.HtmlFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
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
 * 任务类型：search / toc / content（用本机书源规则执行）/ rank（代抓榜单页，文档 §15）。
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
        error: String? = null,
        deviceId: String = ""
    ): Unit = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("id", id)
            // 服务端要按设备统计「这活是哪台机器干的」，缺了就在 recent 里查无此人
            if (deviceId.isNotEmpty()) put("deviceId", deviceId)
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
            "rank" -> rank(job.payload)
            "bookinfo" -> bookInfo(job.payload)
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
        val kw = key.trim()
        var list = runCatching { WebBook.searchBookAwait(bs, key, 1) }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            // 顶点小说这类站按 IP 限流「搜索间隔15秒」，服务端批量派发时常常抓到空结果页；
            // 站点不给结果时先用本机书架里同源、同名的书兜底，服务端就能把这本书搜出来
            list = localShelfMatch(bs, kw)
        }
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

    /**
     * 站点搜索被限流 / 关键词命中不到时，拿本机书架里同一书源的书兑底：
     * 书名包含关键词即可，字段齐全，服务端能直接建书并取目录。
     */
    private fun localShelfMatch(bs: BookSource, key: String): List<SearchBook> {
        val normKey = key.replace(" ", "").lowercase()
        if (normKey.isEmpty()) return emptyList()
        return appDb.bookDao.all
            .filter { book ->
                book.name.isNotBlank() &&
                    book.origin == bs.bookSourceUrl &&
                    book.name.replace(" ", "").lowercase().contains(normKey)
            }
            .take(10)
            .map { book ->
                SearchBook(
                    bookUrl = book.bookUrl,
                    origin = book.origin,
                    originName = book.originName,
                    name = book.name,
                    author = book.author,
                    kind = book.kind,
                    coverUrl = book.coverUrl,
                    intro = book.intro,
                    latestChapterTitle = book.latestChapterTitle
                )
            }
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

    // ---------------- 详情/简介补全（bookinfo，服务端 2.0.0 新增） ----------------

    /*
     * 服务端抓简介这条路是残的（起点详情页 202 反爬、晋江字体混淆），所以缺简介时把任务丢给手机：
     * payload={bookUrl, source, name, author}。手机用本机书源的详情规则抓完回传，
     * 字段全部可选，拿不到就省略——回空串服务端会当成「这书没简介」不再重试，所以宁缺勿滥。
     */
    private suspend fun bookInfo(payload: JSONObject): JSONObject {
        val bookUrl = payload.optString("bookUrl")
        if (bookUrl.isEmpty()) throw RemoteException("任务里没有书籍详情页地址")
        val bs = matchSource(payload.optString("source"), bookUrl)
        val book = Book(
            bookUrl = bookUrl,
            tocUrl = bookUrl,
            origin = bs.bookSourceUrl,
            originName = bs.bookSourceName
        ).apply {
            name = payload.optString("name")
            author = payload.optString("author")
        }
        val info = WebBook.getBookInfoAwait(bs, book)
        val intro = info.intro?.let { plainText(it) }
        return JSONObject().apply {
            if (!intro.isNullOrBlank()) put("intro", intro)
            info.author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
            info.coverUrl?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
            info.kind?.takeIf { it.isNotBlank() }?.let { put("kind", it) }
            info.wordCount?.takeIf { it.isNotBlank() }?.let { put("wordCount", it) }
            info.latestChapterTitle?.takeIf { it.isNotBlank() }?.let { put("lastChapter", it) }
            if (info.totalChapterNum > 0) put("totalChapterNum", info.totalChapterNum)
        }
    }

    /**
     * bookinfo 的书源可能没给地址（服务端只存了书源 URL，有时是空的），
     * 这时按 bookUrl 的 host 去本机书源里捞一个能用的。
     */
    private fun matchSource(sourceUrl: String, bookUrl: String): BookSource {
        if (sourceUrl.isNotEmpty()) {
            runCatching { source(sourceUrl) }.getOrNull()?.let { return it }
        }
        val host = runCatching { URL(bookUrl).host }.getOrNull()
        if (!host.isNullOrEmpty()) {
            appDb.bookSourceDao.all.firstOrNull { candidate ->
                runCatching { URL(candidate.bookSourceUrl).host }.getOrNull() == host
            }?.let { return it }
        }
        throw RemoteException("没有匹配的书源：${sourceUrl.ifEmpty { host.orEmpty() }}")
    }

    /** 富文本简介转纯文本：`<br>` 换成换行，标签全去，再压掉多余的空白行 */
    private fun plainText(html: String): String {
        var text = runCatching { HtmlFormatter.formatKeepImg(html, null) }.getOrElse { html }
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        return text.lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // ---------------- 榜单（rank） ----------------

    /*
     * 服务端抓起点榜单常被反爬挡住（返回 202 挑战页），文档 §15 说「软总线有在线手机就先让手机抓」，
     * 于是建一个 type=rank、payload={url} 的任务丢下来，手机抓完把结构化的 books 回上去。
     * 服务端拿到 data 后直接当 /api/daily 的结果用，所以这里必须给 source / url / books。
     */
    private suspend fun rank(payload: JSONObject): JSONObject {
        val url = payload.optString("url").ifEmpty { "https://www.qidian.com/rank/recom/" }
        val html = httpGetText(url)
        // 起点被反爬时会返回一段很短的挑战页，解析它只会得到空气，尽早报错让服务端换源
        if (html.length < 10000) {
            throw RemoteException("榜单页只拿到 ${html.length} 字节，八成被反爬拦了：$url")
        }
        val doc = Jsoup.parse(html, url)
        val books = JSONArray()
        val seen = HashSet<String>()
        when {
            url.contains("zongheng.com") -> doc.select(".zh-modules-rank-book").forEach { el ->
                val a = el.selectFirst(".book-rank--title-text a") ?: return@forEach
                val name = (a.attr("title").ifEmpty { a.text() }).trim()
                val href = a.attr("href").trim()
                if (name.isEmpty() || href.isEmpty()) return@forEach
                val abs = href.toAbsolute(url)
                if (!seen.add(abs)) return@forEach
                books.put(JSONObject().apply {
                    put("name", name)
                    put("url", abs)
                    put("author", el.selectFirst(".auther")?.text()?.trim().orEmpty())
                    put("score", el.selectFirst(".rank-content-default__rank-text")?.text()?.trim().orEmpty())
                    put("cover", "")
                })
            }
            url.contains("17k.com") -> doc.select("a[href*=\"/book/\"]").forEach { a ->
                val href = a.attr("href").trim()
                if (!Regex("/book/\\d+\\.html").containsMatchIn(href)) return@forEach
                val name = (a.attr("title").ifEmpty { a.text() }).trim()
                if (name.isEmpty() || name.length > 40) return@forEach
                val abs = href.toAbsolute(url)
                if (!seen.add(abs)) return@forEach
                books.put(JSONObject().apply {
                    put("name", name)
                    put("url", abs)
                    put("author", "")
                    put("score", "")
                    put("cover", "")
                })
            }
            else -> doc.select(".book-img-text li, .rank-list li").forEach { li ->
                // 起点推荐榜：书名在 h2 a 里，作者在 .author 里，封面取第一个 img
                val a = li.selectFirst("h2 a, .book-mid-info h2 a") ?: return@forEach
                val name = a.text().trim()
                val href = a.attr("href").trim()
                if (name.isEmpty() || href.isEmpty()) return@forEach
                val abs = href.toAbsolute(url)
                if (!seen.add(abs)) return@forEach
                books.put(JSONObject().apply {
                    put("name", name)
                    put("url", abs)
                    put("author", li.selectFirst(".author a, .author")?.text()?.trim().orEmpty())
                    put("cover", li.selectFirst("img")?.attr("src")?.trim().orEmpty())
                    put("score", "")
                })
            }
        }
        if (books.length() == 0) throw RemoteException("榜单页解析出 0 本书，页面结构可能变了：$url")
        // 服务端拿 rank 序号当榜单名次，这里按抓取顺序补上
        for (i in 0 until books.length()) books.getJSONObject(i).put("rank", i + 1)
        return JSONObject().apply {
            put("source", if (url.contains("zongheng.com")) "纵横" else if (url.contains("17k.com")) "17K" else "起点")
            put("url", url)
            put("books", books)
        }
    }

    /** 抓一段网页文本（榜单页用）：浏览器 UA、跟随跳转、8 秒超时 */
    private fun httpGetText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RemoteException("榜单页返回 HTTP $code")
            return body
        } finally {
            conn?.disconnect()
        }
    }

    /** 相对链接补成绝对地址（榜单页里的 href 多半是 /book/xxx） */
    private fun String.toAbsolute(base: String): String {
        if (startsWith("http://", true) || startsWith("https://", true)) return this
        if (startsWith("//")) return "https:" + this
        val root = runCatching { java.net.URI(base).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: ""
        return if (startsWith("/")) root + this else root + "/" + this
    }

    // ---------------- 网络 ----------------

    /** @return HTTP 状态码 to 响应文本 */
    /**
     * 后台自动同步（书源 / 缓存）用的裸请求。返回 (状态码, 响应体)，
     * 非 2xx 由调用方决定是「不支持」还是「失败」。
     */
    suspend fun fetch(
        method: String,
        url: String,
        token: String,
        jsonBody: String? = null,
        readTimeoutMs: Int = 30000
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        request(method, url, token, jsonBody, readTimeoutMs)
    }

    /** 把 HTTP 状态码翻成人话，供自动同步的失败提示复用 */
    fun explainCode(code: Int, body: String): String = explain(code, body)

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
