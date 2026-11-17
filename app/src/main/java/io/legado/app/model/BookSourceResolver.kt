package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import java.net.URI

/**
 * 修复「手机缓存」导入产生的无书源书籍。
 *
 * 旧数据里 origin 为空、originName 为「手机缓存」，打开时必然报
 * 「未找到书源，可能已被删除」。这里按 bookUrl 的主机名找回启用书源：
 * 1. 先精确匹配主机；
 * 2. 主机不一致时按站点主域匹配，例如 3yt.info 对应 3yt.com/3yt.la；
 * 3. 命中后把 origin/originName 写回书籍，后续打开不再重复查找。
 */
object BookSourceResolver {

    fun resolve(bookUrl: String): BookSource? {
        val host = hostOf(bookUrl) ?: return null
        val enabled = appDb.bookSourceDao.allEnabled
        enabled.firstOrNull { hostOf(it.bookSourceUrl) == host }?.let { return it }
        val key = siteKey(host) ?: return null
        return enabled
            .filter { siteKey(hostOf(it.bookSourceUrl)) == key }
            .maxWithOrNull(compareBy<BookSource> { it.weight }.thenBy { -it.customOrder })
    }

    fun bind(book: Book): BookSource? {
        val current = appDb.bookSourceDao.getBookSource(book.origin)
        if (current != null) return current
        val found = resolve(book.bookUrl) ?: return null
        book.origin = found.bookSourceUrl
        book.originName = found.bookSourceName
        book.save()
        return found
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()

    private fun siteKey(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val parts = host.split('.')
        if (parts.size < 2) return null
        val key = if (parts.size >= 3) parts[parts.size - 2] else parts[0]
        return key.takeIf { it.length >= 3 }
    }
}
