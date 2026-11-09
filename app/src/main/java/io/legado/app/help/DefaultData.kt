package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File

object DefaultData {

    /** lean（coolsnowwolf）LEDE 的 master 提交 Atom 源：这就是它唯一「更新日志」 */
    const val LEDE_RSS_URL = "https://github.com/coolsnowwolf/lede/commits/master.atom"

    /** 同一个提交历史走 GitHub REST API（JSON），github.com 的 443 被重置时用这条 */
    const val LEDE_API_URL = "https://api.github.com/repos/coolsnowwolf/lede/commits?per_page=30"

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    importDefaultTocRules()
                }
                if (LocalConfig.needUpRssSources) {
                    importDefaultRssSources()
                }
                if (LocalConfig.needUpDictRule) {
                    importDefaultDictRules()
                }
                ensureBuiltinRssSources()
            }.onError {
                it.printOnDebug()
            }
        }
    }

    /**
     * 内置订阅补齐：只补缺失的，不动用户自己加的 / 用户删掉的
     * （和 importDefaultRssSources 的「先删默认再插入」区分开，避免每次升级把用户删的默认源又塞回来）
     */
    fun ensureBuiltinRssSources() {
        builtinRssSources().forEach { source ->
            if (appDb.rssSourceDao.getByKey(source.sourceUrl) == null) {
                appDb.rssSourceDao.insert(source)
            }
        }
    }

    /** 「我的」页那条入口直接打开的第一条（Atom 原文流） */
    fun builtinRssSources(): List<RssSource> = listOf(
        RssSource(
            sourceUrl = LEDE_RSS_URL,
            sourceName = "lean LEDE 更新日志",
            sourceGroup = "固件",
            enabled = true,
            singleUrl = false,
            enableJs = false,
            customOrder = 6,
            sourceComment = "coolsnowwolf/lede 的 master 提交历史（GitHub Atom），每条就是一次改动"
        ),
        // github.com 的 443 在某些网络里被 TLS 重置（Atom 那条打不开），这条走 api.github.com，同一份提交历史
        RssSource(
            sourceUrl = LEDE_API_URL,
            sourceName = "lean LEDE 更新日志（API 通道）",
            sourceGroup = "固件",
            enabled = true,
            singleUrl = false,
            enableJs = false,
            customOrder = 7,
            header = """{"User-Agent":"legado-wish","Accept":"application/vnd.github+json"}""",
            ruleArticles = "$[*]",
            ruleTitle = """$.commit.message##(?s)\n.*##""",
            rulePubDate = "$.commit.committer.date",
            ruleDescription = "$.commit.message",
            ruleLink = "$.html_url",
            ruleImage = "$.author.avatar_url",
            sourceComment = "同一个提交历史走 GitHub REST API：github.com 打不开（连接被重置）时用这条"
        )
    )

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val themeConfigs: List<ThemeConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ThemeConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ThemeConfig.Config>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val dictRules: List<DictRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}dictRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    fun importDefaultHttpTTS() {
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    fun importDefaultTocRules() {
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    fun importDefaultRssSources() {
        appDb.rssSourceDao.deleteDefault()
        appDb.rssSourceDao.insert(*rssSources.toTypedArray())
    }

    fun importDefaultDictRules() {
        appDb.dictRuleDao.insert(*dictRules.toTypedArray())
    }

}