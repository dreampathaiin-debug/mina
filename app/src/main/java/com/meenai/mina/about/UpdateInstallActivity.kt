package com.meenai.mina.about

import android.app.Activity
import android.os.Bundle
import com.meenai.mina.MinaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 更新通知点开时先落到这里:不显示任何界面,把下好的 APK 校验后直接交给系统安装器,然后立即退出。
 * 校验不通过(文件坏了、不是更高版本、签名不对)就什么都不打开。
 */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MinaApp
        val updater = ApkUpdater(this)
        Thread {
            val intent = runCatching {
                runBlocking {
                    app.updates.load()
                    withContext(Dispatchers.IO) {
                        val release = app.updates.state.value.update ?: app.updates.state.value.latest
                        if (release?.apkUrl != null && updater.state(release).phase == DownloadPhase.Ready) {
                            updater.installIntent(release)
                        } else null
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (intent != null) startActivity(intent)
                finish()
            }
        }.start()
    }
}
