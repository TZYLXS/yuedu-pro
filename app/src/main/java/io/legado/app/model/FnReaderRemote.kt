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
 * FnReader 手机端解析桥（服务端 v1.7.26 起）
 *
 * 有些书源要 WebView 或加密接口，飞牛那边跑不了，就把任务丢给手机：
 *   GET  /api/m/remote/pending?token=&wait=&limit=    领任务（长轮询）
 *   POST /api/m/remote/result?token=                  回结果
 * 任务类型：search / toc / content，就地用本机的书源规则执行。
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
     */
    suspend fun poll(
        base: String,
        token: String,
        waitMs: Int = 15000,
        limit: Int = 5
    ): List<Job> = withContext(Dispatchers.IO) {
        val url = "$base/api/m/remote/pending?token=${Uri.encode(token)}&wait=$waitMs&limit=$limit"
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
        val url = "$base/api/m/remote/result?token=${Uri.encode(token)}"
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
            code == 401 || lower.contains("invalid token") -> "令牌不对（在 FnReader 设置 → 手机接入 里核对）"
            plain.isEmpty() -> "服务端没有返回内容（HTTP $code）"
            plain.startsWith("<") -> "地址返回的是网页，不是手机接口"
            else -> "HTTP $code ${plain.take(80)}"
        }
    }

}
