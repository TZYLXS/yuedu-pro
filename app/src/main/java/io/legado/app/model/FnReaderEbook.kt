package io.legado.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 本地电子书解析（服务端软总线 ebook-parse 任务）
 *
 * 只负责 EPUB / MOBI 两种格式，产出服务端约定的 fnreader.ebook.v1 结构：
 *   part0 = { schema, sha1, format, meta, toc[], stats }
 *   partN = { schema, sha1, seq, total, chapters[{index,id,title,paragraphs[{k,t}]}] }
 * 段落 k 取值：p / h1~h6 / quote / hr / img。
 * PDF / TXT 由服务端本机解析，不会派到手机。
 */
object FnReaderEbook {

    const val SCHEMA = "fnreader.ebook.v1"

    data class Block(val k: String, val t: String)

    data class Chapter(
        val title: String,
        val level: Int,
        val paragraphs: List<Block>
    )

    data class Parsed(
        val meta: JSONObject,
        val chapters: List<Chapter>,
        val chars: Int
    )

    class EbookException(val code: String, message: String) : Exception(message)

    private val blockTags = setOf(
        "p", "div", "section", "article", "li", "blockquote", "pre", "td", "dd", "dt",
        "figcaption", "header", "footer", "aside", "main", "nav", "center"
    )
    private val headingRe = Regex("^h[1-6]$")

    fun parse(file: File, format: String, fallbackName: String): Parsed = when (format.lowercase()) {
        "epub" -> parseEpub(file, fallbackName)
        "mobi", "azw" -> parseMobi(file, fallbackName)
        else -> throw EbookException("unsupported", "手机端不解析 $format，交给服务端")
    }

    // ---------------- EPUB ----------------

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class TocEntry(val title: String, val level: Int)

    private fun parseEpub(file: File, fallbackName: String): Parsed {
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw EbookException("corrupt", "不是有效的 EPUB：${e.message}")
        }
        zip.use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            fun read(name: String): ByteArray? {
                val entry = z.getEntry(name) ?: z.getEntry(decodePath(name)) ?: return null
                return z.getInputStream(entry).use { it.readBytes() }
            }

