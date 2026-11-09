package io.legado.app.model

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 给 lean（coolsnowwolf/lede）许愿
 *
 * 想往别人的仓库提 PR，GitHub 只认「从 fork 来的分支」，所以一键许愿实际是四步：
 * 1. `GET /user` 认人
 * 2. 没 fork 就先 `POST /repos/coolsnowwolf/lede/forks`，等它复制完
 * 3. 在 fork 里按 fork 的 master 拉一条 `wish-<时间>` 分支，写一个 `wishlist/<时间>-<login>.md`
 * 4. `POST /repos/coolsnowwolf/lede/pulls`，head = `<login>:<branch>`
 *
 * 任何一步失败都带 GitHub 原文，别自己编错误信息。
 */
object GithubWish {

    /** token 只存本机，不写日志、不进备份 */
    const val PREF_TOKEN = "githubWishToken"
    const val PREF_LOGIN = "githubWishLogin"

    const val UPSTREAM = "coolsnowwolf/lede"
    private const val UPSTREAM_REPO = "lede"
    private const val BASE = "master"
    private const val API = "https://api.github.com"
    private const val UA = "legado-wish"

    /** fork 一份 335 MB 的 lede 要一会儿，这里最多等 2 分钟 */
    private const val FORK_POLL_TIMES = 40
    private const val FORK_POLL_INTERVAL = 3000L

    class ApiException(message: String) : Exception(message)

    data class Account(val login: String, val publicRepos: Int, val forkReady: Boolean)

    suspend fun login(token: String): Account = withContext(Dispatchers.IO) {
        val (code, text) = request(token, "GET", "$API/user")
        if (code != 200) throw ApiException(explain(code, text))
        val json = JSONObject(text)
        val name = json.optString("login")
        val repos = json.optInt("public_repos")
        if (name.isBlank()) throw ApiException("GitHub 没返回账号名，token 可能不对")
        val forkReady = request(token, "GET", "$API/repos/$name/$UPSTREAM_REPO").first == 200
        Account(name, repos, forkReady)
    }

