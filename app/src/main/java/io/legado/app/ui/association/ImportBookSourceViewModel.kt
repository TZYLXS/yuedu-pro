package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.unCompress
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSource?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()

    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    val isSelectAllNew: Boolean
        get() {
            newSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val isSelectAllUpdate: Boolean
        get() {
            updateSourceStatus.forEachIndexed { index, b ->
                if (b && !selectStatus[index]) {
                    return false
                }
            }
            return true
        }

    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) {
                            source.bookSourceName = it.bookSourceName
                        }
                        if (keepGroup) {
                            source.bookSourceGroup = it.bookSourceGroup
                        }
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            source.bookSourceGroup = groups.joinToString(",")
                        } else {
                            source.bookSourceGroup = group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        execute {
            val mText = text.trim()
            when {
                mText.isJsonObject() -> {
                    kotlin.runCatching {
                        val json = JsonPath.parse(mText)
                        json.read<List<String>>("$.sourceUrls")
                    }.onSuccess { listUrl ->
                        listUrl.forEach {
                            importSourceUrl(it)
                        }
                    }.onFailure {
                        GSON.fromJsonObject<BookSource>(mText).getOrThrow().let {
                            if (it.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.add(it)
                        }
                    }
                }

                mText.isJsonArray() -> GSON.fromJsonArray<BookSource>(mText).getOrThrow()
                    .let { items ->
                        val source = items.firstOrNull() ?: return@let
                        if (source.bookSourceUrl.isEmpty()) {
                            throw NoStackTraceException("不是书源")
                        }
                        allSources.addAll(items)
                    }

                mText.isAbsUrl() -> {
                    importSourceUrl(mText)
                }

                mText.startsWith("legado://") || mText.startsWith("legado:", true) -> {
                    importLegadoLink(mText)
                }

                // 只填了「主机:端口/路径」的情况，补上协议再走网络导入
                IP_HOST_REGEX.matches(mText) -> {
                    importSourceUrl("http://$mText")
                }

                mText.isUri() -> {
                    val uri = Uri.parse(mText)
                    uri.inputStream(context).getOrThrow().use { inputS ->
                        GSON.fromJsonArray<BookSource>(inputS).getOrThrow().let {
                            val source = it.firstOrNull() ?: return@let
                            if (source.bookSourceUrl.isEmpty()) {
                                throw NoStackTraceException("不是书源")
                            }
                            allSources.addAll(it)
                        }
                    }
                }

                else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importLegadoLink(link: String) {
        val uri = Uri.parse(link)
        val path = uri.path.orEmpty()
        val src = uri.getQueryParameter("src")
        when {
            !path.contains("bookSource", true) ->
                throw NoStackTraceException("这条链接不是书源导入链接（$path），请用书源类链接")
            src.isNullOrBlank() ->
                throw NoStackTraceException("链接里没有 src 参数")
            src.startsWith("legado:", true) ->
                throw NoStackTraceException("链接里的 src 还是导入链接，无法连续解析")
            src.isAbsUrl() ->
                importSourceUrl(src)
            else ->
                throw NoStackTraceException("src 不是可下载地址：$src")
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.unCompress { inputStream ->
            // 官方书源包是数组；FnReader 这类自建服务也可能只给一个对象，两种都收
            val text = String(inputStream.readBytes(), Charsets.UTF_8).trim()
            val list = when {
                text.isJsonArray() -> GSON.fromJsonArray<BookSource>(text).getOrThrow()
                text.isJsonObject() -> listOf(GSON.fromJsonObject<BookSource>(text).getOrThrow())
                text.startsWith("<") || text.contains("<html", true) ->
                    throw NoStackTraceException("地址返回的是网页而不是书源 JSON，多为缺少令牌或未登录，请换带 token 的地址")
                text.isEmpty() ->
                    throw NoStackTraceException("地址返回了空内容：$url")
                else ->
                    throw NoStackTraceException("返回内容不是书源 JSON（前 60 字）：${text.take(60)}")
            }
            val source = list.firstOrNull() ?: throw NoStackTraceException("不是书源")
            if (source.bookSourceUrl.isEmpty()) {
                throw NoStackTraceException("不是书源")
            }
            allSources.addAll(list)
        }
    }

    private fun comparisonSource() {
        execute {
            allSources.forEach {
                val source = appDb.bookSourceDao.getBookSource(it.bookSourceUrl)
                checkSources.add(source)
                selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            successLiveData.postValue(allSources.size)
        }
    }

    companion object {
        // 形如 192.168.5.110:1122/api/m/booksource 这种缺协议的地址
        private val IP_HOST_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/\S*)?$""")
    }

}