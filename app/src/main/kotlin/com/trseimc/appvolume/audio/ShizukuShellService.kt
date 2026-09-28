package com.trseimc.appvolume.audio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.trseimc.appvolume.IShizukuShell

/**
 * 由 Shizuku 拉起的独立进程服务，进程的 UID 就是 shell（2000）或 root（0）—— 取决于
 * 用户的 Shizuku 是 ADB 模式还是 Root 模式。
 *
 * 放在这里而不是主进程，是因为「以谁的身份发起 binder 调用」取决于调用进程的 UID：
 * 主进程永远是普通 app UID，而这里的进程才是 shell/root。
 */
class ShizukuShellService : Service() {

    private val binder = object : IShizukuShell.Stub() {

        override fun exec(command: String): String = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            runCatching { process.waitFor() }
            if (stdout.isBlank() && stderr.isNotBlank()) stderr else stdout
        }.getOrElse { "ERR: ${it.message}" }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