    /**
     * 许愿 → PR，返回 PR 的网页地址
     * [onStep] 每一步的进度文案，调用方负责切回主线程
     */
    suspend fun submitPr(
        token: String,
        login: String,
        wish: String,
        onStep: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (wish.isBlank()) throw ApiException("许愿内容不能为空")
        ensureFork(token, login, onStep)
        val repoApi = "$API/repos/$login/$UPSTREAM_REPO"
        val (baseCode, baseText) = request(token, "GET", "$repoApi/git/ref/heads/$BASE")
        if (baseCode != 200) throw ApiException("读不到 fork 的 $BASE 分支：${explain(baseCode, baseText)}")
        val baseSha = JSONObject(baseText).getJSONObject("object").getString("sha")

        val now = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val branch = "wish-$now"
        onStep("新建分支 $branch")
        val refBody = JSONObject()
            .put("ref", "refs/heads/$branch")
            .put("sha", baseSha)
        val (refCode, refText) = request(token, "POST", "$repoApi/git/refs", refBody.toString())
        if (refCode !in 200..299) throw ApiException("建分支失败：${explain(refCode, refText)}")

        val path = "wishlist/$now-$login.md"
        val fileBody = JSONObject()
            .put("message", "许愿：${title(wish)}")
            .put("content", Base64.encodeToString(render(login, wish).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("branch", branch)
        onStep("往 fork 写 $path")
        val (putCode, putText) = request(token, "PUT", "$repoApi/contents/$path", fileBody.toString())
        if (putCode !in 200..299) throw ApiException("写文件失败：${explain(putCode, putText)}")

        onStep("向 $UPSTREAM 发 PR")
        val prBody = JSONObject()
            .put("title", "许愿：${title(wish)}")
            .put("head", "$login:$branch")
            .put("base", BASE)
            .put("body", prBody(login, wish))
        val (prCode, prText) = request(token, "POST", "$API/repos/$UPSTREAM/pulls", prBody.toString())
        if (prCode !in 200..299) throw ApiException("发 PR 失败：${explain(prCode, prText)}")
        JSONObject(prText).optString("html_url").ifBlank {
            "https://github.com/$UPSTREAM/pulls"
        }
    }

    /** 不想走 fork 就走 Issue，一步到位，返回 Issue 地址 */
    suspend fun submitIssue(
        token: String,
        wish: String,
        onStep: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (wish.isBlank()) throw ApiException("许愿内容不能为空")
        onStep("向 $UPSTREAM 提 Issue")
        val body = JSONObject()
            .put("title", "许愿：${title(wish)}")
            .put("body", prBody("issue", wish))
        val (code, text) = request(token, "POST", "$API/repos/$UPSTREAM/issues", body.toString())
        if (code !in 200..299) throw ApiException("提 Issue 失败：${explain(code, text)}")
        JSONObject(text).optString("html_url").ifBlank {
            "https://github.com/$UPSTREAM/issues"
        }
    }

    private suspend fun ensureFork(token: String, login: String, onStep: (String) -> Unit) {
        val (code, text) = request(token, "GET", "$API/repos/$login/$UPSTREAM_REPO")
        if (code == 200) {
            onStep("fork 已就绪：$login/$UPSTREAM_REPO")
            return
        }
        if (code != 404) throw ApiException("查 fork 失败：${explain(code, text)}")
        onStep("正在把 $UPSTREAM fork 到你的账号（首次要等一会儿）")
        val forkBody = JSONObject().put("name", UPSTREAM_REPO)
        val (forkCode, forkText) = request(token, "POST", "$API/repos/$UPSTREAM/forks", forkBody.toString())
        if (forkCode !in 200..299) {
            if (forkCode == 403) {
                // 细粒度 token 常见：缺 repository_creation 权限。给出可点的网页 fork 兜底
                throw ApiException(
                    "当前 token 没有创建 fork 的权限。两种办法：① 点这行打开 https://github.com/coolsnowwolf/lede/fork " +
                        "手动点一次 Fork（只需一次），回来再点「提交 PR」就能继续；" +
                        "② 换成 classic token（勾 public_repo）https://github.com/settings/tokens/new?scopes=public_repo 。" +
                        "原始返回：${explain(forkCode, forkText)}"
                )
            }
            throw ApiException("fork 失败：${explain(forkCode, forkText)}")
        }
        for (i in 1..FORK_POLL_TIMES) {
            Thread.sleep(FORK_POLL_INTERVAL)
            if (request(token, "GET", "$API/repos/$login/$UPSTREAM_REPO").first == 200) {
                onStep("fork 已完成：$login/$UPSTREAM_REPO")
                return
            }
        }
        throw ApiException(
            "$UPSTREAM 有 335 MB，GitHub 复制 fork 还没好，过一两分钟再点一次「提交 PR」即可（fork 已经在生成）"
        )
    }

    private fun request(
        token: String,
        method: String,
        url: String,
        body: String? = null
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 45000
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", UA)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String {
        val raw = try {
            JSONObject(body).optString("message")
        } catch (e: Exception) {
            body
        }.take(200)
        val hint = when (code) {
            401 -> "token 无效或已过期"
            403 -> "权限不够：许愿 PR 需要 classic token 勾 public_repo（新建 https://github.com/settings/tokens/new?scopes=public_repo ），" +
                "细粒度 token 要给 Contents + Pull requests 读写、Issues 读写，自动 fork 还要 Repository creation 写"
            404 -> "仓库/分支不存在，或 token 看不到"
            422 -> "GitHub 拒绝了请求（分支或文件重名、PR 已存在都算）"
            429 -> "请求太频繁，等一会儿再试"
            else -> ""
        }
        return if (hint.isEmpty()) "HTTP $code ${raw}" else "HTTP $code $hint（$raw）"
    }

    private fun title(wish: String): String {
        val first = wish.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val cut = if (first.length > 60) first.substring(0, 60) + "…" else first
        return cut.ifBlank { "未命名许愿" }
    }

    private fun prBody(login: String, wish: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """
            |许愿原文：
            |
            |${wish.trim()}
            |
            |---
            |由 GitHub 账号 `$login` 从「阅读Pro」的「给 lean 许愿」提交于 $time。
            |这条 PR 只是在 `wishlist/` 下加了一个愿望清单文件，不含代码改动。
        """.trimMargin()
    }

    private fun render(login: String, wish: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """
            |# 许愿：${title(wish)}
            |
            |${wish.trim()}
            |
            |---
            |- 提交人：@$login
            |- 提交时间：$time
            |- 来源：阅读Pro「给 lean 许愿」（手机端一键提交）
        """.trimMargin()
    }
}
