# AppVolume 项目文档

Android 每应用独立音量与声道平衡控制工具。包名 `com.trseimc.appvolume`，Kotlin + Jetpack Compose，当前版本 **v1.7.6**。

## 一、项目定位

Android 没有公开的「改别的应用音量」API。AppVolume 通过 **AudioFlinger 会话音效**方案实现无 Root 的每应用音量控制，并附带一套荣耀风格的全局音量 UI（悬浮按钮、悬浮胶囊、音量面板）。

- 最低支持 Android 10（minSdk 29），targetSdk 36
- Release 包 ~2.1 MB（R8 minify + resource shrink，material-icons-extended 裁剪）
- 依赖 Shizuku 授予 `DUMP` 权限读取音频会话表

## 二、功能总览

| 功能 | 说明 |
|---|---|
| 每应用音量 | 给目标应用的音频会话挂 `LoudnessEnhancer` 做负增益衰减 |
| 每应用声道平衡 | `DynamicsProcessing` 按通道独立增益，等功率声像律 |
| 音量键悬浮控制 | 无障碍服务旁听音量键，弹出自绘悬浮胶囊/面板，不消费按键 |
| 音量面板（荣耀风格） | 纵向滑条 + 应用图标在滑条底部，ViewPager2 一页 5 应用横向翻页 |
| 动态衰减范围 | 随系统主音量收缩推子底端：主音量 75% → 范围约 -45~0 dB |
| 音量设置组 | 量程 20–120 dB（默认 80）、额外衰减 0–20 dB（默认 0） |
| 应用内日志 | FileLog 落盘 + 闪退无条件记录 + 设置里复制/清除（替代 adb） |

## 三、技术架构

```
app/src/main/kotlin/com/trseimc/appvolume/
├── apps/          AppEntry / AppScanner / ProcessScanner —— 应用清单与进程扫描
├── audio/         核心音频引擎
│   ├── SessionScanner / SessionRegistry   dumpsys media.audio_flinger 解析、会话缓存
│   ├── SessionEffectEngine                音效挂载与下发（LoudnessEnhancer + DynamicsProcessing）
│   ├── VolumeEngine / SessionVolumeEngine 引擎接口与实现（可替换为 Root DSP 方案）
│   ├── AudioCurve                         dB 推子曲线、声像律、响度换算
│   ├── MasterVolume                       主音量缓存单例（masterFraction/fullRangeDb/extraDb）
│   ├── AudioControlService                前台常驻服务，会话出现即挂音效
│   └── ShizukuBridge / ShizukuShellService Shizuku 拉起 shell 进程跑 pm grant
├── data/          VolumeRepository / ButtonSettingsStore / VolumeSettingsStore（DataStore）
├── overlay/       VolumeWatcherService（无障碍入口）、OverlayChrome、VolumeSliderView、VolumeButtonView
├── ui/            Compose：AppListScreen（主页）、VolumeSheet、WindowKit（悬浮窗容器）、
│                  HorizontalVolumeSlider、AppIcon、AppVolumeViewModel
└── util/FileLog   日志与崩溃落盘
```

### 核心原理：AudioFlinger 会话音效

AudioFlinger 对「往某个 session 挂音效」不做归属校验——只要拿到 session id，任何进程都能把自己的 AudioEffect 插进别的应用的播放链路。因此：

1. Shizuku（shell 身份）执行 `pm grant` 授予 `DUMP` 权限；
2. 跑 `dumpsys media.audio_flinger` 解析出「正在发声的应用 → session id」表；
3. 对目标 session 挂 `LoudnessEnhancer`（音量，`amp = 10^(gainmB/2000)`，负值即衰减）和 `DynamicsProcessing`（左右声道独立增益）。

按需挂载：音量满格不挂 LoudnessEnhancer、声道居中不挂 DynamicsProcessing，对未配置的应用完全透明。`AudioControlService` 前台常驻，应用新开会话即自动挂上（音效随进程死亡，所以服务必须活着）。

### 三大系统组件（AndroidManifest）

| 组件 | 作用 |
|---|---|
| `MainActivity` | Compose 主页：应用列表 + 卡片（顶部横置音量条）+ 设置浮窗 |
| `AudioControlService` | foregroundServiceType=mediaPlayback，常驻挂音效 |
| `VolumeWatcherService` | 无障碍服务：旁听音量键、观察系统音量面板窗口、管理悬浮按钮与音量浮窗，**永远不消费按键** |
| `ShizukuShellService` | Shizuku 拉起的独立进程服务（shell/root 身份） |

### 关键权限

- `PACKAGE_USAGE_STATS`（使用情况访问，手动授予）— 识别最近/正在使用的应用
- `DUMP`（signature|privileged 级，靠 Shizuku `pm grant` 一次）
- `MODIFY_AUDIO_SETTINGS`、`SYSTEM_ALERT_WINDOW`（悬浮窗，手动授予）
- `QUERY_ALL_PACKAGES` — API 30+ 不声明则会话表 name 列解析失败，正在发声的应用会从列表消失

