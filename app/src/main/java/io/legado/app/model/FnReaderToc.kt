package io.legado.app.model

import io.legado.app.data.entities.BookChapter

/** 手机 JVM 侧 TOC 预处理，旧客户端由服务端 Java 21 做同样处理。 */
object FnReaderToc {
    data class Entry(val title: String, val url: String, val order: Int, val number: Int)

    private val numberPattern = Regex("第\\s*([零一二三四五六七八九十百千万两\\d]+)\\s*[章节回卷]")
    private val chapterPattern = Regex("^\\s*第\\s*[零一二三四五六七八九十百千万两\\d]+\\s*[章节回卷]")
    private val digits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)

    fun normalize(chapters: List<BookChapter>): List<Entry> {
        val seenUrl = hashSetOf<String>()
        val seenTitle = hashSetOf<String>()
        val deduped = arrayListOf<Entry>()
        chapters.forEachIndexed { index, chapter ->
            val url = runCatching { chapter.getAbsoluteURL() }.getOrElse { chapter.url }.trim()
            val title = chapter.title.trim()
            val titleKey = title.filterNot { it.isWhitespace() }
            if (url.isNotEmpty() && !seenUrl.add(url)) return@forEachIndexed
            if (titleKey.isNotEmpty() && !seenTitle.add(titleKey)) return@forEachIndexed
            deduped.add(Entry(title, url, index, chapterNumber(title)))
        }
        val standard = deduped.count { chapterPattern.containsMatchIn(it.title) }
        val filtered = if (standard >= deduped.size * 0.7) {
            deduped.filter { chapterPattern.containsMatchIn(it.title) }
        } else deduped
        val known = filtered.count { it.number > 0 }
        if (known < filtered.size * 0.6) return filtered
        return filtered.sortedWith(compareBy<Entry> { if (it.number > 0) it.number else Int.MAX_VALUE }.thenBy { it.order })
    }

    private fun chapterNumber(title: String): Int {
        val match = numberPattern.find(normalizeCircled(title)) ?: return 0
        val raw = match.groupValues[1]
        return raw.toIntOrNull() ?: cnToNumber(raw)
    }

    private fun cnToNumber(text: String): Int {
        var total = 0
        var section = 0
        var number = 0
        text.forEach { ch ->
            when {
                ch in '0'..'9' -> number = number * 10 + (ch - '0')
                digits.containsKey(ch) -> number = digits.getValue(ch)
                units.containsKey(ch) -> {
                    val unit = units.getValue(ch)
                    if (unit == 10000) {
                        section = (section + number) * unit
                        total += section
                        section = 0
                    } else section += (if (number == 0) 1 else number) * unit
                    number = 0
                }
            }
        }
        return total + section + number
    }

    private fun normalizeCircled(text: String): String {
        val result = StringBuilder()
        text.forEach { ch ->
            val code = ch.code
            when (code) {
                in 0x2460..0x2473 -> result.append(code - 0x2460 + 1)
                in 0x3251..0x325f -> result.append(code - 0x3251 + 21)
                else -> result.append(ch)
            }
        }
        return result.toString()
    }
}