            val enc = read("META-INF/encryption.xml")
            if (enc != null && enc.decodeToString().contains("<EncryptedReference")) {
                throw EbookException("drm", "这本书带 DRM 加密")
            }
            val container = read("META-INF/container.xml")
                ?: throw EbookException("corrupt", "EPUB 缺少 container.xml")
            val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"")
                .find(container.decodeToString())?.groupValues?.get(1)
                ?: throw EbookException("corrupt", "container.xml 里没有 OPF 路径")
            val opfBytes = read(opfPath) ?: throw EbookException("corrupt", "OPF 文件不存在")
            val opfText = opfBytes.decodeToString()
            val baseDir = opfPath.substringBeforeLast('/', "")

            val manifest = HashMap<String, ManifestItem>()
            Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opfText).forEach { m ->
                val tag = m.value
                val id = attr(tag, "id") ?: return@forEach
                val href = attr(tag, "href") ?: return@forEach
                manifest[id] = ManifestItem(
                    id = id,
                    href = href,
                    mediaType = attr(tag, "media-type") ?: "",
                    properties = attr(tag, "properties") ?: ""
                )
            }
            val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opfText)
                .mapNotNull { attr(it.value, "idref")?.let(manifest::get) }
                .toList()
            if (spine.isEmpty()) throw EbookException("corrupt", "EPUB spine 为空")

            val tocMap = LinkedHashMap<String, TocEntry>()
            // 新版 EPUB3：properties 里带 nav
            val navItem = manifest.values.firstOrNull { it.properties.contains("nav") }
            if (navItem != null) {
                read(resolve(baseDir, navItem.href))?.let { parseNav(it.decodeToString(), baseDir, tocMap) }
            }
            // 兼容 EPUB2 NCX
            if (tocMap.isEmpty()) {
                val ncxItem = manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                if (ncxItem != null) {
                    read(resolve(baseDir, ncxItem.href))?.let { parseNcx(it.decodeToString(), baseDir, tocMap) }
                }
            }

            val meta = JSONObject().apply {
                put("title", clean(xmlText(Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>").find(opfText)?.groupValues?.get(1)) ?: fallbackName))
                put("author", clean(xmlText(Regex("<dc:creator[^>]*>([\\s\\S]*?)</dc:creator>").find(opfText)?.groupValues?.get(1))))
                put("language", clean(xmlText(Regex("<dc:language[^>]*>([\\s\\S]*?)</dc:language>").find(opfText)?.groupValues?.get(1))))
                put("publisher", clean(xmlText(Regex("<dc:publisher[^>]*>([\\s\\S]*?)</dc:publisher>").find(opfText)?.groupValues?.get(1))))
                put("publishedAt", clean(xmlText(Regex("<dc:date[^>]*>([\\s\\S]*?)</dc:date>").find(opfText)?.groupValues?.get(1))))
                put("isbn", clean(xmlText(Regex("<dc:identifier[^>]*>([\\s\\S]*?)</dc:identifier>").find(opfText)?.groupValues?.get(1))))
                put("description", clean(xmlText(Regex("<dc:description[^>]*>([\\s\\S]*?)</dc:description>").find(opfText)?.groupValues?.get(1))))
            }

            val chapters = ArrayList<Chapter>()
            for (item in spine) {
                if (chapters.size >= 3000) break
                val isHtml = item.mediaType.contains("html") || item.mediaType.contains("xml") ||
                    item.href.endsWith(".xhtml", true) || item.href.endsWith(".html", true) || item.href.endsWith(".htm", true)
                if (!isHtml) continue
                val path = resolve(baseDir, item.href)
                val html = read(path)?.decodeToString() ?: continue
                val toc = tocMap[path]
                var title = toc?.title.orEmpty()
                if (title.isEmpty()) title = firstHeadingTitle(html)
                if (title.isEmpty()) title = "第 ${chapters.size + 1} 章"
                val paragraphs = htmlToBlocks(html, title)
                if (paragraphs.isEmpty()) continue
                chapters.add(Chapter(title, toc?.level ?: 1, paragraphs))
            }
            if (chapters.isEmpty()) throw EbookException("corrupt", "EPUB 正文为空")
            return Parsed(meta, chapters, chapters.sumOf { ch -> ch.paragraphs.sumOf { it.t.length } })
        }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: Regex("\\b$name\\s*=\\s*'([^']*)'").find(tag)?.groupValues?.get(1)

    private fun xmlText(s: String?): String {
        if (s == null) return ""
        return Jsoup.parse("<x>$s</x>", "", Parser.xmlParser()).selectFirst("x")?.text().orEmpty()
    }

    private fun parseNav(html: String, baseDir: String, out: MutableMap<String, TocEntry>) {
        val doc = Jsoup.parse(html)
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") }
            ?: doc.selectFirst("nav")
            ?: return
        nav.select("a[href]").forEach { a ->
            val href = a.attr("href").substringBefore("#")
            if (href.isEmpty()) return@forEach
            var level = 1
            var p: Element? = a.parent()
            while (p != null && p != nav) {
                if (p.tagName().equals("li", true)) level++
                p = p.parent()
            }
            out[resolve(baseDir, href)] = TocEntry(clean(a.text()), level)
        }
    }

    private fun parseNcx(xml: String, baseDir: String, out: MutableMap<String, TocEntry>) {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        doc.select("navPoint").forEach { navPoint ->
            val src = navPoint.selectFirst("src")?.text()?.substringBefore("#") ?: return@forEach
            val title = navPoint.selectFirst("navLabel text")?.text()
                ?: navPoint.selectFirst("title")?.text().orEmpty()
            val level = navPoint.parents().count { it.tagName().equals("navPoint", true) } + 1
            out[resolve(baseDir, src)] = TocEntry(clean(title), level)
        }
    }

    private fun firstHeadingTitle(html: String): String {
        val doc = Jsoup.parse(html)
        return doc.select("h1, h2").firstOrNull()?.text()?.let(::clean).orEmpty()
    }

    /** 相对路径归一化：a/b/../c.xhtml → a/c.xhtml */
    private fun resolve(baseDir: String, href: String): String {
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) baseDir.split('/').filter { it.isNotEmpty() }.forEach(parts::add)
        href.substringBefore("#").split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodePath(name: String): String = runCatching {
        java.net.URLDecoder.decode(name, "UTF-8")
    }.getOrDefault(name)

    // ---------------- MOBI ----------------

    private fun parseMobi(file: File, fallbackName: String): Parsed {
        val buf = file.readBytes()
        if (buf.size < 100) throw EbookException("corrupt", "文件太小")
        val type = buf.copyOfRange(60, 64).toString(Charsets.ISO_8859_1)
        val creator = buf.copyOfRange(64, 68).toString(Charsets.ISO_8859_1)
        if (type != "BOOK" || creator != "MOBI") {
            throw EbookException("unsupported", "不是 MOBI 文件（type=$type, creator=$creator）")
        }
        val numRecords = readU16(buf, 76)
        if (numRecords <= 0) throw EbookException("corrupt", "记录表为空")
        val offsets = IntArray(numRecords) { readU32(buf, 78 + it * 8) }

        fun rec(i: Int): ByteArray {
            if (i < 0 || i >= offsets.size) return ByteArray(0)
            val start = offsets[i]
            val end = if (i + 1 < offsets.size) offsets[i + 1] else buf.size
            if (start < 0 || start > buf.size || end < start) return ByteArray(0)
            return buf.copyOfRange(start, minOf(end, buf.size))
        }

        val record0 = rec(0)
        if (record0.size < 16) throw EbookException("corrupt", "PalmDOC 头缺失")
        val compression = readU16(record0, 0)
        val textLength = readU32(record0, 4)
        val recordCount = readU16(record0, 8)
        val encryption = readU16(record0, 12)
        if (encryption != 0) throw EbookException("drm", "这本书带 DRM 加密")

        var encoding = 1252
        var version = 6
        var headerLen = 0
        var fullName = ""
        var exthTitle = ""
        var exthAuthor = ""
        var exthLanguage = ""
        if (record0.copyOfRange(16, 20).toString(Charsets.ISO_8859_1) == "MOBI") {
            headerLen = readU32(record0, 20)
            encoding = readU32(record0, 28)
            version = readU32(record0, 36)
            val nameOff = readU32(record0, 16 + 0x54)
            val nameLen = readU32(record0, 16 + 0x58)
            if (nameOff in 0 until record0.size && nameLen > 0 && nameOff + nameLen <= record0.size) {
                fullName = decodeText(record0.copyOfRange(nameOff, nameOff + nameLen), encoding)
            }
            val exth = readExth(record0, headerLen)
            exthAuthor = exth["author"].orEmpty()
            exthTitle = exth["title"].orEmpty()
            exthLanguage = exth["language"].orEmpty()
        }
        if (version >= 8) throw EbookException("kf8-unsupported", "这是 KF8（AZW3）结构，暂不支持，请转 EPUB")

        if (compression != 1 && compression != 2) {
            throw EbookException("unsupported", "不支持的 MOBI 压缩方式：$compression")
        }
        val textRecords = (1..minOf(recordCount, offsets.size - 1)).map { rec(it) }
        if (textRecords.isEmpty()) throw EbookException("corrupt", "没有正文记录")

        val textBytes = decompress(textRecords, compression, textLength)
        val html = decodeText(textBytes, encoding)
        if (html.isBlank()) throw EbookException("corrupt", "正文解压为空")

        val chapters = splitMobiChapters(html)
        val meta = JSONObject().apply {
            put("title", clean(fullName.ifEmpty { exthTitle }.ifEmpty { fallbackName }))
            put("author", clean(exthAuthor))
            put("language", clean(exthLanguage))
        }
        return Parsed(meta, chapters, chapters.sumOf { ch -> ch.paragraphs.sumOf { it.t.length } })
    }

    private fun splitMobiChapters(html: String): List<Chapter> {
        val heading = Regex("<h([1-3])\\b[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
        val marks = heading.findAll(html).toList()
        if (marks.size >= 2) {
            val chapters = ArrayList<Chapter>()
            val firstStart = marks.first().range.first
            if (firstStart > 0) {
                val preface = html.substring(0, firstStart)
                if (clean(Jsoup.parseBodyFragment(preface).text()).length > 200) {
                    chapters.add(Chapter("前言", 1, htmlToBlocks(preface, "前言")))
                }
            }
            marks.forEachIndexed { i, m ->
                val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
                val rawTitle = m.groupValues[2]
                val title = clean(Jsoup.parseBodyFragment(rawTitle).text())
                    .ifEmpty { "第 ${chapters.size + 1} 章" }
                val body = html.substring(m.range.last + 1, end)
                chapters.add(Chapter(title, m.groupValues[1].toIntOrNull() ?: 1, htmlToBlocks(body, title)))
            }
            return chapters
        }
        val parts = html.split(Regex("<mbp:pagebreak\\b[^>]*/?>|</?mbp:pagebreak\\b[^>]*>", RegexOption.IGNORE_CASE))
            .filter { it.trim().isNotEmpty() }
        if (parts.size > 5) {
            val chapters = ArrayList<Chapter>()
            val sb = StringBuilder()
            var chars = 0
            for (part in parts) {
                sb.append(part)
                chars += clean(Jsoup.parseBodyFragment(part).text()).length
                if (chars > 200000) {
                    val n = chapters.size + 1
                    chapters.add(Chapter("第 $n 章", 1, htmlToBlocks(sb.toString(), "")))
                    sb.setLength(0)
                    chars = 0
                }
            }
            if (sb.toString().trim().isNotEmpty()) {
                val n = chapters.size + 1
                chapters.add(Chapter("第 $n 章", 1, htmlToBlocks(sb.toString(), "")))
            }
            if (chapters.size > 1) return chapters
        }
        return listOf(Chapter("", 1, htmlToBlocks(html, "")))
    }

    private class ByteBuf(initial: Int) {
        var data = ByteArray(initial.coerceAtLeast(4096))
        var size = 0
        fun write(b: Int) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = b.toByte()
        }
        fun write(bytes: ByteArray) {
            for (b in bytes) write(b.toInt() and 0xff)
        }
        fun copyBack(distance: Int, length: Int) {
            var src = size - distance
            if (src < 0) src = 0
            repeat(length) {
                if (src >= size) return
                write(data[src].toInt() and 0xff)
                src++
            }
        }
        fun toBytes(limit: Int): ByteArray = data.copyOfRange(0, minOf(size, if (limit > 0) limit else size))
    }

    /** PalmDOC：compression=1 原样，2 为 LZ77；反向引用可跨记录 */
    private fun decompress(records: List<ByteArray>, compression: Int, textLength: Int): ByteArray {
        val out = ByteBuf(textLength + 4096)
        for (rec in records) {
            if (compression == 1) {
                out.write(rec)
                continue
            }
            var i = 2
            while (i < rec.size) {
                val b = rec[i].toInt() and 0xff
                i++
                when {
                    b == 0 -> if (i < rec.size) out.write(rec[i++].toInt() and 0xff)
                    b <= 8 -> repeat(b) { if (i < rec.size) out.write(rec[i++].toInt() and 0xff) }
                    b <= 0x7f -> out.write(b)
                    b <= 0xbf -> {
                        val next = if (i < rec.size) rec[i++].toInt() and 0xff else 0
                        val length = (b and 0x07) + 3
                        val distance = ((b and 0x78) shl 5) or next
                        out.copyBack(distance, length)
                    }
                    else -> {
                        out.write(0x20)
                        out.write(b xor 0x80)
                    }
                }
            }
            if (textLength > 0 && out.size >= textLength) break
        }
        return out.toBytes(textLength)
    }

    private fun readExth(record0: ByteArray, mobiHeaderLen: Int): Map<String, String> {
        val out = HashMap<String, String>()
        val from = 16 + mobiHeaderLen
        var at = -1
        for (i in from..minOf(record0.size - 4, from + 1024)) {
            if (record0.copyOfRange(i, i + 4).toString(Charsets.ISO_8859_1) == "EXTH") {
                at = i
                break
            }
        }
        if (at < 0) return out
        val count = readU32(record0, at + 8)
        var p = at + 12
        repeat(count) {
            if (p + 8 > record0.size) return out
            val type = readU32(record0, p)
            val len = readU32(record0, p + 4)
            if (len < 8 || p + len > record0.size) return out
            val data = record0.copyOfRange(p + 8, p + len).decodeToString()
            when (type) {
                100 -> out["author"] = data
                503 -> out["title"] = data
                524 -> out["language"] = data
            }
            p += len
        }
        return out
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String = when (encoding) {
        65001 -> bytes.decodeToString()
        1252 -> String(bytes, charset("windows-1252"))
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    private fun readU16(buf: ByteArray, at: Int): Int {
        if (at + 2 > buf.size) return 0
        return ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)
    }

    private fun readU32(buf: ByteArray, at: Int): Int {
        if (at + 4 > buf.size) return 0
        return ((buf[at].toInt() and 0xff) shl 24) or
            ((buf[at + 1].toInt() and 0xff) shl 16) or
            ((buf[at + 2].toInt() and 0xff) shl 8) or
            (buf[at + 3].toInt() and 0xff)
    }

    // ---------------- HTML → 段落（与服务端 html.js 同一套规则） ----------------

    fun htmlToBlocks(html: String, dropTitle: String): List<Block> {
        val doc = try {
            Jsoup.parse(html)
        } catch (e: Exception) {
            return emptyList()
        }
        doc.select("script,style,noscript,head,svg").remove()
        val root: Node = doc.body() ?: doc
        val out = ArrayList<Block>()
        val buf = StringBuilder()

        fun flush() {
            val t = clean(buf.toString())
            buf.setLength(0)
            if (t.isNotEmpty()) splitLong(t).forEach { out.add(Block("p", it)) }
        }

        fun walk(node: Node) {
            for (child in node.childNodes()) {
                if (child is TextNode) {
                    buf.append(child.wholeText)
                    continue
                }
                if (child !is Element) continue
                val tag = child.tagName().lowercase()
                when {
                    tag == "br" -> flush()
                    headingRe.matches(tag) -> {
                        flush()
                        val t = clean(child.text())
                        if (t.isNotEmpty()) splitLong(t).forEach { out.add(Block(tag, it)) }
                    }
                    tag == "img" -> {
                        flush()
                        out.add(Block("img", clean(child.attr("alt").ifEmpty { child.attr("title") })))
                    }
                    tag == "hr" -> {
                        flush()
                        out.add(Block("hr", ""))
                    }
                    tag in blockTags -> {
                        val hasBlock = child.children().any {
                            val n = it.tagName().lowercase()
                            n in blockTags || headingRe.matches(n)
                        }
                        if (hasBlock) {
                            flush()
                            walk(child)
                            flush()
                        } else {
                            flush()
                            val kind = if (tag == "blockquote") "quote" else "p"
                            val t = clean(child.text())
                            if (t.isNotEmpty()) splitLong(t).forEach { out.add(Block(kind, it)) }
                        }
                    }
                    else -> walk(child)
                }
            }
        }

        walk(root)
        flush()
        if (dropTitle.isNotBlank()) {
            val first = out.firstOrNull()
            if (first != null && clean(first.t) == clean(dropTitle)) out.removeAt(0)
        }
        return out
    }

    private fun clean(text: String): String = text
        .replace("\u0000", "")
        .replace(Regex("[\\u00a0\\u3000]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun splitLong(text: String, max: Int = 4096): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = -1
            var i = max
            while (i > max / 2) {
                if ("。！？!?.；;".contains(rest[i])) {
                    cut = i + 1
                    break
                }
                i--
            }
            if (cut < 0) cut = max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotEmpty() }
    }

    /** 章节分片：part0 只有 toc/meta，章节按 50 章或约 2MB 一组，total 与 seq 一次算准 */
    fun buildParts(parsed: Parsed, sha1: String, format: String, name: String): List<String> {
        data class Draft(val obj: JSONObject, val size: Int)

        val drafts = ArrayList<Draft>()
        parsed.chapters.forEachIndexed { index, ch ->
            val obj = JSONObject().apply {
                put("index", index)
                put("id", "ch" + (index + 1).toString().padStart(4, '0'))
                put("title", ch.title)
                put("paragraphs", JSONArray().apply {
                    ch.paragraphs.forEach { b ->
                        put(JSONObject().apply {
                            put("k", b.k)
                            put("t", b.t)
                        })
                    }
                })
            }
            drafts.add(Draft(obj, obj.toString().toByteArray(Charsets.UTF_8).size))
        }

        val groups = ArrayList<List<JSONObject>>()
        var group = ArrayList<JSONObject>()
        var groupBytes = 0
        for (d in drafts) {
            if (group.size >= 50 || (groupBytes + d.size > 2 * 1024 * 1024 && group.isNotEmpty())) {
                groups.add(group)
                group = ArrayList()
                groupBytes = 0
            }
            group.add(d.obj)
            groupBytes += d.size
        }
        if (group.isNotEmpty()) groups.add(group)

        val total = 1 + groups.size
        val toc = JSONArray()
        parsed.chapters.forEachIndexed { index, ch ->
            toc.put(JSONObject().apply {
                put("id", "ch" + (index + 1).toString().padStart(4, '0'))
                put("title", ch.title.ifEmpty { "第 ${index + 1} 章" })
                put("level", ch.level.coerceIn(1, 6))
                put("index", index)
            })
        }
        val part0 = JSONObject().apply {
            put("schema", SCHEMA)
            put("sha1", sha1)
            put("format", format)
            put("name", name)
            put("meta", parsed.meta)
            put("toc", toc)
            put("stats", JSONObject().apply {
                put("chapters", parsed.chapters.size)
                put("chars", parsed.chars)
                put("parts", total)
            })
        }.toString()

        val parts = ArrayList<String>()
        parts.add(part0)
        groups.forEachIndexed { i, chapters ->
            parts.add(JSONObject().apply {
                put("schema", SCHEMA)
                put("sha1", sha1)
                put("seq", i + 1)
                put("total", total)
                put("chapters", JSONArray(chapters))
            }.toString())
        }
        return parts
    }

    /**
     * 目录先行（toc-only）：只发 part0，正文等飞牛按需来要。
     *
     * 服务端侧配套改动见 `fnreader-toc-only-server-spec-20261115.md`：
     * part0 里多一个 `tocOnly:true`、`total=1`，服务端据此把书标成 `toc-only`，
     * 不会因为「只收到一片」被 watchdog 判掉线再本地兜底覆盖。
     */
    fun buildTocOnlyPart(parsed: Parsed, sha1: String, format: String, name: String): String {
        val toc = JSONArray()
        parsed.chapters.forEachIndexed { index, ch ->
            toc.put(JSONObject().apply {
                put("id", "ch" + (index + 1).toString().padStart(4, '0'))
                put("title", ch.title.ifEmpty { "第 ${index + 1} 章" })
                put("level", ch.level.coerceIn(1, 6))
                put("index", index)
            })
        }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("sha1", sha1)
            put("format", format)
            put("name", name)
            put("meta", parsed.meta)
            put("toc", toc)
            put("stats", JSONObject().apply {
                put("chapters", parsed.chapters.size)
                put("chars", parsed.chars)
                put("parts", 1)
            })
            put("tocOnly", true)
        }.toString()
    }

    /**
     * 单章按需回传：`POST /api/m/ebook/chapter?sha1=&index=` 的请求体。
     * 章节号越界返回 null，由调用方决定报错。
     */
    fun buildChapter(parsed: Parsed, sha1: String, index: Int): String? {
        val ch = parsed.chapters.getOrNull(index) ?: return null
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("sha1", sha1)
            put("index", index)
            put("id", "ch" + (index + 1).toString().padStart(4, '0'))
            put("title", ch.title.ifEmpty { "第 ${index + 1} 章" })
            put("paragraphs", JSONArray().apply {
                ch.paragraphs.forEach { b ->
                    put(JSONObject().apply {
                        put("k", b.k)
                        put("t", b.t)
                    })
                }
            })
        }.toString()
    }
}
