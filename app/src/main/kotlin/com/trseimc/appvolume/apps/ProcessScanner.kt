package com.trseimc.appvolume.apps

import android.content.Context
import android.content.pm.PackageManager
import com.trseimc.appvolume.audio.SessionScanner

/**
 * 扫描「此刻真正在运行的应用进程」。
 *
 * 为什么不用 ActivityManager.getRunningAppProcesses()：Android 5.0 起它只返回调用者自身，
 * 第三方拿不到别人的进程；系统最近任务列表也没有开放接口。ActivityManagerService 的 dump
 * 里有完整的进程表（每项以 `pid:进程名/uid` 结尾），拿到 DUMP 权限就能读 —— 这才是
 * 「后台开启了哪些应用」的准确答案，不受 UsageStats 落盘延迟影响，也跟「最近用过」无关。
 *
 * 不同 ROM 对 dump 子命令的裁剪不一样（有的保留了 `processes` 却输出空表），所以这里不赌单个
 * 命令，按代价从小到大依次尝试，取第一个真的有内容的；每个候选的实测结果都记在 [Snapshot.probes]
 * 里暴露给界面，出问题时一眼能看出卡在哪。
 */
class ProcessScanner(private val context: Context) {

    /** 单个候选通道的实测结果，直接显示给用户。 */
    data class Probe(val command: String, val count: Int, val note: String)

    data class Snapshot(val packages: Set<String>, val probes: List<Probe>) {
        val worked: Boolean get() = packages.isNotEmpty()
    }

    // 上次跑出内容的命令，下次直接用，省得每 8 秒把四个候选全跑一遍
    @Volatile
    private var workingCommand: String? = null

    fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(SessionScanner.DUMP_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasUsageStatsPermission(): Boolean =
        context.checkSelfPermission(SessionScanner.USAGE_STATS_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 能不能读进程表。
     *
     * 关键事实（AOSP `DumpUtils.checkDumpAndUsageStatsPermission`）：AMS 系的所有 dump
     * （`activity lru` / `activity processes` / `meminfo` / `procstats`）共用**同一道门**，
     * 而这道门是「DUMP **且** PACKAGE_USAGE_STATS **且** appop 允许」的与运算。
     * 只有 DUMP 时，换任何子命令都是同一个 Permission Denial —— 别再一条条试了。
     *
     * 另外：`PACKAGE_USAGE_STATS` 的清单权限拿不到「使用情况访问」那个开关，
     * 只能 shell 跑一次 `pm grant`。
     */
    fun hasPermission(): Boolean = hasDumpPermission() && hasUsageStatsPermission()

    /** 返回正在运行的进程名集合（进程名通常等于包名）。没有权限时返回空集合。 */
    fun scan(): Snapshot {
        if (!hasPermission()) {
            return Snapshot(emptySet(), listOf(Probe(NONE, 0, missingPermissionNote())))
        }

        workingCommand?.let { command ->
            val text = exec(command)
            if (text != null) {
                val packages = parse(text)
                if (packages.isNotEmpty()) {
                    return Snapshot(packages, listOf(Probe(command, packages.size, "沿用上次成功的通道")))
                }
            }
            workingCommand = null
        }

        val probes = ArrayList<Probe>(CANDIDATES.size)
        for (candidate in CANDIDATES) {
            val text = exec(candidate)
            if (text == null) {
                probes += Probe(candidate, 0, "命令执行失败")
                continue
            }
            val packages = parse(text)
            probes += Probe(candidate, packages.size, if (packages.isEmpty()) reasonOf(text) else "可用")
            if (packages.isNotEmpty()) {
                workingCommand = candidate
                return Snapshot(packages, probes)
            }
            // 同一个权限门，没必要把剩下的候选再撞一遍
            if (text.contains(DENIAL_MARKER)) break
        }
        return Snapshot(emptySet(), probes)
    }

    private fun missingPermissionNote(): String = when {
        !hasDumpPermission() && !hasUsageStatsPermission() ->
            "缺 DUMP 与 PACKAGE_USAGE_STATS 两个权限"

        !hasDumpPermission() -> "缺 DUMP 权限"

        else -> "缺 PACKAGE_USAGE_STATS 清单权限（设置里的「使用情况访问」开关补不上这条）"
    }

    /**
     * 借别人的身份读进程表（Shizuku 的 shell，uid 2000）。
     *
     * 本进程被拒时的第二条路：ActivityManagerService 的 dump 走的是
     * `checkDumpAndUsageStatsPermission`，普通应用即使拿到了 DUMP，也可能因为
     * PACKAGE_USAGE_STATS 或 ROM 的额外限制被挡；shell 身份则一定能读。
     */
    fun scanVia(runner: (String) -> String?): Snapshot {
        val probes = ArrayList<Probe>(CANDIDATES.size)
        for (candidate in CANDIDATES) {
            val text = runCatching { runner(candidate) }.getOrNull()
            if (text == null) {
                probes += Probe(candidate, 0, "借 shell 身份执行失败")
                continue
            }
            val packages = parse(text)
            probes += Probe(
                candidate,
                packages.size,
                if (packages.isEmpty()) reasonOf(text) else "可用（借 shell 身份）"
            )
            if (packages.isNotEmpty()) return Snapshot(packages, probes)
        }
        return Snapshot(emptySet(), probes)
    }

    /**
     * 从 dump 文本里提取进程名。进程名不等于包名的情况（`android.process.acore` 这类）
     * 交给上层用 PackageManager 校验，查不到自然会被丢掉。
     */
    fun parse(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val self = context.packageName
        val result = HashSet<String>()
        for (match in PROCESS_ENTRY.findAll(text)) {
            keep(result, match.groupValues[1], self)
        }
        return result
    }

    private fun keep(result: MutableSet<String>, raw: String, self: String) {
        // 进程名可能带 `:remote` 之类的后缀，取主进程名
        val name = raw.substringBefore(':')
        if (name == self) return
        // 包名一定含点号，这条能滤掉 system / media / zygote / surfaceflinger 这类内核进程
        if (name.length < 4 || '.' !in name) return
        // 会话表/进程表里的 uid 占位名，不是包名
        if (name.startsWith("android.uid.")) return
        if (name in NON_APP_PROCESSES) return
        result.add(name)
    }

    /**
     * 失败时说明原因：空输出、权限拒绝、还是别的。
     * 权限拒绝那几行的尾巴很关键（缺 DUMP 还是缺 PACKAGE_USAGE_STATS 就写在末尾），
     * 所以截得宽松一点，别把结论截掉。
     */
    private fun reasonOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "输出为空"
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length > 220) firstLine.take(220) + "…" else firstLine
    }

