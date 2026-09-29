package com.trseimc.appvolume.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志：设置里开关控制，写入 app 私有目录；未捕获异常（闪退）无条件写 crash 文件。
 * 闪退后重新打开 app，设置里「复制日志」可把内容拷到剪贴板。
 */
object FileLog {

    private const val PREFS = "file_log_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val LOG_FILE = "app_log.txt"
    private const val CRASH_FILE = "app_crash.txt"
    private const val MAX_BYTES = 512L * 1024

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var enabled: Boolean = false
        private set

    private var appContext: Context? = null
    private val lock = Any()

    /** 进程内各入口都调它，幂等：只初始化上下文并读回开关状态。 */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        installCrashHandler()
    }

    fun setEnabled(on: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        enabled = on
        if (on) log("lifecycle", "日志记录已开启")
    }

    fun log(tag: String, msg: String) {
        if (!enabled) return
        Log.i("AppVolume.$tag", msg)
        append(logFile(), "${fmt.format(Date())} [$tag] $msg\n")
    }

    fun log(tag: String, msg: String, t: Throwable) {
        if (!enabled) return
        Log.w("AppVolume.$tag", msg, t)
        append(logFile(), "${fmt.format(Date())} [$tag] $msg :: ${Log.getStackTraceString(t)}\n")
    }

    /** 闪退落盘：不管开关是否打开都写（否则忘了开日志就永远抓不到崩溃）。 */
    fun crash(t: Throwable) {
        val head = "${fmt.format(Date())} [CRASH] 线程 ${Thread.currentThread().name}\n"
        append(crashFile(), head + Log.getStackTraceString(t) + "\n")
    }

    /** 给设置页「复制日志」用：crash 在前（最近一次闪退），运行日志在后。 */
    fun dump(): String {
        val c = runCatching { crashFile().readText() }.getOrDefault("（无闪退记录）")
        val l = runCatching {
            logFile().readText().ifBlank { "（运行日志为空，可能没开日志记录）" }
        }.getOrDefault("（运行日志读取失败）")
        return "===== 闪退记录 =====\n$c\n===== 运行日志 =====\n$l"
    }

    fun clear() {
        synchronized(lock) {
            logFile().delete()
            crashFile().delete()
        }
    }

    fun copyToClipboard(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AppVolume log", dump()))
        true
    }.getOrDefault(false)

    private fun logFile(): File {
        val ctx = appContext ?: throw IllegalStateException("FileLog not initialized")
        return File(ctx.filesDir, LOG_FILE)
    }

    private fun crashFile(): File {
        val ctx = appContext ?: throw IllegalStateException("FileLog not initialized")
        return File(ctx.filesDir, CRASH_FILE)
    }

    /** 追加写，超过上限就把旧内容截半，避免无限膨胀。 */
    private fun append(file: File, text: String) {
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange(bytes.size / 2, bytes.size))
                }
                file.appendText(text)
            }
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashForwarder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashForwarder(prev))
    }

    /** 写完 crash 文件后转发给原 handler（系统弹窗、进程退出流程照常走）。 */
    private class CrashForwarder(private val prev: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            crash(e)
            prev?.uncaughtException(t, e)
        }
    }
}
