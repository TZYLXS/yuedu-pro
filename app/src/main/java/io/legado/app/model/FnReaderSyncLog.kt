package io.legado.app.model

import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书源同步记录（最近 50 条）。
 *
 * 通知栏的同步结果会被下一轮覆盖，这里把每轮结果落到 SharedPreferences，
 * App 内「同步记录」页直接读它；杀进程/重启后仍在。
 */
object FnReaderSyncLog {

    private const val KEY = "fnReaderSyncLog"
    private const val MAX = 50
    private val lock = Any()

    data class Entry(
        val at: Long,
        val uploaded: Int,
        val pulled: Int,
        val deleted: Int,
        val total: Int,
        val durationMs: Long,
        val error: String = "",
        val note: String = ""
    ) {
        val direction: String
            get() {
                val dirs = ArrayList<String>(3)
                if (uploaded > 0) dirs.add("推")
                if (pulled > 0) dirs.add("拉")
                if (deleted > 0) dirs.add("删")
                if (dirs.isEmpty()) dirs.add(if (error.isNotEmpty()) "失败" else "无变化")
                return dirs.joinToString("/")
            }

        fun countText(): String =
            "上传 $uploaded　下载 $pulled　删除 $deleted　飞牛总数 $total"

        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at)
            put("uploaded", uploaded)
            put("pulled", pulled)
            put("deleted", deleted)
            put("total", total)
            put("durationMs", durationMs)
            put("error", error)
            put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                at = o.optLong("at"),
                uploaded = o.optInt("uploaded"),
                pulled = o.optInt("pulled"),
                deleted = o.optInt("deleted"),
                total = o.optInt("total"),
                durationMs = o.optLong("durationMs"),
                error = o.optString("error"),
                note = o.optString("note")
            )
        }
    }

    fun add(entry: Entry) {
        synchronized(lock) {
            val list = all().toMutableList()
            list.add(0, entry)
            while (list.size > MAX) list.removeAt(list.size - 1)
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            appCtx.putPrefString(KEY, arr.toString())
        }
    }

    fun all(): List<Entry> = synchronized(lock) {
        val raw = appCtx.getPrefString(KEY, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Entry.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        synchronized(lock) { appCtx.putPrefString(KEY, "[]") }
    }

    fun formatTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
}
