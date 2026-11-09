package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.config.FnReaderConfigDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.net.URL

/**
 * 软总线之外的两件「跟着服务端一起活着」的活，原来要手动点 ⑧⑨：
 *   ⑧ 上传本机缓存（网页离线读正文，文档 §16）
 *   ⑨ 书源双向同步（lastUpdateTime 大的一方生效，文档 §17）
 *
 * 现在改成自动：手机解析待命连上服务端后，每 5 分钟跑一次；服务端没这两个接口就自动跳过，
 * 不再让人点按钮。缓存只传「整本都下完」的书，没下完的继续待命，等下轮再说。
 */
object FnReaderAutoSync {

    /** 一轮自动同步的结果，写进通知栏给用户看 */
    data class Result(
        val sources: String = "",
        val cache: String = "",
        val shelf: String = "",
        val skipped: Boolean = false
    ) {
        fun summary(): String =
            listOf(shelf, sources, cache).filter { it.isNotEmpty() }.joinToString("　")
    }

    /** 服务端确认没有对应接口就别再每轮重试，记住这个结论 */
    private const val KEY_NO_SOURCE_SYNC = "fnReaderNoSourceSync"
    private const val KEY_NO_CACHE_API = "fnReaderNoCacheApi"
    /** 上一轮从服务端书架见过的 bookUrl 集合 */
    private const val KEY_SHELF_SEEN = "fnReaderShelfSeen"
    /** 用户手动删掉的 bookUrl（墓碑），服务端再推也不补回本地 */
    private const val KEY_SHELF_DELETED = "fnReaderShelfDeleted"

    suspend fun run(base: String, token: String): Result = withContext(Dispatchers.IO) {
        val shelf = runShelf(base, token)
        val sources = runSources(base, token)
        val cache = runCache(base, token)
        Result(sources, cache, shelf)
    }

    // ---------------- ③ 书架合并：同名只留一本（优先有 .nb 缓存的） ----------------

    private suspend fun runShelf(base: String, token: String): String {
        val body = try {
            // full=1：要整份书架（增量包里没有的书会被当成「用户删了」，墓碑判断会误伤）
            val (code, text) = FnReaderRemote.fetch("GET", "$base/api/m/sync?full=1", token)
            if (code != 200 || !text.trimStart().startsWith("{")) return ""
            text
        } catch (e: Exception) {
            return ""
        }
        return mergeShelf(body) ?: ""
    }