    private fun exec(command: String): String? = runCatching {
        // redirectErrorStream 很关键：只读 stdout 而不排空 stderr 时，子进程写满 stderr 管道会卡死
        val process = ProcessBuilder(command.split(' '))
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(16 * 1024)
            val out = StringBuilder()
            while (out.length < MAX_CHARS) {
                val read = reader.read(buffer)
                if (read <= 0) break
                out.append(buffer, 0, read)
            }
            out.toString()
        }
        runCatching { process.waitFor() }
        runCatching { process.destroy() }
        text
    }.getOrNull()

    private companion object {
        const val NONE = "—"
        const val MAX_CHARS = 24 * 1024 * 1024

        /** AMS dump 被拒时每一条候选的回复都以它开头，用来判断「换命令也没用」。 */
        const val DENIAL_MARKER = "Permission Denial"

        // 从便宜到贵：lru 只打印进程表，processes 还会带上每个进程的详情，整份 activity 最大。
        // 它们共用同一道权限门，留着多条只是为了应对个别 ROM 对子命令的裁剪。
        val CANDIDATES = listOf(
            "dumpsys activity lru",
            "dumpsys activity processes",
            "dumpsys activity"
        )

        // dump 里每条进程记录都以 `pid:进程名/用户id` 结尾，例如：
        //   Proc # 0: fore  T/A/TOP  LCM t:0  b:  0 16067:com.android.systemui/u0a123
        private val PROCESS_ENTRY =
            Regex("""\b\d+:([A-Za-z0-9_.:-]+)/[A-Za-z0-9_]+""")

        // 含点号但属于系统内部支撑进程的，排掉免得刷屏
        private val NON_APP_PROCESSES = setOf(
            "android.process.acore",
            "android.process.media",
            "android.process.phone"
        )
    }
}
