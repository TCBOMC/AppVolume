package com.trseimc.appvolume.audio

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 从 `dumpsys media.audio_flinger` 里捞当前活跃的音频会话。
 *
 * 为什么必须走 dumpsys：AudioPlaybackConfiguration 只暴露 AudioAttributes，
 * 不暴露 session id，而 session id 才是挂音效的钥匙。AudioFlinger 的 dump 里有
 * 一张「Global session refs」表，字段顺序是 session / cnt / pid / uid / package：
 *
 * ```
 * Global session refs:
 *   session  cnt     pid    uid  name
 *      1234    1     5678  10123  com.netease.cloudmusic
 * ```
 *
 * dumpsys 需要 DUMP 权限（signature|privileged|development）。两条路可走：
 * 1. 用 Shizuku 执行 `pm grant <pkg> android.permission.DUMP` 把权限授予自己，之后本进程直接跑；
 * 2. 退而求其次，每次都借 Shizuku 的 shell 身份跑 dumpsys。
 */
class SessionScanner(private val context: Context) {

    fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(DUMP_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * PACKAGE_USAGE_STATS 的**清单权限**是否到手。
     *
     * 注意这和「使用情况访问」开关不是一回事：设置里那个开关只把 appop
     * `GET_USAGE_STATS` 置为 allow（见 Settings 的 UsageAccessDetails），**不会**授予清单权限。
     * 而 ActivityManagerService 的 dump 走 `DumpUtils.checkUsageStatsPermission`，
     * 它先查清单权限、再查 appop —— 两者都过才算数。所以想读系统进程表，
     * 只能靠 shell 跑一次 `pm grant`，设置页开关补不上。
     */
    fun hasUsageStatsPermission(): Boolean =
        context.checkSelfPermission(USAGE_STATS_PERMISSION) == PackageManager.PERMISSION_GRANTED

    data class ScanResult(
        val sessions: List<AudioSessionInfo>,
        val source: SessionRegistry.Source,
        val error: String?,
        val rawSection: String? = null
    )

    /**
     * @param shellRunner 没有 DUMP 权限时的兜底通道，以 shell 身份执行命令。
     */
    fun scan(shellRunner: ((String) -> String?)? = null): ScanResult {
        return if (hasDumpPermission()) {
            val text = runLocal()
            if (text.isNullOrBlank()) {
                ScanResult(emptyList(), SessionRegistry.Source.LOCAL, "dumpsys 返回空，DUMP 权限可能没真正生效")
            } else {
                ScanResult(parse(text), SessionRegistry.Source.LOCAL, null, rawSectionOf(text))
            }
        } else if (shellRunner == null) {
            ScanResult(emptyList(), SessionRegistry.Source.NONE, "没有 DUMP 权限，也没有可用的 shell 通道")
        } else {
            val text = runCatching { shellRunner(DUMP_COMMAND) }.getOrNull()
            if (text.isNullOrBlank()) {
                ScanResult(emptyList(), SessionRegistry.Source.SHIZUKU, "Shizuku 执行 dumpsys 失败")
            } else {
                ScanResult(parse(text), SessionRegistry.Source.SHIZUKU, null, rawSectionOf(text))
            }
        }
    }

    /** 会话表原始行，诊断用。行太长截掉尾部，防止刷屏。 */
    private fun rawSectionOf(text: String): String? {
        val headerIdx = text.indexOf(SECTION_HEADER)
        if (headerIdx < 0) return null
        val section = text.substring(headerIdx)
        return section.lineSequence()
            .take(RAW_DIAG_LINES)
            .joinToString("\n") { it.take(120) }
    }

    private fun runLocal(): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "media.audio_flinger"))
        val text = process.inputStream.bufferedReader().readText()
        runCatching { process.destroy() }
        text
    }.getOrNull()

    /**
     * 解析算法**与示例 APK（sulefi.volume.ctl）的 AUtils.getAudioSessionList 完全一致**：
     *
     * 1. 截取 `Global session refs:\n` 之后、第一个 `Hardware` 之前的整段文本 ——
     *    中间哪怕混着顶格行也照单全收。之前我们用「遇到顶格行就 break」的写法，
     *    在部分 ROM 上会话表中间插了别的段落，后面的会话行全被丢掉，
     *    表现为「正在播的应用不在列表里」。
     * 2. 每行 trim 后折叠连续空白、按空白切列，**至少 5 列**才收，不锚定行尾，
     *    多出来的列不影响（不做整行正则匹配）。
     * 3. 取 `parts[0]`=session、`parts[2]`=pid、`parts[3]`=uid、`parts[4]`=name。
     */
    fun parse(text: String): List<AudioSessionInfo> {
        val result = ArrayList<AudioSessionInfo>()
        val uidCache = HashMap<Int, String?>()
        val seenSessions = HashSet<Int>()

        val headerIdx = text.indexOf(SECTION_HEADER + "\n")
        if (headerIdx < 0) return result
        var section = text.substring(headerIdx + SECTION_HEADER.length + 1)

        // 终点是行首的 Hardware 段落头；找不到就吃到结尾（后面的杂行会被列数过滤掉）
        val hardwareIdx = section.indexOf("\nHardware")
        if (hardwareIdx >= 0) section = section.substring(0, hardwareIdx)

        // 第一行是表头（session cnt pid uid name），跳过
        val firstNewline = section.indexOf('\n')
        if (firstNewline < 0) return result
        section = section.substring(firstNewline + 1)

        for (rawLine in section.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 5) continue
            val sessionId = parts[0].toIntOrNull() ?: continue
            if (sessionId <= 0 || sessionId > MAX_SESSION_ID) continue
            // 同一个 session 可能被多个客户端引用（cnt>1），表里会出现多行，去重
            if (!seenSessions.add(sessionId)) continue
            val pid = parts[2].toIntOrNull() ?: 0
            val uidValue = parts[3].toIntOrNull() ?: 0
            val rawName = parts[4]
            val packageName = uidCache.getOrPut(uidValue) { resolvePackage(uidValue, rawName) }
                ?: continue
            result.add(
                AudioSessionInfo(
                    sessionId = sessionId,
                    pid = pid,
                    uid = uidValue,
                    packageName = packageName,
                    rawName = rawName
                )
            )
        }
        return result
    }

    /**
     * 会话归属：**和示例一致，表里的 name 列是首选** —— 它是 audioserver 按 uid 反查出来的
     * 包名，只要它是个真实安装的包就直接用（示例 APK 就是 `pkgName = parts[4]`，实测在本机
     * 能正确列出正在发声的应用）。
     *
     * name 列拿不出包名时（`android.uid.systemui` 占位、空、缓存过期），退回用 uid 反查：
     * [PackageManager.getPackagesForUid] 是包管理器的当下事实。
     * 共享 uid 时（Honor 把预装组件塞进 systemui 的 uid）用占位名后缀去挑，挑不出再选
     * 「用户自己装的」优先，避免稳定串到预装组件头上。
     */
    private fun resolvePackage(uid: Int, rawName: String): String? {
        // 1) name 列本身就是已安装的包名 → 直接采用
        if ('.' in rawName && !rawName.startsWith(UID_NAME_PREFIX) && isInstalled(rawName)) {
            return rawName
        }

        // 2) 占位名 / 空名 → uid 反查
        if (uid > 0) {
            val byUid = runCatching { context.packageManager.getPackagesForUid(uid) }
                .getOrNull()
                ?.filter { isInstalled(it) }
                .orEmpty()

            if (rawName.startsWith(UID_NAME_PREFIX)) {
                val hint = rawName.removePrefix(UID_NAME_PREFIX)
                byUid.firstOrNull { it.substringAfterLast('.') == hint }?.let { return it }
            }

            byUid.firstOrNull { !isPureSystemApp(it) }?.let { return it }
            byUid.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    private fun isPureSystemApp(pkg: String): Boolean {
        val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }
            .getOrNull() ?: return false
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) return false
        if (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return false
        val dir = info.sourceDir ?: return true
        return !dir.startsWith("/data/") && !dir.contains("/data/app")
    }

    companion object {
        const val DUMP_PERMISSION = "android.permission.DUMP"
        const val USAGE_STATS_PERMISSION = "android.permission.PACKAGE_USAGE_STATS"
        const val DUMP_COMMAND = "dumpsys media.audio_flinger"

        private const val SECTION_HEADER = "Global session refs:"
        private const val UID_NAME_PREFIX = "android.uid."

        // session id 来自 audioserver 的全局自增计数，正常不会超过百万
        private const val MAX_SESSION_ID = 9_999_999

        // 诊断面板最多贴多少行原始表
        private const val RAW_DIAG_LINES = 14
    }
}
