package io.legado.app.ui.config

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogFnreaderConfigBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick
import java.net.HttpURLConnection
import java.net.URL

/**
 * 接入 FnReader（飞牛 fnOS）服务器
 *
 * 把全部接入方式列在一个入口里，由用户自己选：
 * 一键导入书源 / 复制 JSON 地址 / 复制 legado:// 链接 / WebDAV / HTTP API / 网页书架 / 接入文档。
 * 服务器地址与访问令牌存默认 SharedPreferences。
 */
class FnReaderConfigDialog : BaseDialogFragment(R.layout.dialog_fnreader_config) {

    companion object {
        const val KEY_PREF = "fnReaderConfig"
        const val KEY_SERVER = "fnReaderServer"
        const val KEY_TOKEN = "fnReaderToken"
        private const val DOC_URL = "https://github.com/TZYLXS/FnReader/blob/main/README.md"
    }

    private val binding by viewBinding(DialogFnreaderConfigBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.editServer.setText(getPrefString(KEY_SERVER, ""))
        binding.editToken.setText(getPrefString(KEY_TOKEN, ""))
        binding.tvSave.onClick {
            savePrefs()
            toastOnUi("已保存服务器地址与令牌")
        }
        binding.tvTest.onClick { testConnection() }
        binding.tvCancel.onClick { dismiss() }
        buildMethods()
    }

    private fun savePrefs() {
        putPrefString(KEY_SERVER, binding.editServer.text?.toString()?.trim().orEmpty())
        putPrefString(KEY_TOKEN, binding.editToken.text?.toString()?.trim().orEmpty())
    }

    private fun buildMethods() {
        binding.listMethods.removeAllViews()
        addMethod(
            "① 一键导入书源（推荐）",
            "自动生成「飞牛书库」书源并弹出导入确认；之后搜书、目录、正文、封面都走飞牛"
        ) { importBookSource() }
        addMethod(
            "② 复制书源 JSON 地址",
            "手动粘贴用：我的 → 书源管理 → 右上角菜单 → 网络导入"
        ) { copyText(jsonUrl(serverBase() ?: return@addMethod), "已复制书源 JSON 地址") }
        addMethod(
            "③ 复制 legado:// 导入链接",
            "发到手机或电脑上点一下即可唤起本 App 导入；注意这个链接不能粘进「网络导入」输入框"
        ) { copyText(importUrl(serverBase() ?: return@addMethod), "已复制导入链接") }
        addMethod(
            "④ WebDAV（备份与恢复）",
            "把书架备份挂到飞牛 /dav/backup/，/dav/books/ 可在文件管理器里浏览下载的 txt"
        ) { showWebDav() }
        addMethod(
            "⑤ HTTP API（自建客户端）",
            "查看 /api/m/* 接口地址与令牌，供脚本或第三方客户端读取书架、目录、正文"
        ) { showHttpApi() }
        addMethod(
            "⑥ 打开飞牛阅读网页",
            "用 App 内置浏览器直接打开 FnReader 网页书架"
        ) { openWebShelf() }
        addMethod(
            "⑦ 接入文档",
            "GitHub 上的接入说明、接口表与常见问题"
        ) { openDoc() }
    }

