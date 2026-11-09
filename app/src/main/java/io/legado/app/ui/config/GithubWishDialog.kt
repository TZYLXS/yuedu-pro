package io.legado.app.ui.config

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogGithubWishBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.GithubWish
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

/**
 * 给 lean（coolsnowwolf/lede）许愿
 *
 * 两步：先登录 GitHub（token 只存本机），再把许愿内容一键做成 PR；
 * 不想走 fork 的可以改发 Issue。UI 全用 app 自带的控件，和「接入 FnReader 服务器」同一套样式。
 */
class GithubWishDialog : BaseDialogFragment(R.layout.dialog_github_wish) {

    private val binding by viewBinding(DialogGithubWishBinding::bind)

    /** 正在跑请求时禁用按钮，防连点 */
    private var running = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.tvCancel.onClick { dismiss() }
        binding.tvLogin.onClick { doLogin() }
        binding.tvLogout.onClick { doLogout() }
        binding.tvSubmit.onClick { doSubmit(asIssue = false) }
        binding.tvIssue.onClick { doSubmit(asIssue = true) }
        restoreSaved()
    }

    private fun ctx(): Context? = context

    private fun restoreSaved() {
        val c = ctx() ?: return
        val token = c.getPrefString(GithubWish.PREF_TOKEN, "") ?: ""
        val login = c.getPrefString(GithubWish.PREF_LOGIN, "") ?: ""
        binding.editToken.setText(token)
        if (login.isBlank()) {
            binding.tvAccount.text = getString(R.string.github_wish_not_login)
        } else {
            binding.tvAccount.text = getString(R.string.github_wish_logged_in, login)
            binding.tvResult.text = getString(R.string.github_wish_ready)
        }
    }

    private fun step(text: String) {
        if (!isAdded) return
        binding.tvResult.text = text
    }

    private fun doLogin() {
        if (running) return
        val token = binding.editToken.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            toastOnUi(R.string.github_wish_need_token)
            return
        }
        val c = ctx() ?: return
        running = true
        step(getString(R.string.github_wish_logging_in))
        lifecycleScope.launch {
            runCatching { GithubWish.login(token) }
                .onSuccess { account ->
                    c.putPrefString(GithubWish.PREF_TOKEN, token)
                    c.putPrefString(GithubWish.PREF_LOGIN, account.login)
                    step(
                        getString(
                            R.string.github_wish_login_ok,
                            account.login,
                            account.publicRepos,
                            if (account.forkReady) getString(R.string.github_wish_fork_yes)
                            else getString(R.string.github_wish_fork_no)
                        )
                    )
                    binding.tvAccount.text = getString(R.string.github_wish_logged_in, account.login)
                }
                .onFailure { e ->
                    c.putPrefString(GithubWish.PREF_TOKEN, "")
                    c.putPrefString(GithubWish.PREF_LOGIN, "")
                    binding.tvAccount.text = getString(R.string.github_wish_not_login)
                    step(getString(R.string.github_wish_login_fail, e.message ?: e.toString()))
                }
            running = false
        }
    }

    private fun doLogout() {
        val c = ctx() ?: return
        c.putPrefString(GithubWish.PREF_TOKEN, "")
        c.putPrefString(GithubWish.PREF_LOGIN, "")
        binding.editToken.setText("")
        binding.tvAccount.text = getString(R.string.github_wish_not_login)
        binding.tvResult.text = ""
        toastOnUi(R.string.github_wish_logged_out)
    }

    private fun doSubmit(asIssue: Boolean) {
        if (running) return
        val c = ctx() ?: return
        val token = c.getPrefString(GithubWish.PREF_TOKEN, "") ?: ""
        val login = c.getPrefString(GithubWish.PREF_LOGIN, "") ?: ""
        if (token.isBlank() || login.isBlank()) {
            toastOnUi(R.string.github_wish_need_login)
            return
        }
        val wish = binding.editWish.text?.toString()?.trim().orEmpty()
        if (wish.isBlank()) {
            toastOnUi(R.string.github_wish_need_wish)
            return
        }
        running = true
        step(
            if (asIssue) getString(R.string.github_wish_start_issue)
            else getString(R.string.github_wish_start_pr)
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (asIssue) {
                        GithubWish.submitIssue(token, wish) { text ->
                            stepOnUi(text)
                        }
                    } else {
                        GithubWish.submitPr(token, login, wish) { text ->
                            stepOnUi(text)
                        }
                    }
                }
            }
            result.onSuccess { url ->
                val text = getString(R.string.github_wish_done, url)
                step(text)
                linkResult(text)
            }.onFailure { e ->
                val text = getString(R.string.github_wish_fail, e.message ?: e.toString())
                step(text)
                linkResult(text)
            }
            running = false
        }
    }

    /** 结果文案里带地址就让它可点（成功后是 PR/Issue 地址，失败时可能是手动 fork 的地址） */
    private fun linkResult(text: String) {
        val url = URL_REGEX.find(text)?.value ?: return
        if (!isAdded) return
        binding.tvResult.setOnClickListener { context?.openUrl(url) }
        binding.tvResult.isClickable = true
    }

    /** 进度回调来自 IO 线程，切回主线程再碰控件 */
    private fun stepOnUi(text: String) {
        view?.post { step(text) }
    }

    private companion object {
        val URL_REGEX = Regex("https://[A-Za-z0-9._/:@\\-]+")
    }
}
