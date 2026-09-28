package com.trseimc.appvolume.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.trseimc.appvolume.IShizukuShell
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道。
 *
 * 在这里 Shizuku 只干一件事：以 shell 身份执行 `pm grant <本应用> android.permission.DUMP`。
 * 拿到 DUMP 权限之后，本进程就能直接跑 `dumpsys media.audio_flinger` 拿到 AudioFlinger 的
 * 会话表 —— 那是给别人的播放链路挂音效的钥匙。
 *
 * 关键坑（踩过）：`Shizuku.bindUserService()` 是**异步**的。Shizuku 服务端要先 fork 一个
 * `app_process` 把服务进程拉起来，再 binder 回调 `onServiceConnected`，首次冷启动要几秒。
 * 早先的版本 bind 完立刻就去执行命令，必然拿到「服务没连上」。现在统一走
 * [ensureShell]，它会发起绑定并轮询等到真正连上（或超时把原因报出来）。
 *
 * 另外所有 binder/绑定异常都不再静默吞掉 —— 绑不上必须让用户看到为什么。
 */
class ShizukuBridge(private val context: Context) {

    enum class State { NOT_INSTALLED, NOT_GRANTED, READY }

    @Volatile
    private var shell: IShizukuShell? = null

    @Volatile
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    /** 已经发起绑定但回调还没到，避免重复拉起进程。 */
    @Volatile
    private var bindPending = false

    @Volatile
    private var bindStartedAt = 0L

    @Volatile
    private var bindError: String? = null

    /** 同步命令通道（newProcess）的失败原因，可能是 null 但通道仍不可用。 */
    @Volatile
    private var remoteError: String? = null

    /** 授权结果回调：用户点完授权弹窗后由 Shizuku 回调，上层可以据此立刻重跑流程。 */
    var onPermissionResult: ((Boolean) -> Unit)? = null

    // 单例的生命周期跟进程一样长，不需要取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _diag = MutableStateFlow("尚未尝试连接")
    val diag: StateFlow<String> = _diag