    private fun addMethod(title: String, desc: String, action: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ThemeStore.textColorPrimary(ctx))
        })
        row.addView(TextView(ctx).apply {
            text = desc
            textSize = 12f
            setTextColor(ThemeStore.textColorSecondary(ctx))
            setPadding(0, dp(2), 0, 0)
        })
        row.setOnClickListener { action() }
        binding.listMethods.addView(row)
    }

    private fun dp(value: Int): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()

    // ---------------- 地址与令牌 ----------------

    private fun rawServer(): String = binding.editServer.text?.toString()?.trim().orEmpty()

    private fun token(): String = binding.editToken.text?.toString()?.trim().orEmpty()

    /**
     * 规范化服务器地址：缺协议补 http://，只填了主机时补上飞牛的网关路径 /app/legado-web
     * 例：192.168.5.110:5666 → http://192.168.5.110:5666/app/legado-web
     */
    private fun serverBase(): String? {
        var s = rawServer()
        if (s.isEmpty()) {
            toastOnUi("先填服务器地址，例如 http://192.168.5.110:5666/app/legado-web")
            return null
        }
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        val path = Uri.parse(s).path.orEmpty().trim('/')
        if (path.isEmpty()) {
            s = "$s/app/legado-web"
        }
        savePrefs()
        return s
    }

    private fun requireServerAndToken(needToken: Boolean = true): String? {
        val base = serverBase() ?: return null
        if (needToken && token().isEmpty()) {
            toastOnUi("先填访问令牌（FnReader 设置 → 手机接入 里那个）")
            return null
        }
        return base
    }

    private fun jsonUrl(base: String): String =
        "$base/api/m/booksource?token=${Uri.encode(token())}"

    private fun importUrl(base: String): String =
        "legado://import/bookSource?src=${Uri.encode(jsonUrl(base))}"

    private fun davUrl(base: String): String {
        val uri = Uri.parse(base)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: base
        return "$scheme://$host:1122/dav/"
    }

    private fun copyText(text: String, tip: String) {
        requireContext().sendToClip(text)
        toastOnUi(tip)
    }

    // ---------------- 各接入方式 ----------------

    private fun importBookSource() {
        val base = requireServerAndToken() ?: return
        val uri = Uri.parse(importUrl(base))
        startActivity<OnLineImportActivity> { data = uri }
    }

    private fun showWebDav() {
        val base = requireServerAndToken() ?: return
        val dav = davUrl(base)
        val tk = token()
        alert {
            setTitle("WebDAV 接入")
            setMessage(
                "地址：$dav\n用户名：任意\n密码：$tk\n\n" +
                    "· /dav/books/  书架里已下载的书籍（只读，.txt）\n" +
                    "· /dav/backup/ 可读写，可接「我的 → 备份与恢复 → WebDAV」"
            )
            positiveButton("复制地址") { requireContext().sendToClip(dav) }
            neutralButton("打开备份设置") {
                startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.BACKUP_CONFIG) }
            }
            negativeButton(android.R.string.cancel) {}
        }
    }

    private fun showHttpApi() {
        val base = requireServerAndToken() ?: return
        val apiBase = "$base/api/m/"
        val tk = token()
        alert {
            setTitle("HTTP API")
            setMessage(
                "接口基址：$apiBase\n请求头：X-Api-Token: $tk\n\n" +
                    "GET /shelf 书架\n" +
                    "GET /search?key= 搜索\n" +
                    "GET /toc?url= 目录\n" +
                    "GET /content?url=&index= 正文\n" +
                    "GET /cover?url= 封面\n" +
                    "POST /progress 进度"
            )
            positiveButton("复制接口地址") { requireContext().sendToClip(apiBase) }
            okButton()
        }
    }

    private fun openWebShelf() {
        val base = requireServerAndToken(needToken = false) ?: return
        startActivity<WebViewActivity> {
            putExtra("url", base)
            putExtra("title", "FnReader")
        }
    }

    private fun openDoc() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOC_URL)))
        } catch (e: Exception) {
            toastOnUi("没有可用的浏览器：${e.localizedMessage}")
        }
    }

    // ---------------- 连通性测试 ----------------

    private fun testConnection() {
        val base = requireServerAndToken(needToken = false) ?: return
        val tk = token()
        execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("$base/api/m/info").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.setRequestProperty("X-Api-Token", tk)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                code to body.take(160)
            } finally {
                conn?.disconnect()
            }
        }.onSuccess { result ->
            val code = result.first
            if (code in 200..299) {
                toastOnUi("连接成功（HTTP $code）")
            } else {
                toastOnUi("服务器返回 HTTP $code：${result.second}")
            }
        }.onError {
            toastOnUi("连接失败：${it.localizedMessage}")
        }
    }

}
