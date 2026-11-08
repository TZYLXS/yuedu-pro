package io.legado.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.ui.config.FnReaderConfigDialog
import io.legado.app.utils.getPrefString

/**
 * 开机保活：开关开着（KEY_BRIDGE = 1）就在开机后把手机解析桥拉起来，
 * 不用等用户再进一次设置页，飞到半路的任务不会因为重启手机就一直没人接。
 */
class FnReaderBridgeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (context.getPrefString(FnReaderConfigDialog.KEY_BRIDGE, "0") != "1") return
        runCatching { FnReaderBridgeService.start(context) }
    }
}