    val isBound: Boolean get() = shell != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = runCatching { IShizukuShell.Stub.asInterface(service) }.getOrNull()
            if (binder == null) {
                bindPending = false
                bindError = "服务已连上，但 AIDL 转换失败"
                _diag.value = bindError!!
                return
            }
            shell = binder
            bindPending = false
            bindError = null
            _diag.value = "shell 服务已连上（${name?.className.orEmpty()}）"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            bindPending = false
            _diag.value = "shell 服务连接断开"
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _diag.value = "Shizuku binder 已到达"
        scope.launch { warmUp() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shell = null
        bindPending = false
        _diag.value = "Shizuku 已停止运行"
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            _diag.value = "授权通过"
            scope.launch { warmUp() }
        } else {
            _diag.value = "用户在 Shizuku 里拒绝了授权"
        }
        onPermissionResult?.invoke(granted)
    }

    init {
        // sticky 版会在 binder 已存在时立刻回调一次，进程一启动就能把通道备好
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure {
            _diag.value = "注册 Shizuku 监听失败：${it.message}"
        }
        // Shizuku 没运行（监听不会回调）时也要有个说得通的初始状态
        if (hasDumpPermission()) {
            _diag.value = "DUMP 权限已持有：会话表本应用自己就能读，Shizuku 只在读系统进程表时还用得上"
        }
    }

    private val isBinderAlive: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isGranted: Boolean
        get() = runCatching {
            if (!isBinderAlive) false else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun state(): State = when {
        !isBinderAlive -> State.NOT_INSTALLED
        !isGranted -> State.NOT_GRANTED
        else -> State.READY
    }

    fun requestPermission() {
        _diag.value = "已向 Shizuku 发起授权请求"
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { _diag.value = "发起授权失败：${it.message}" }
    }

    /** 本应用是否已经能自己跑 dumpsys —— 是的话 Shizuku 就彻底不用干活了。 */
    private fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(SessionScanner.DUMP_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 预热：只在确实需要 shell 通道时才去拉 UserService。
     *
     * 之前这里无条件 bindInternal()，于是拿到 DUMP 之后照样硬拉一个独立进程 ——
     * 而在拉不起 UserService 的机器上，界面就永远卡在「正在拉起 shell 服务…」，
     * 旁边却又显示「已就绪」，看着像坏了。
     *
     * 现在的做法：先探测同步的 newProcess 通道（一次 binder 调用，很便宜），通了就用它；
     * 只有同步通道也不通、而且本进程还没有 DUMP 时，才去 fork 那个独立进程。
     * 注意「有 DUMP」不再等于「不用 Shizuku 了」—— 读系统进程表时 shell 身份仍然有用。
     */
    private suspend fun warmUp() {
        if (!isGranted) return
        if (isBound || bindPending) return

        val probe = withContext(Dispatchers.IO) { execRemote("echo ok") }
        if (probe?.trim() == "ok") {
            _diag.value = "同步命令通道可用（uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}）" +
                "，无需拉起 shell 服务"
            return
        }
        if (hasDumpPermission()) {
            _diag.value = "DUMP 权限已持有、同步命令通道不可用，不拉起 shell 服务"
            return
        }
        withContext(Dispatchers.Main) { bindInternal() }
    }

    private fun bindInternal() {
        if (!isGranted) return
        if (shell != null) return
        // 上一次绑定已经超时（服务没起来），别再死等，允许重新拉一次
        if (bindPending && System.currentTimeMillis() - bindStartedAt < BIND_TIMEOUT_MS) return
        bindPending = true
        bindStartedAt = System.currentTimeMillis()
        bindError = null
        _diag.value = "正在拉起 shell 服务…（首次需要几秒）"
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, ShizukuShellService::class.java)
            )
                .daemon(false)
                .version(1)
                .processNameSuffix("shell")
                .debuggable(false)
            serviceArgs = args
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            bindPending = false
            bindError = "bindUserService 抛出 ${t.javaClass.simpleName}：${t.message}"
            _diag.value = bindError!!
        }
    }

    /**
     * 确保 shell 服务可用。返回 null 表示已连上，否则返回失败原因。
     *
     * 必须在主线程发起绑定（ServiceConnection 回调走主线程 Looper），然后轮询等待。
     */
    suspend fun ensureShell(timeoutMs: Long = BIND_TIMEOUT_MS): String? {
        if (!isBinderAlive) return "Shizuku 的 binder 不通（Shizuku 没运行，或没在设置里启动）"
        if (!isGranted) return "Shizuku 未把权限授予本应用"

        if (shell == null) {
            withContext(Dispatchers.Main) { bindInternal() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (shell == null && System.currentTimeMillis() < deadline) {
                bindError?.let { return it }
                delay(BIND_POLL_MS)
            }
        }
        return if (shell != null) {
            null
        } else {
            bindError ?: "等待 shell 服务超时（${timeoutMs / 1000}s）。" +
                "常见原因：Shizuku 版本过旧不支持 UserService，或它在后台被系统杀了。" +
                "打开 Shizuku 确认它显示「正在运行」，再回本页重试。"
        }
    }

    /** 服务退出时解绑，别把 shell 进程一直留着。 */
    fun unbind() {
        val args = serviceArgs ?: return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        shell = null
        bindPending = false
    }

    /** 强制重连：先解绑旧进程再重新拉起。 */
    suspend fun reconnect(timeoutMs: Long = BIND_TIMEOUT_MS): String? {
        val args = serviceArgs
        if (args != null) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        shell = null
        bindPending = false
        bindError = null
        return ensureShell(timeoutMs)
    }

    /**
     * 以 shell/root 身份执行命令。优先走 [execRemote]（同步、无需独立进程），
     * 它不可用时再退到 UserService 通道。绝不抛异常。
     */
    fun runShell(command: String): String? {
        execRemote(command)?.let { return it }
        val service = shell ?: return null
        return try {
            val out = service.exec(command)
            if (out.startsWith("ERR:")) {
                bindError = out
                null
            } else {
                out
            }
        } catch (t: Throwable) {
            // 进程被杀了：清掉句柄，下次调用会重新绑定
            shell = null
            bindPending = false
            bindError = "shell 调用失败：${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    /**
     * 通道一（首选）：反射调用 `Shizuku.newProcess()`。
     *
     * 它是 private 的，但那是我们自己 APK 里的类，反射不受 Android hidden API 限制。
     * 反汇编过字节码 —— 实现就是 `requireService().newProcess(cmd, env, dir)` 的直通封装，
     * 没有任何版本检查。**同步**返回，不需要 Shizuku 去 fork 一个独立进程再异步回调，
     * 所以不存在 UserService 那套「等几秒还不一定连得上」的问题。
     *
     * R8 会动私有方法，所以 `proguard-rules.pro` 里有对应的 keep 规则。
     */
    private fun execRemote(command: String): String? {
        val method = newProcessMethod ?: return null
        return runCatching {
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return null
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
            if (stdout.isBlank() && stderr.isNotBlank()) stderr else stdout
        }.onFailure {
            remoteError = "newProcess 失败：${it.javaClass.simpleName}: ${it.message}"
        }.getOrNull()
    }

    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
        }.onFailure {
            remoteError = "找不到 Shizuku.newProcess（很老的 Shizuku 版本？）：${it.message}"
        }.getOrNull()
    }

    /**
     * 借 shell 身份把权限授予自己。两条清单权限 + 一条 appop，缺一不可：
     *
     * - `DUMP`：读 AudioFlinger 会话表的钥匙；
     * - `PACKAGE_USAGE_STATS`：读系统进程表还需要它（AMS 的 dump 是 DUMP **且** USAGE_STATS）；
     * - `appops set ... GET_USAGE_STATS allow`：万一用户曾在设置里关掉「使用情况访问」，
     *   appop 会停在 MODE_IGNORED，这时光有清单权限也过不了 `checkUsageStatsPermission`。
     *
     * 这一步只需要成功一次，之后系统会把授权记下来，除非卸载重装。
     */
    suspend fun grantDumpPermission(): String? {
        ensureExecutable()?.let { return it }
        return grantRequiredPermissions()
    }

    /** 逐条执行并回报结果，供界面原样显示。 */
    private suspend fun grantRequiredPermissions(): String = buildString {
        for (permission in GRANTED_PERMISSIONS) {
            val cmd = "pm grant ${context.packageName} $permission"
            append(cmd.substringAfter("pm grant ")).append(" → ")
                .append(runShell(cmd)?.trim()?.ifBlank { "成功（无输出）" } ?: "执行失败")
            append('\n')
        }
        val appOp = "appops set ${context.packageName} ${USAGE_STATS_APP_OP} allow"
        append("$USAGE_STATS_APP_OP appop → ")
            .append(runShell(appOp)?.trim()?.ifBlank { "成功（无输出）" } ?: "执行失败")
    }

    /**
     * 确保至少有一条能执行命令的通道。
     *
     * 先用一条 `echo` 探测同步通道：能通就直接完事，不必去等 UserService 那个
     * 动辄几秒还不一定起得来的独立进程。探测不通才退回 [ensureShell]。
     */
    suspend fun ensureExecutable(timeoutMs: Long = BIND_TIMEOUT_MS): String? {
        if (!isBinderAlive) return "Shizuku 的 binder 不通（Shizuku 没运行，或没在设置里启动）"
        if (!isGranted) return "Shizuku 未把权限授予本应用"

        val probe = withContext(Dispatchers.IO) { execRemote("echo ok") }
        if (probe?.trim() == "ok") {
            _diag.value = "已通过同步命令通道执行（Shizuku uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}）"
            return null
        }
        remoteError?.let { Log.w(TAG, "newProcess 不可用：$it") }
        return ensureShell(timeoutMs)
    }

    /**
     * 一键诊断：依次跑几条关键命令，把真实输出拼成一段文本。
     *
     * 光报「超时」没法定位，必须看到 `id` 到底是 shell 还是别的、`pm grant` 回了什么、
     * dumpsys 里到底有没有会话表 —— 这三行基本能判定整条链路的每一段。
     */
    fun probe(): String = buildString {
        val method = newProcessMethod
        appendLine("newProcess 通道：${if (method != null) "可用（同步）" else "不可用 → 只能走 UserService"}")
        appendLine("binder=${if (isBinderAlive) "通" else "断"} uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
        appendLine("授权=${if (isGranted) "已授权" else "未授权"}")

        val id = runShell("id")?.trim().takeUnless { it.isNullOrEmpty() } ?: "<执行失败>"
        appendLine("id → $id")

        for (permission in GRANTED_PERMISSIONS) {
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val result = runShell("pm grant ${context.packageName} $permission")
            appendLine(
                "$permission → 本进程${if (granted) "已持有" else "未持有"}" +
                    "，pm grant：${result?.trim()?.ifBlank { "成功（无输出）" } ?: "执行失败"}"
            )
        }

        appendLine(
            "$USAGE_STATS_APP_OP appop → " +
                (runShell("appops get ${context.packageName} $USAGE_STATS_APP_OP")
                    ?.trim()?.replace('\n', ' ')?.ifBlank { "无输出" } ?: "执行失败")
        )

        val dump = runShell(SessionScanner.DUMP_COMMAND)
        appendLine(
            if (dump == null) {
                "dumpsys media.audio_flinger → <执行失败>"
            } else {
                "dumpsys media.audio_flinger → ${dump.length} 字符，含会话表=${dump.contains("Global session refs")}"
            }
        )

        val lru = runShell(PROCESS_COMMAND)
        append(
            if (lru == null) {
                "$PROCESS_COMMAND → <执行失败>"
            } else {
                "$PROCESS_COMMAND → ${lru.length} 字符，" +
                    "进程条数=${PROCESS_ENTRY.findAll(lru).count()}"
            }
        )
    }

    /** 一行状态，直接显示给用户，用来判断到底卡在哪一步。 */
    fun diagnostics(): String = buildString {
        append("Shizuku v").append(runCatching { Shizuku.getVersion() }.getOrDefault(-1))
        append(" uid=").append(runCatching { Shizuku.getUid() }.getOrDefault(-1))
        append(" binder=").append(if (isBinderAlive) "通" else "断")
        append(" 授权=").append(if (isGranted) "是" else "否")
        append(" shell=").append(
            when {
                shell != null -> "已连上"
                hasDumpPermission() -> "未连上（有 DUMP，读进程表时才会用到）"
                else -> "未连上"
            }
        )
        append(" · ").append(_diag.value)
    }

    companion object {
        private const val TAG = "ShizukuBridge"
        private const val REQUEST_CODE = 1001
        private const val BIND_TIMEOUT_MS = 15_000L
        private const val BIND_POLL_MS = 120L

        // AMS 的 dump 需要 DUMP **和** PACKAGE_USAGE_STATS 两个权限，一次都给上
        private val GRANTED_PERMISSIONS = listOf(
            SessionScanner.DUMP_PERMISSION,
            SessionScanner.USAGE_STATS_PERMISSION
        )

        const val USAGE_STATS_APP_OP = "GET_USAGE_STATS"

        /** 给用户抄的 adb 命令，和设备上 Shizuku 执行的那三条完全一致。 */
        fun adbGrantCommands(packageName: String): List<String> = listOf(
            "adb shell pm grant $packageName ${SessionScanner.DUMP_PERMISSION}",
            "adb shell pm grant $packageName ${SessionScanner.USAGE_STATS_PERMISSION}",
            "adb shell appops set $packageName $USAGE_STATS_APP_OP allow"
        )

        private const val PROCESS_COMMAND = "dumpsys activity lru"
        private val PROCESS_ENTRY = Regex("""\b\d+:([A-Za-z0-9_.:-]+)/[A-Za-z0-9_]+""")

        @Volatile
        private var instance: ShizukuBridge? = null

        /**
         * 全局单例。界面和后台服务必须共用同一个实例：
         * Shizuku 的 binder 监听只能注册一次，重复注册会导致回调乱序、绑定状态互相覆盖。
         */
        fun obtain(context: Context): ShizukuBridge = instance ?: synchronized(this) {
            instance ?: ShizukuBridge(context.applicationContext).also { instance = it }
        }
    }
}