    /**
     * 把服务器书架并进本地书架，并清掉本地同名（不同书源）的副本：
     * 只留一本——本地有 .nb 缓存（已下过）的优先，其次最近在读的。
     * @return 给通知栏看的一句话；body 不是 JSON 时返回 null
     */
    fun mergeShelf(body: String): String? {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val shelf = obj.optJSONArray("shelf") ?: JSONArray()
        val progress = obj.optJSONArray("progress") ?: JSONArray()

        val dupRemoved = dedupeLocalShelf()
        // 用户手动删掉的书不能再被服务端书架补回来：
        // 上一轮见过、本地现在没有的 url 记成墓碑，以后一律跳过
        val seenPrev = loadSet(KEY_SHELF_SEEN)
        val deleted = loadSet(KEY_SHELF_DELETED)
        for (u in seenPrev) {
            if (u.isNotEmpty() && appDb.bookDao.getBook(u) == null) deleted.add(u)
        }
        // 用户又自己加回来的，撤销墓碑
        deleted.removeAll { appDb.bookDao.getBook(it) != null }
        saveSet(KEY_SHELF_DELETED, deleted)
        var skippedDeleted = 0
        val nameMap = HashMap<String, String>()
        for (b in appDb.bookDao.all) {
            val k = normTitle(b.name)
            if (k.isNotEmpty()) nameMap[k] = b.bookUrl
        }
        val remoteProgress = HashMap<String, JSONObject>()
        for (i in 0 until progress.length()) {
            val p = progress.optJSONObject(i) ?: continue
            val url = p.optString("bookUrl")
            if (url.isNotEmpty()) remoteProgress[url] = p
        }

        var added = 0
        var updated = 0
        var skipped = 0
        for (i in 0 until shelf.length()) {
            val s = shelf.optJSONObject(i) ?: continue
            val url = s.optString("bookUrl")
            if (url.isEmpty()) continue
            val shelfTime = s.optLong("durChapterTime", 0L)
            if (deleted.contains(url)) {
                // 用户删过这本，服务端还留着也不补回
                skippedDeleted++
                continue
            }
            val local = appDb.bookDao.getBook(url)
            if (local == null) {
                val key = normTitle(s.optString("name"))
                if (key.isNotEmpty() && nameMap.containsKey(key)) {
                    skipped++
                    continue
                }
                val book = Book(bookUrl = url, origin = s.optString("origin"), originName = s.optString("originName"))
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
                if (key.isNotEmpty()) nameMap[key] = url
                added++
                continue
            }
            val p = remoteProgress[url]
            val remoteTime = maxOf(shelfTime, p?.optLong("time", 0L) ?: 0L)
            if (remoteTime > local.durChapterTime) {
                local.durChapterIndex = p?.optInt("index", local.durChapterIndex) ?: s.optInt("durChapterIndex", local.durChapterIndex)
                local.durChapterPos = p?.optInt("pos", local.durChapterPos) ?: s.optInt("durChapterPos", local.durChapterPos)
                val title = p?.optString("title").orEmpty().ifEmpty { s.optString("durChapterTitle") }
                if (title.isNotEmpty()) local.durChapterTitle = title
                local.durChapterTime = remoteTime
                local.save()
                updated++
            }
        }
        // 记住这轮服务端都推了哪些书，下一轮用来判断用户删了哪本
        val seenNow = HashSet<String>()
        for (i in 0 until shelf.length()) {
            val u = shelf.optJSONObject(i)?.optString("bookUrl") ?: continue
            if (u.isNotEmpty()) seenNow.add(u)
        }
        saveSet(KEY_SHELF_SEEN, seenNow)

        val parts = ArrayList<String>()
        if (added > 0) parts.add("新增 $added 本")
        if (updated > 0) parts.add("更新 $updated 条进度")
        if (skipped > 0) parts.add("跳过同名 $skipped 本")
        if (skippedDeleted > 0) parts.add("跳过已删 $skippedDeleted 本")
        if (dupRemoved > 0) parts.add("清理重复 $dupRemoved 本")
        return if (parts.isEmpty()) "" else "书架：" + parts.joinToString("　")
    }

