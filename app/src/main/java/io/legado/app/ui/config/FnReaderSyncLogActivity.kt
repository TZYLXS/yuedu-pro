package io.legado.app.ui.config

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityFnreaderSyncLogBinding
import io.legado.app.model.FnReaderSyncLog
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

/**
 * T2 同步记录页：通知被下一轮覆盖后，用户仍能在 App 内回看最近 50 条。
 */
class FnReaderSyncLogActivity : BaseActivity<ActivityFnreaderSyncLogBinding>() {

    override val binding by viewBinding(ActivityFnreaderSyncLogBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.tvClear.onClick {
            FnReaderSyncLog.clear()
            render()
        }
        render()
    }

    private fun render() {
        binding.llLogs.removeAllViews()
        val logs = FnReaderSyncLog.all()
        if (logs.isEmpty()) {
            binding.llLogs.addView(TextView(this).apply {
                text = "还没有同步记录"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        logs.forEachIndexed { index, entry ->
            binding.llLogs.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }.apply {
                addView(TextView(context).apply {
                    text = "${FnReaderSyncLog.formatTime(entry.at)}　${entry.direction}"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                })
                addView(TextView(context).apply {
                    text = entry.countText()
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.primaryText))
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "耗时 ${entry.durationMs} ms" +
                            if (entry.note.isNotEmpty()) "　${entry.note}" else ""
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                    setPadding(0, dp(2), 0, 0)
                })
                if (entry.error.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = "失败：${entry.error}"
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            })
            if (index != logs.lastIndex) {
                binding.llLogs.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                })
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
