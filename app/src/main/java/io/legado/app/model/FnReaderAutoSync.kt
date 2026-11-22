package io.legado.app.model

import android.util.Base64
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.config.FnReaderConfigDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private const val KEY_NO_COVER_API = "fnReaderNoCoverApi"
    /** 上一轮从服务端书架见过的 bookUrl 集合 */
    private const val KEY_SHELF_SEEN = "fnReaderShelfSeen"
    /** 用户手动删掉的 bookUrl（墓碑），服务端再推也不补回本地 */
    private const val KEY_SHELF_DELETED = "fnReaderShelfDeleted"
    /** 上次成功回传进度的时间水位，避免每 5 分钟把整库进度再推一遍 */
    private const val KEY_PROGRESS_SYNCED = "fnReaderProgressSyncedMs"
    /** 上次同步成功时本机书源地址快照，用来识别本机删掉的源 */
    private const val KEY_SOURCES_SNAPSHOT = "fnReaderSourcesSnapshot"
    /** 手动改书/加书后的短延迟推送，避免连续落库触发多次网络请求 */
    private var bookPushJob: Coroutine<Unit>? = null
    /** 阅读进度 3 秒防抖推送，对应清单“翻页/退出/切后台” */
    private var progressPushJob: Coroutine<Unit>? = null

    suspend fun run(base: String, token: String): Result = withContext(Dispatchers.IO) {
        val (shelf, shelfBody) = runShelf(base, token)
        val push = runPush(base, token, shelfBody)
        val sources = runSources(base, token)
        val cache = runCache(base, token)
        Result(sources, cache, listOf(shelf, push).filter { it.isNotEmpty() }.joinToString("　"))
    }

    // ---------------- ③ 书架合并：同名只留一本（优先有 .nb 缓存的） ----------------

    private suspend fun runShelf(base: String, token: String): Pair<String, String?> {
        val body = try {
            // full=1：要整份书架（增量包里没有的书会被当成「用户删了」，墓碑判断会误伤）
            val (code, text) = FnReaderRemote.fetch("GET", "$base/api/m/sync?full=1", token)
            if (code != 200 || !text.trimStart().startsWith("{")) return "" to null
            text
        } catch (e: Exception) {
            return "" to null
        }
        return (mergeShelf(body) ?: "") to body
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
                val remoteOrigin = s.optString("origin")
                val matched = if (remoteOrigin.isEmpty() || appDb.bookSourceDao.getBookSource(remoteOrigin) == null) {
                    BookSourceResolver.resolve(url)
                } else null
                val origin = matched?.bookSourceUrl ?: remoteOrigin
                if (origin.isEmpty()) {
                    // 服务端历史上传过一批 origin 为空的「手机缓存」书，导入后必然打不开。
                    // 本地也匹配不到书源时不再写入，避免把坏数据继续扩散到书架。
                    skipped++
                    continue
                }
                val book = Book(bookUrl = url, origin = origin, originName = matched?.bookSourceName ?: s.optString("originName"))
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

    // ---------------- 书架 → 服务端：缺书、进度、封面自动回传 ----------------

    /**
     * 清单 §3/§4/§5：本地有、服务端没有的书推上去；已有书的阅读进度按水位批量回传；
     * 服务端封面不是本地副本且本机有封面时顺带补推。每轮最多一次，失败只报错不阻断其他同步。
     */
    private suspend fun runPush(base: String, token: String, shelfBody: String?): String {
        val obj = shelfBody?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ""
        val shelf = obj.optJSONArray("shelf") ?: JSONArray()
        val serverUrls = HashSet<String>()
        val serverNames = HashSet<String>()
        val serverCovers = HashMap<String, String>()
        for (i in 0 until shelf.length()) {
            val s = shelf.optJSONObject(i) ?: continue
            val url = s.optString("bookUrl")
            val name = s.optString("name")
            if (url.isNotEmpty()) {
                serverUrls.add(url)
                serverCovers[url] = s.optString("coverUrl")
            }
            if (name.isNotEmpty()) serverNames.add(normTitle(name))
        }

        val local = appDb.bookDao.all
            .filter { it.bookUrl.isNotEmpty() && !it.bookUrl.startsWith("file:") && it.name.isNotBlank() }
        val picked = LinkedHashMap<String, Book>()
        var duplicate = 0
        for (book in local) {
            val key = normTitle(book.name)
            val prev = picked[key]
            if (prev == null) {
                picked[key] = book
            } else {
                duplicate++
                if (isBetterCopy(book, prev)) picked[key] = book
            }
        }

        val toPush = ArrayList<Book>()
        val toProgress = ArrayList<Book>()
        val toCover = ArrayList<Book>()
        var skippedName = 0
        for (book in picked.values) {
            val key = normTitle(book.name)
            if (serverUrls.contains(book.bookUrl)) {
                toProgress.add(book)
                if (needsCoverFix(serverCovers[book.bookUrl])) {
                    if (localCoverData(book) != null) {
                        toCover.add(book)
                    } else if (hasCover(book)) {
                        toPush.add(book)
                    }
                }
            } else if (serverNames.contains(key)) {
                skippedName++
            } else {
                toPush.add(book)
                toProgress.add(book)
                if (localCoverData(book) != null) toCover.add(book)
            }
        }

        var added = 0
        var coverFixes = 0
        var progressCount = 0
        var failed = ""
        if (toPush.isNotEmpty()) {
            runCatching {
                added = pushBooks(base, token, toPush)
            }.onFailure { failed = FnReaderRemote.describeError(it) }
        }
        if (toCover.isNotEmpty()) {
            runCatching {
                coverFixes = pushCovers(base, token, toCover)
            }.onFailure { if (failed.isEmpty()) failed = FnReaderRemote.describeError(it) }
        }
        if (toProgress.isNotEmpty()) {
            runCatching {
                progressCount = pushProgress(base, token, toProgress)
            }.onFailure { if (failed.isEmpty()) failed = FnReaderRemote.describeError(it) }
        }

        val parts = ArrayList<String>()
        if (added > 0) parts.add("回传新书 $added 本")
        if (progressCount > 0) parts.add("回传进度 $progressCount 本")
        if (coverFixes > 0) parts.add("补封面 $coverFixes 本")
        if (skippedName > 0) parts.add("重名跳过 $skippedName 本")
        if (duplicate > 0) parts.add("本机重复 $duplicate 本")
        if (failed.isNotEmpty()) parts.add("回传失败：$failed")
        return if (parts.isEmpty()) "" else "推送：" + parts.joinToString("　")
    }

    private suspend fun pushBooks(base: String, token: String, books: Collection<Book>): Int {
        var added = 0
        for (batch in books.chunked(20)) {
            val arr = JSONArray()
            batch.forEach { arr.put(bookJson(it)) }
            val payload = JSONObject().apply { put("books", arr) }.toString()
            val r = FnReaderRemote.fetch("POST", "$base/api/m/books", token, payload, 60000)
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            added += runCatching { JSONObject(r.second).optInt("added") }.getOrDefault(0)
        }
        return added
    }

    /** 清单 §5：独立封面上传接口，data 为 data:image/...;base64, 形式 */
    private suspend fun pushCovers(base: String, token: String, books: Collection<Book>): Int {
        if (appCtx.getPrefString(KEY_NO_COVER_API, "0") == "1") return 0
        var ok = 0
        for (book in books) {
            val (mime, b64) = localCoverData(book) ?: continue
            val payload = JSONObject().apply {
                put("bookUrl", book.bookUrl)
                put("mime", mime)
                put("data", "data:$mime;base64,$b64")
            }.toString()
            val r = FnReaderRemote.fetch("POST", "$base/api/m/cover", token, payload, 120000)
            if (r.first == 404) {
                appCtx.putPrefString(KEY_NO_COVER_API, "1")
                break
            }
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            ok++
        }
        return ok
    }

    /** 只推水位之后变过的进度；成功后把水位推进到本批最大 durChapterTime */
    private suspend fun pushProgress(base: String, token: String, books: Collection<Book>): Int {
        val last = appCtx.getPrefString(KEY_PROGRESS_SYNCED, "0")?.toLongOrNull() ?: 0L
        val changed = books.filter { it.durChapterTime > last }
        if (changed.isEmpty()) return 0
        var updated = 0
        var maxTime = last
        for (batch in changed.chunked(50)) {
            val items = JSONArray()
            batch.forEach { book ->
                items.put(JSONObject().apply {
                    put("bookUrl", book.bookUrl)
                    put("name", book.name)
                    put("author", book.author)
                    put("index", book.durChapterIndex)
                    put("pos", book.durChapterPos)
                    put("title", book.durChapterTitle.orEmpty())
                })
                if (book.durChapterTime > maxTime) maxTime = book.durChapterTime
            }
            val payload = JSONObject().apply { put("items", items) }.toString()
            val r = FnReaderRemote.fetch("POST", "$base/api/m/progress/batch", token, payload, 60000)
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            updated += runCatching { JSONObject(r.second).optInt("updated", batch.size) }.getOrDefault(batch.size)
        }
        appCtx.putPrefString(KEY_PROGRESS_SYNCED, maxTime.toString())
        return updated
    }

    private suspend fun bookJson(book: Book): JSONObject {
        val (origin, originName) = originOf(book)
        return JSONObject().apply {
            put("bookUrl", book.bookUrl)
            put("name", book.name)
            put("author", book.author)
            put("origin", origin)
            if (originName.isNotEmpty()) put("originName", originName)
            remoteCover(book.coverUrl)?.let { put("coverUrl", it) }
            localCoverData(book)?.let { (mime, data) ->
                put("coverMime", mime)
                put("coverData", data)
            }
            book.intro?.takeIf { it.isNotEmpty() }?.let { put("intro", it.take(500)) }
            if (book.totalChapterNum > 0) put("totalChapterNum", book.totalChapterNum)
            put("index", book.durChapterIndex)
            book.durChapterTitle?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        }
    }

    private suspend fun originOf(book: Book): Pair<String, String> {
        val direct = appDb.bookSourceDao.getBookSource(book.origin)
        if (direct != null) return direct.bookSourceUrl to direct.bookSourceName
        val host = runCatching { URL(book.bookUrl).host }.getOrNull() ?: return "" to ""
        val matched = appDb.bookSourceDao.all.firstOrNull {
            runCatching { URL(it.bookSourceUrl).host }.getOrNull() == host
        } ?: return "" to ""
        return matched.bookSourceUrl to matched.bookSourceName
    }

    private fun remoteCover(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun needsCoverFix(serverCover: String?): Boolean =
        serverCover.orEmpty().trim().let { it.isEmpty() || !it.contains("/api/covers/") }

    private fun hasCover(book: Book): Boolean =
        remoteCover(book.coverUrl) != null || localCoverData(book) != null

    /** 本机封面文件（阅读把用户选的封面存在本地路径），转 base64 直接走 coverData */
    private fun localCoverData(book: Book): Pair<String, String>? {
        val url = book.coverUrl?.trim().orEmpty()
        if (url.isEmpty() || remoteCover(url) != null || url.startsWith("content://", true)) return null
        val file = File(url.removePrefix("file://"))
        if (!file.isFile || file.length() <= 0 || file.length() > 8L * 1024 * 1024) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> return null
        }
        return mime to Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 加书/改书后 2 秒内推一次；连续落库会被后一次取消，避免重复请求 */
    fun scheduleBookPush(book: Book) {
        if (!isConfigured()) return
        synchronized(this) {
            bookPushJob?.cancel()
            bookPushJob = Coroutine.async(context = Dispatchers.IO) {
                delay(2000)
                val base = serverBase() ?: return@async
                val token = serverToken() ?: return@async
                runCatching { pushBooks(base, token, listOf(book)) }
                Unit
            }
        }
    }

    /** 翻页/退出/切后台 3 秒防抖推当前进度 */
    fun scheduleProgressPush(book: Book) {
        if (!isConfigured()) return
        synchronized(this) {
            progressPushJob?.cancel()
            progressPushJob = Coroutine.async(context = Dispatchers.IO) {
                delay(3000)
                val base = serverBase() ?: return@async
                val token = serverToken() ?: return@async
                val payload = JSONObject().apply {
                    put("bookUrl", book.bookUrl)
                    put("name", book.name)
                    put("author", book.author)
                    put("index", book.durChapterIndex)
                    put("pos", book.durChapterPos)
                    put("title", book.durChapterTitle.orEmpty())
                }.toString()
                val r = runCatching {
                    FnReaderRemote.fetch("POST", "$base/api/m/progress", token, payload, 30000)
                }.getOrNull()
                if (r != null && r.first in 200..299) {
                    val old = appCtx.getPrefString(KEY_PROGRESS_SYNCED, "0")?.toLongOrNull() ?: 0L
                    if (book.durChapterTime > old) {
                        appCtx.putPrefString(KEY_PROGRESS_SYNCED, book.durChapterTime.toString())
                    }
                }
                Unit
            }
        }
    }

    private fun isConfigured(): Boolean {
        if (appCtx.getPrefString(FnReaderConfigDialog.KEY_BRIDGE, "0") != "1") return false
        return serverBase() != null && serverToken() != null
    }

    private fun serverBase(): String? = FnReaderRemote.normalizeBase(
        appCtx.getPrefString(FnReaderConfigDialog.KEY_SERVER, "").orEmpty()
    ).takeIf { it.isNotBlank() }

    private fun serverToken(): String? =
        appCtx.getPrefString(FnReaderConfigDialog.KEY_TOKEN, "").orEmpty().takeIf { it.isNotBlank() }

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
        // 404 只跳过 6 小时：旧版服务端/临时 404 不能让书源同步永久失效
        val skipUntil = appCtx.getPrefString(KEY_NO_SOURCE_SYNC, "0")?.toLongOrNull() ?: 0L
        if (skipUntil > System.currentTimeMillis()) return ""
        val since = appCtx.getPrefString(FnReaderConfigDialog.KEY_SOURCES_SINCE, "0")?.toLongOrNull() ?: 0L
        val parts = appDb.bookSourceDao.flowAll().first()
        val currentUrls = parts.map { normSourceUrl(it.bookSourceUrl) }.toHashSet()
        return try {
            val text = if (since <= 0L) {
                firstSync(base, token, currentUrls)
            } else {
                incrementalSync(base, token, since, currentUrls)
            }
            appCtx.putPrefString(KEY_NO_SOURCE_SYNC, "0")
            text
        } catch (e: Exception) {
            val msg = FnReaderRemote.describeError(e)
            if (msg.contains("404")) {
                appCtx.putPrefString(KEY_NO_SOURCE_SYNC, (System.currentTimeMillis() + 6 * 3600 * 1000L).toString())
                "书源同步：服务端没有这个接口，6 小时后重试"
            } else {
                "书源同步失败：$msg"
            }
        }
    }

    /** 首次接入：按 bookSourceUrl 合并上传（POST /api/m/sources），再取一次 serverTime 当 since */
    private suspend fun firstSync(base: String, token: String, currentUrls: Set<String>): String {
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
        saveSourceSnapshot(currentUrls)
        return "书源首次合并：上传 ${all.size} 个（新增 ${obj.optInt("added")}　更新 ${obj.optInt("updated")}）"
    }

    /** 之后每轮：本机改过/删掉的推上去，服务端更进的拉回来 */
    private suspend fun incrementalSync(
        base: String,
        token: String,
        since: Long,
        currentUrls: Set<String>
    ): String {
        val parts = appDb.bookSourceDao.flowAll().first()
        val changed = ArrayList<BookSource>()
        parts.forEach { p ->
            if (p.lastUpdateTime > since) {
                appDb.bookSourceDao.getBookSource(p.bookSourceUrl)?.let { changed.add(it) }
            }
        }
        // §17.2：本机删掉的源走 deleted，带当前时间戳；只靠 GET 会把它又拉回来
        val goneUrls = readSourceSnapshot().filter { it.isNotEmpty() && it !in currentUrls }
        var pulled = 0
        var removed = 0
        var total = 0
        var pushed = 0
        var pushedDeleted = 0
        val serverTime: Long
        if (changed.isEmpty() && goneUrls.isEmpty()) {
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
                put("deleted", JSONArray().also { arr ->
                    goneUrls.forEach { url ->
                        arr.put(JSONObject().apply {
                            put("bookSourceUrl", url)
                            put("lastUpdateTime", System.currentTimeMillis())
                        })
                    }
                })
            }.toString()
            val r = FnReaderRemote.fetch("POST", "$base/api/m/sources/sync", token, payload, 180000)
            if (r.first !in 200..299) throw IllegalStateException(FnReaderRemote.explainCode(r.first, r.second))
            val obj = JSONObject(r.second)
            pushed = changed.size
            pushedDeleted = goneUrls.size
            pulled = mergePull(obj.optJSONArray("pull"))
            removed = applyPullDeleted(obj.optJSONArray("pullDeleted"))
            total = obj.optInt("total")
            serverTime = obj.optLong("serverTime", System.currentTimeMillis())
        }
        appCtx.putPrefString(FnReaderConfigDialog.KEY_SOURCES_SINCE, serverTime.toString())
        saveSourceSnapshot(currentUrls)
        return buildString {
            append("书源同步：推 $pushed 个　拉 $pulled 个")
            if (pushedDeleted > 0) append("　推送删除 $pushedDeleted 个")
            if (removed > 0) append("　服务端删 $removed 个")
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

    /** 上次同步成功时本机书源地址快照 */
    private fun readSourceSnapshot(): Set<String> = runCatching {
        val raw = appCtx.getPrefString(KEY_SOURCES_SNAPSHOT, "").orEmpty()
        if (raw.isEmpty()) emptySet() else {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { normSourceUrl(arr.optString(it)) }.toSet()
        }
    }.getOrDefault(emptySet())

    private fun saveSourceSnapshot(urls: Collection<String>) {
        appCtx.putPrefString(
            KEY_SOURCES_SNAPSHOT,
            JSONArray().also { arr -> urls.forEach { arr.put(it) } }.toString()
        )
    }

    private fun normSourceUrl(url: String?): String =
        (url ?: "").trim().trimEnd('/').lowercase()

    private fun sourceJson(src: BookSource): JSONObject =
        JSONObject(GSON.toJson(src)).apply {
            put("bookSourceUrl", src.bookSourceUrl)
            put("lastUpdateTime", if (src.lastUpdateTime > 0) src.lastUpdateTime else System.currentTimeMillis())
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