    private fun loadSet(key: String): MutableSet<String> {
        val raw = appCtx.getPrefString(key, "") ?: ""
        if (raw.isEmpty()) return HashSet()
        return runCatching {
            val arr = JSONArray(raw)
            val set = HashSet<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotEmpty()) set.add(v)
            }
            set
        }.getOrDefault(HashSet())
    }

    private fun saveSet(key: String, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        appCtx.putPrefString(key, arr.toString())
    }

    /** 本地同名（不同书源）只留一本：有 .nb 缓存的优先，其次最近在读的；进度先并再删 */
    private fun dedupeLocalShelf(): Int {
        var removed = 0
        for ((key, list) in appDb.bookDao.all.groupBy { normTitle(it.name) }) {
            if (key.isEmpty() || list.size < 2) continue
            val keep = list.maxWithOrNull(compareBy({ hasNbCache(it) }, { it.durChapterTime })) ?: continue
            for (book in list) {
                if (book.bookUrl == keep.bookUrl) continue
                if (book.durChapterTime > keep.durChapterTime) {
                    keep.durChapterIndex = book.durChapterIndex
                    keep.durChapterPos = book.durChapterPos
                    keep.durChapterTitle = book.durChapterTitle
                    keep.durChapterTime = book.durChapterTime
                    keep.save()
                }
                appDb.bookDao.delete(book)
                removed++
            }
        }
        return removed
    }

    private fun normTitle(name: String?): String =
        (name ?: "").replace(Regex("[\\s　《》·]"), "").lowercase()

    // ---------------- ⑨ 书源双向同步 ----------------

    private suspend fun runSources(base: String, token: String): String {
        if (appCtx.getPrefString(KEY_NO_SOURCE_SYNC, "0") == "1") return ""
        val since = appCtx.getPrefString(FnReaderConfigDialog.KEY_SOURCES_SINCE, "0")?.toLongOrNull() ?: 0L
        return try {
            if (since <= 0L) {
                firstSync(base, token)
            } else {
                incrementalSync(base, token, since)
            }
        } catch (e: Exception) {
            val msg = FnReaderRemote.describeError(e)
            if (msg.contains("404")) {
                appCtx.putPrefString(KEY_NO_SOURCE_SYNC, "1")
                "书源同步：服务端没有这个接口，已跳过"
            } else {
                "书源同步失败：$msg"
            }
        }
    }

    /** 首次接入：按 bookSourceUrl 合并上传（POST /api/m/sources），再取一次 serverTime 当 since */
    private suspend fun firstSync(base: String, token: String): String {
        val parts = appDb.bookSourceDao.flowAll().first()
        val all = ArrayList<BookSource>()
        parts.forEach { p ->
            appDb.bookSourceDao.getBookSource(p.bookSourceUrl)?.let { all.add(it) }
        }
        if (all.isEmpty()) return ""
        val body = JSONArray().also { arr ->
            all.forEach { arr.put(sourceJson(it)) }
        }.toString()
        val r = FnReaderRemote.fetch("POST", "$base/api/m/sources", token, body, 180000)
        if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
        val obj = JSONObject(r.second)
        val stamp = FnReaderRemote.fetch(
            "GET",
            "$base/api/m/sources/sync?since=${System.currentTimeMillis()}",
            token
        )
        val serverTime = if (stamp.first in 200..299) {
            runCatching { JSONObject(stamp.second).optLong("serverTime") }.getOrDefault(0L)
        } else 0L
        appCtx.putPrefString(
            FnReaderConfigDialog.KEY_SOURCES_SINCE,
            (if (serverTime > 0) serverTime else System.currentTimeMillis()).toString()
        )
        return "书源首次合并：上传 ${all.size} 个（新增 ${obj.optInt("added")}　更新 ${obj.optInt("updated")}）"
    }

    /** 之后每轮：本机改过的推上去，服务端更进的拉回来 */
    private suspend fun incrementalSync(base: String, token: String, since: Long): String {
        val parts = appDb.bookSourceDao.flowAll().first()
        val changed = ArrayList<BookSource>()
        parts.forEach { p ->
            if (p.lastUpdateTime > since) {
                appDb.bookSourceDao.getBookSource(p.bookSourceUrl)?.let { changed.add(it) }
            }
        }
        var pulled = 0
        var removed = 0
        var total = 0
        var pushed = 0
        val serverTime: Long
        if (changed.isEmpty()) {
            val r = FnReaderRemote.fetch("GET", "$base/api/m/sources/sync?since=$since", token, null, 120000)
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            val obj = JSONObject(r.second)
            pulled = mergePull(obj.optJSONArray("sources"))
            total = obj.optInt("total")
            serverTime = obj.optLong("serverTime", System.currentTimeMillis())
        } else {
            val payload = JSONObject().apply {
                put("since", since)
                put("updated", JSONArray().also { arr -> changed.forEach { arr.put(sourceJson(it)) } })
            }.toString()
            val r = FnReaderRemote.fetch("POST", "$base/api/m/sources/sync", token, payload, 180000)
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            val obj = JSONObject(r.second)
            pushed = changed.size
            pulled = mergePull(obj.optJSONArray("pull"))
            removed = applyPullDeleted(obj.optJSONArray("pullDeleted"))
            total = obj.optInt("total")
            serverTime = obj.optLong("serverTime", System.currentTimeMillis())
        }
        appCtx.putPrefString(FnReaderConfigDialog.KEY_SOURCES_SINCE, serverTime.toString())
        return buildString {
            append("书源同步：推 $pushed 个　拉 $pulled 个")
            if (removed > 0) append("　删 $removed 个")
            append("　飞牛 $total 个")
        }
    }

    private suspend fun mergePull(arr: JSONArray?): Int {
        if (arr == null) return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val src = runCatching { GSON.fromJson(o.toString(), BookSource::class.java) }.getOrNull() ?: continue
            if (src.bookSourceUrl.isEmpty()) continue
            appDb.bookSourceDao.insert(src)
            n++
        }
        return n
    }

    private suspend fun applyPullDeleted(arr: JSONArray?): Int {
        if (arr == null) return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val url = arr.optJSONObject(i)?.optString("bookSourceUrl").orEmpty()
            if (url.isEmpty()) continue
            appDb.bookSourceDao.delete(url)
            n++
        }
        return n
    }

    private fun sourceJson(src: BookSource): JSONObject =
        JSONObject(GSON.toJson(src)).apply {
            put("bookSourceUrl", src.bookSourceUrl)
            put("lastUpdateTime", src.lastUpdateTime)
        }

    // ---------------- ⑧ 上传本机缓存 ----------------

    /** 同名书的取舍：有 .nb 缓存（下过的）优先，其次最近在读的 */
    private fun isBetterCopy(a: Book, b: Book): Boolean {
        val aCache = hasNbCache(a)
        val bCache = hasNbCache(b)
        if (aCache != bCache) return aCache
        return a.durChapterTime > b.durChapterTime
    }

    private fun hasNbCache(book: Book): Boolean = runCatching {
        val dir = File(BookHelp.cachePath, book.getFolderName())
        dir.isDirectory && dir.listFiles()?.any { it.name.endsWith(".nb", true) } == true
    }.getOrDefault(false)

    private suspend fun runCache(base: String, token: String): String {
        if (appCtx.getPrefString(KEY_NO_CACHE_API, "0") == "1") return ""
        val books = appDb.bookDao.flowAll().first()
            .filter { it.bookUrl.isNotEmpty() && !it.bookUrl.startsWith("file:") && it.name.isNotBlank() }
        // 同名不同源的重复书只传一份：优先有 .nb 缓存（下过的）那本，其次最近在读的那本
        val seen = HashMap<String, Book>()
        for (book in books) {
            val key = book.name.replace(Regex("[\\s　《》·]"), "").lowercase()
            val prev = seen[key]
            if (prev == null || isBetterCopy(book, prev)) seen[key] = book
        }
        val deduped = seen.values
        var uploaded = 0
        var waiting = 0
        var chapters = 0
        var failed = 0
        for (book in deduped) {
            val folder = File(BookHelp.cachePath, book.getFolderName())
            if (!folder.isDirectory) continue
            val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".nb", true) } ?: continue
            if (files.isEmpty()) continue
            // 「全下完才算」：目录里缓存的章数要够总章数，否则先待命
            val total = book.totalChapterNum
            if (total <= 0 || files.size < total) {
                waiting++
                continue
            }
            val payload = cacheJson(book, folder, files) ?: continue
            val r = runCatching {
                FnReaderRemote.fetch("POST", "$base/api/m/cache", token, payload.toString(), 60000)
            }.getOrNull()
            if (r == null || r.first !in 200..299) {
                if (r != null && r.first == 404) {
                    appCtx.putPrefString(KEY_NO_CACHE_API, "1")
                    return "缓存上传：服务端没有这个接口，已跳过"
                }
                failed++
                continue
            }
            uploaded++
            chapters += files.size
        }
        if (uploaded == 0 && waiting == 0) return ""
        return buildString {
            append("缓存：上传 $uploaded 本")
            if (chapters > 0) append("（$chapters 章）")
            if (waiting > 0) append("　待命 $waiting 本未下完")
            if (failed > 0) append("　失败 $failed 本")
        }
    }

    /**
     * 一本书的 /api/m/cache 载荷（字段见文档 §16.1）。只传原样缓存的章节原文，
     * origin 能对上就带上（对不上也不影响离线读正文）。
     */
    private suspend fun cacheJson(book: Book, folder: File, files: Array<out File>): JSONObject? {
        val chapters = JSONArray()
        files.forEach { f ->
            val index = f.name.take(5).toIntOrNull() ?: return@forEach
            val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            chapters.put(JSONObject().apply {
                put("index", index)
                put("title", appDb.bookChapterDao.getChapter(book.bookUrl, index)?.title.orEmpty())
                put("file", f.name)
                put("raw", raw)
            })
        }
        if (chapters.length() == 0) return null
        val origin = runCatching {
            val host = URL(book.bookUrl).host
            appDb.bookSourceDao.all.firstOrNull {
                runCatching { URL(it.bookSourceUrl).host }.getOrNull() == host
            }?.bookSourceUrl.orEmpty()
        }.getOrDefault("")
        return JSONObject().apply {
            put("layout", "legado")
            put("book", JSONObject().apply {
                put("bookUrl", book.bookUrl)
                put("name", book.name)
                put("author", book.author)
                put("origin", origin)
                book.intro?.takeIf { it.isNotEmpty() }?.let { put("intro", it.take(500)) }
                put("durChapterIndex", book.durChapterIndex)
            })
            put("chapters", chapters)
        }
    }
}
