# AppVolume

Android **每应用独立音量 + 声道平衡**控制工具。无需 Root，通过给 AudioFlinger 音频会话挂载音效实现，附带一套荣耀风格的全局音量 UI（悬浮按钮、悬浮胶囊、音量面板）。

![platform](https://img.shields.io/badge/platform-Android%2010%2B-green) ![license](https://img.shields.io/badge/license-MIT-blue)

## 功能

- **每应用音量**：对任意正在发声的应用独立衰减（`LoudnessEnhancer` 负增益）
- **每应用声道平衡**：左右声道独立增益，等功率声像律（移动声像不改变总响度）
- **音量键悬浮控制**：无障碍服务旁听音量键，弹出自绘悬浮胶囊/音量面板，不消费按键事件
- **音量面板**（荣耀风格）：纵向滑条 + 应用图标在滑条底部，一页 5 应用横向翻页
- **动态衰减范围**：推子底端随系统主音量收缩（主音量 75% → 约 -60~0 dB），量程 20–120 dB 可调，支持额外衰减 0–20 dB
- **dB 线性推子**：滑条行程等分 dB，听感均匀；底部 4% 静音区
- **应用内日志**：日志/崩溃落盘 + 设置内复制，无需 adb 即可排查问题

## 工作原理

Android 没有公开的「改别的应用音量」API。AudioFlinger 对「往某个 session 挂音效」不做归属校验——只要拿到 session id，任何进程都能把自己的 AudioEffect 插进别的应用的播放链路：

1. [Shizuku](https://shizuku.rikka.app/)（shell 身份）执行 `pm grant` 授予 `DUMP` 权限
2. 跑 `dumpsys media.audio_flinger` 解析出「正在发声的应用 → session id」表
3. 对目标 session 按需挂载 `LoudnessEnhancer`（音量）和 `DynamicsProcessing`（声道平衡）

前台服务常驻监控，应用新开会话即自动挂上；未配置的应用完全透明（不挂任何音效）。

## 使用要求

- Android 10+（minSdk 29）
- [Shizuku](https://shizuku.rikka.app/) 已运行并授权（用于授予 `DUMP` 权限）
- 手动授予：使用情况访问权限、无障碍服务、悬浮窗权限

## 构建

```bash
./gradlew assembleRelease
# 产物 ~2.1 MB（R8 minify），debug key 签名可直接安装
```

技术细节与踩坑记录见 [PROJECT.md](PROJECT.md)。

## License

[MIT](LICENSE)