## 四、音频模型（AudioCurve）

- **主音量走 dB 线性推子**（调音台 fader 标准）：滑条行程等分 dB，听感均匀。顶端 0 dB（不放大，避免削波），底部默认 -80 dB。
- **动态底端**：`minDb = -(量程 × 系统主音量比例) - 额外衰减`。主音量 75% + 量程 80 → 底端 -60；主音量 10% → -8 附近。主音量由 1 秒轮询同步进 `MasterVolume` 单例。
- **静音区**：滑条底部 4% 直接 -∞，避免滑到 0 还有底噪。
- **声道平衡走等功率声像律**：cos/sin 三角分配，L² + R² ≡ 1，移动声像不改变总响度（线性交叉会让中间位置响度掉一半）。
- **响度百分比**：按 2^(dB/10) 归一（+10 dB ≈ 响一倍），与滑条位置是两个概念。

## 五、关键踩坑记录

| 问题 | 结论 |
|---|---|
| Dialog 里读 WindowInsets 恒为 0 | Dialog 是 floating window，窗口帧不含系统栏。必须沿 `ContextWrapper.baseContext` 链 `findActivity()`，从 **Activity 的 decorView** 读 `ViewCompat.getRootWindowInsets`（`systemBars() or displayCutout()`），悬浮窗据此限高留出安全区 |
| 荣耀新系统破坏 FLAG_BLUR_BEHIND | 系统强制把 behind-blur 扩成全屏模糊殃及其他应用。弃用系统 blur，改自绘毛玻璃：Activity decorView 快照（drawToBitmap 缩 6x）+ Compose `blur(24dp)` 垫底 + 白雾层 |
| ViewPager2 闪退 `Pages must fill the whole ViewPager2` | 页根容器必须显式 MATCH_PARENT，wrap_content 直接崩。通过应用内 FileLog 抓到的日志定位 |
| overlay 拿不到别的应用像素 | 悬浮窗无法截取其他应用画面做自绘模糊，所以面板只能用纯半透明（0xF2），应用内浮窗才能用快照毛玻璃（alpha 可以更透 0xB3） |
| closeCapsule 触控区过小 | 只有背景 drawable 用了 sizeDp，容器本身没有尺寸 → 补 `minimumWidth/minimumHeight` |
| API 30 包可见性 | 见上文 QUERY_ALL_PACKAGES |
| DynamicsProcessing 默认配置 | 自带一个 -2 dB 限幅器会把声音压掉，构造时只保留 0 dB 的 PreEQ 段 |
| 音量键悬浮按钮荣耀 ROM 弹不出 | 面板窗口兜底触发 + 防抖 + 观测（v1.5.2） |

### 语义约定

- 「透明度更高」= **更透** = alpha 数值更低（ARGB 前两位 00 全透 / FF 全不透）。
- 有背景模糊的界面 alpha 可以更高（更透），没有模糊的要更实。

## 六、版本历史

| 版本 | 提交 | 内容 |
|---|---|---|
| v1.4.1 | `fbc087a` | 每应用音量 + 声道平衡（AudioFlinger 会话音效方案） |
| v1.4.2/3 | `fe7ccf7`/`36e8e32` | 拖动音量条实时生效 + 实时推送通道 |
| v1.5.0 | `1271ff7` | 音量键悬浮控制（无障碍方案） |
| v1.5.2 | `838a3e4` | 荣耀 ROM 音量键弹不出修复 |
| v1.6.0 | `3e2f402` | 悬浮按钮设置 + 白底胶囊样式 |
| v1.6.2/3 | `241a57e`/`a452ca2` | 窗口 id 快路径零 IPC + 量程细化、横竖屏位置分离 |
| v1.7.0 | `089a891`/`a7f6cac` | 统一悬浮窗容器 + 安全区定稿（Activity decorView 读 insets） |
| v1.7.1 | `b258c4e` | 自绘毛玻璃，弃用系统 blurBehind |
| v1.7.2 | `f07df93` | 音量面板重构（ViewPager2）+ 闪退修复 + 应用内日志 |
| v1.7.3 | `2570d2f` | Material 齿轮图标 + 差异化透明度 |
| v1.7.5 | `83df3b8` | 音量设置组（量程默认 80 + 额外衰减）+ 动态衰减范围 |
| v1.7.6 | `18edfda` | 主页卡片改造：20dp 圆角矩形 + 顶部横置音量条，删重复图标 |

## 七、构建与调试

```bash
# Release（真机一律用 release 包，debug 包 58MB 是 icons-extended 未裁剪所致）
./gradlew assembleRelease
# 产物 ~2.1MB，debug key 签名，直接安装
```

- 调试不依赖 adb：设置浮窗开「日志记录」，闪退后重开应用在设置里复制 `app_crash.txt` / `app_log.txt` 内容（512KB 轮转）。
- 首次使用流程：安装 → Shizuku 授权（`DUMP`）→ 授予使用情况访问权限 → 开启无障碍服务 → 开启悬浮窗权限。
