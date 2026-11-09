package io.legado.app.model.rss

import io.legado.app.data.entities.RssArticle
import io.legado.app.model.Debug
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Suppress("unused")
object RssParserDefault {

    @Throws(XmlPullParserException::class, IOException::class)
    fun parseXML(
        sortName: String,
        xml: String,
        sourceUrl: String
    ): Pair<MutableList<RssArticle>, String?> {

        val articleList = mutableListOf<RssArticle>()
        var currentArticle = RssArticle()

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false

        val xmlPullParser = factory.newPullParser()
        xmlPullParser.setInput(StringReader(xml))

        // A flag just to be sure of the correct parsing
        var insideItem = false

        var eventType = xmlPullParser.eventType

        // Start parsing the xml
        loop@ while (eventType != XmlPullParser.END_DOCUMENT) {

            // Start parsing the item
            if (eventType == XmlPullParser.START_TAG) {
                when {
                    // RSS 2.0 用 <item>，Atom（GitHub 的 commits.atom 等）用 <entry>
                    xmlPullParser.name.equals(RSS_ITEM, true) ||
                        xmlPullParser.name.equals(ATOM_ENTRY, true) ->
                        insideItem = true
                    xmlPullParser.name.equals(RSS_ITEM_TITLE, true) ->
                        if (insideItem) currentArticle.title = xmlPullParser.nextText().oneLine()
                    xmlPullParser.name.equals(RSS_ITEM_LINK, true) ->
                        if (insideItem) {
                            // Atom 的 <link> 是自闭合标签，正文地址在 href 属性里，取 rel="alternate" 那条
                            val href = xmlPullParser.getAttributeValue(null, ATTR_HREF)
                            if (href != null) {
                                val rel = xmlPullParser.getAttributeValue(null, ATTR_REL)
                                if (rel == null || rel == "alternate") {
                                    currentArticle.link = href.trim()
                                }
                            } else {
                                currentArticle.link = xmlPullParser.nextText().trim()
                            }
                        }
                    xmlPullParser.name.equals(RSS_ITEM_THUMBNAIL, true) ->
                        if (insideItem) currentArticle.image =
                            xmlPullParser.getAttributeValue(null, RSS_ITEM_URL)
                    xmlPullParser.name.equals(RSS_ITEM_ENCLOSURE, true) ->
                        if (insideItem) {
                            val type =
                                xmlPullParser.getAttributeValue(null, RSS_ITEM_TYPE)
                            if (type != null && type.contains("image/")) {
                                currentArticle.image =
                                    xmlPullParser.getAttributeValue(null, RSS_ITEM_URL)
                            }
                        }
                    xmlPullParser.name.equals(RSS_ITEM_DESCRIPTION, true) ->
                        if (insideItem) {
                            val description = xmlPullParser.nextText()
                            currentArticle.description = description.trim()
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(description)
                            }
                        }
                    xmlPullParser.name.equals(ATOM_SUMMARY, true) ->
                        if (insideItem) {
                            val summary = xmlPullParser.nextText()
                            currentArticle.description = summary.trim()
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(summary)
                            }
                        }
                    xmlPullParser.name.equals(RSS_ITEM_CONTENT, true) ->
                        if (insideItem) {
                            val content = xmlPullParser.nextText().trim()
                            currentArticle.content = content
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(content)
                            }
                        }
                    // Atom 的 <content type="html"> </content>
                    xmlPullParser.name.equals(ATOM_CONTENT, true) ->
                        if (insideItem) {
                            val content = xmlPullParser.nextText().trim()
                            currentArticle.content = content
                            if (currentArticle.image == null) {
                                currentArticle.image = getImageUrl(content)
                            }
                        }
                    // Atom 用 <updated>/<published>，是 ISO 8601，转成本地时间展示
                    xmlPullParser.name.equals(ATOM_UPDATED, true) ||
                        xmlPullParser.name.equals(ATOM_PUBLISHED, true) ->
                        if (insideItem) currentArticle.pubDate = atomDate(xmlPullParser.nextText())
                    xmlPullParser.name
                        .equals(RSS_ITEM_PUB_DATE, true) ->
                        if (insideItem) {
                            val nextTokenType = xmlPullParser.next()
                            if (nextTokenType == XmlPullParser.TEXT) {
                                currentArticle.pubDate = xmlPullParser.text.trim()
                            }
                            // Skip to be able to find date inside 'tag' tag
                            continue@loop
                        }
                    xmlPullParser.name.equals(RSS_ITEM_TIME, true) ->
                        if (insideItem) currentArticle.pubDate = xmlPullParser.nextText()
                }
            } else if (eventType == XmlPullParser.END_TAG
                && (xmlPullParser.name.equals(RSS_ITEM, true)
                        || xmlPullParser.name.equals(ATOM_ENTRY, true))
            ) {
                // The item is correctly parsed
                insideItem = false
                currentArticle.origin = sourceUrl
                currentArticle.sort = sortName
                articleList.add(currentArticle)
                currentArticle = RssArticle()
            }
            eventType = xmlPullParser.next()
        }
        articleList.firstOrNull()?.let {
            Debug.log(sourceUrl, "┌获取标题")
            Debug.log(sourceUrl, "└${it.title}")
            Debug.log(sourceUrl, "┌获取时间")
            Debug.log(sourceUrl, "└${it.pubDate}")
            Debug.log(sourceUrl, "┌获取描述")
            Debug.log(sourceUrl, "└${it.description}")
            Debug.log(sourceUrl, "┌获取图片url")
            Debug.log(sourceUrl, "└${it.image}")
            Debug.log(sourceUrl, "┌获取文章链接")
            Debug.log(sourceUrl, "└${it.link}")
        }
        return Pair(articleList, null)
    }

    /**
     * Finds the first img tag and get the src as featured image
     *
     * @param input The content in which to search for the tag
     * @return The url, if there is one
     */
    private fun getImageUrl(input: String): String? {

        var url: String? = null
        val patternImg = "(<img [^>]*>)".toPattern()
        val matcherImg = patternImg.matcher(input)
        if (matcherImg.find()) {
            val imgTag = matcherImg.group(1)
            val patternLink = "src\\s*=\\s*\"([^\"]+)\"".toPattern()
            val matcherLink = patternLink.matcher(imgTag!!)
            if (matcherLink.find()) {
                url = matcherLink.group(1)!!.trim()
            }
        }
        return url
    }

    /** 把带换行缩进的标题压成一行 */
    private fun String.oneLine(): String = this.trim().replace(WHITESPACE, " ")

    /**
     * Atom 的 <updated> 是 ISO 8601（2026-11-08T09:52:35Z），转成本地 "yyyy-MM-dd HH:mm"；
     * 解析不了就原样返回，至少不会丢时间
     */
    private fun atomDate(raw: String): String {
        val text = raw.trim()
        return try {
            val slice = if (text.length >= 19) text.substring(0, 19) else text
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(slice) ?: return text
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            text
        }
    }

    private val WHITESPACE = "\\s+".toRegex()

    private const val RSS_ITEM = "item"
    private const val ATOM_ENTRY = "entry"
    private const val ATTR_HREF = "href"
    private const val ATTR_REL = "rel"
    private const val ATOM_SUMMARY = "summary"
    private const val ATOM_CONTENT = "content"
    private const val ATOM_UPDATED = "updated"
    private const val ATOM_PUBLISHED = "published"
    private const val RSS_ITEM_TITLE = "title"
    private const val RSS_ITEM_LINK = "link"
    private const val RSS_ITEM_CATEGORY = "category"
    private const val RSS_ITEM_THUMBNAIL = "media:thumbnail"
    private const val RSS_ITEM_ENCLOSURE = "enclosure"
    private const val RSS_ITEM_DESCRIPTION = "description"
    private const val RSS_ITEM_CONTENT = "content:encoded"
    private const val RSS_ITEM_PUB_DATE = "pubDate"
    private const val RSS_ITEM_TIME = "time"
    private const val RSS_ITEM_URL = "url"
    private const val RSS_ITEM_TYPE = "type"
}