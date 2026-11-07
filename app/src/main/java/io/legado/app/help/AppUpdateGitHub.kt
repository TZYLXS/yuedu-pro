package io.legado.app.help

import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readString
import kotlinx.coroutines.CoroutineScope

/**
 * 应用内更新：指向「阅读Pro」自己的仓库 TZYLXS/yuedu-pro 的 Release
 *
 * 与上游的区别：
 * - 上游读 gedoor/legado，并以 tag 字符串直接比较，这对 v1.0.24001 这种自定义 tag 不成立；
 *   这里改为从 tag 里取出版本号（versionCode）与当前 versionCode 比较。
 * - 上游按 legado_<channel>_*.apk 匹配，阅读Pro 的产物是 ...-armv7.apk / ...-arm64.apk，
 *   按设备支持的 ABI 选包。
 */
@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private const val REPO = "TZYLXS/yuedu-pro"
    private const val LAST_RELEASE_URL =
        "https://api.github.com/repos/$REPO/releases/latest"

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val body = okHttpClient.newCallStrResponse {
                url(LAST_RELEASE_URL)
                header("Accept", "application/vnd.github+json")
            }.body
            if (body.isNullOrBlank()) {
                throw NoStackTraceException("获取新版本出错")
            }
            val rootDoc = jsonPath.parse(body)
            val tagName = rootDoc.readString("$.tag_name")
                ?: throw NoStackTraceException("获取新版本出错")
            val releaseCode = versionCodeOf(tagName)
            if (releaseCode > AppConst.appInfo.versionCode) {
                val updateBody = rootDoc.readString("$.body").orEmpty()
                val names = rootDoc.read<List<String>>("\$.assets[*].name").orEmpty()
                val urls = rootDoc.read<List<String>>("\$.assets[*].browser_download_url").orEmpty()
                val index = pickAsset(names)
                if (index < 0 || index >= urls.size) {
                    throw NoStackTraceException("该版本没有可用的安装包")
                }
                return@async AppUpdate.UpdateInfo(
                    tagName, updateBody, urls[index], names[index]
                )
            } else {
                throw NoStackTraceException("已是最新版本")
            }
        }.timeout(15000)
    }

    /**
     * 从 tag 里取版本号，如 v1.0.24001 -> 24001
     */
    private fun versionCodeOf(tag: String): Long {
        val digits = Regex("(\\d+)$").find(tag)?.value ?: return 0L
        return digits.toLongOrNull() ?: 0L
    }

    /**
     * 按设备 ABI 选安装包：arm64 设备优先 arm64 包，其余优先 armv7 包，兜底取第一个 apk
     */
    private fun pickAsset(names: List<String>): Int {
        val abis = Build.SUPPORTED_ABIS.joinToString("|").lowercase()
        val want = when {
            abis.contains("arm64") -> "arm64"
            abis.contains("armeabi") || abis.contains("armv7") -> "armv7"
            else -> ""
        }
        if (want.isNotEmpty()) {
            names.forEachIndexed { index, name ->
                val lower = name.lowercase()
                if (lower.endsWith(".apk") && lower.contains(want)) return index
            }
        }
        return names.indexOfFirst { it.lowercase().endsWith(".apk") }
    }

}
