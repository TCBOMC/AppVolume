package com.trseimc.appvolume.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.buttonSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "button_settings")

/** 悬浮按钮的动画样式。SLIDE=从所在侧平移出入（默认），FADE=渐显/渐隐。 */
enum class ButtonAnimStyle { SLIDE, FADE }

/** 悬浮按钮停靠在屏幕哪一侧。 */
enum class ButtonSide { LEFT, RIGHT }

/**
 * 音量键悬浮按钮的外观与行为设置。
 *
 * 消失时间只对「按键事件」通道生效；命中的是「窗口扫描」通道时，
 * 按钮与系统音量条窗口同步出现、同步消失，不按时间。
 */
data class ButtonSettings(
    val hideTimeoutMs: Int = 2000,
    val animStyle: ButtonAnimStyle = ButtonAnimStyle.SLIDE,
    val animDurationMs: Int = 200,
    val side: ButtonSide = ButtonSide.RIGHT,
    val verticalPercent: Int = 50,
    val sizeDp: Int = 64
)

/** 悬浮按钮设置的持久化（独立 DataStore 文件，和每应用音量配置互不干扰）。 */
class ButtonSettingsStore(private val context: Context) {

    private object Keys {
        val hideTimeoutMs = intPreferencesKey("hide_timeout_ms")
        val animStyle = stringPreferencesKey("anim_style")
        val animDurationMs = intPreferencesKey("anim_duration_ms")
        val side = stringPreferencesKey("side")
        val verticalPercent = intPreferencesKey("vertical_percent")
        val sizeDp = intPreferencesKey("size_dp")
    }

    private fun read(prefs: Preferences): ButtonSettings = ButtonSettings(
        hideTimeoutMs = prefs[Keys.hideTimeoutMs] ?: 2000,
        animStyle = prefs[Keys.animStyle]?.let { runCatching { ButtonAnimStyle.valueOf(it) }.getOrNull() }
            ?: ButtonAnimStyle.SLIDE,
        animDurationMs = prefs[Keys.animDurationMs] ?: 200,
        side = prefs[Keys.side]?.let { runCatching { ButtonSide.valueOf(it) }.getOrNull() }
            ?: ButtonSide.RIGHT,
        verticalPercent = prefs[Keys.verticalPercent] ?: 50,
        sizeDp = prefs[Keys.sizeDp] ?: 64
    )

    val settings: Flow<ButtonSettings> = context.buttonSettingsStore.data.map(::read)

    suspend fun update(transform: (ButtonSettings) -> ButtonSettings) {
        context.buttonSettingsStore.edit { prefs ->
            val updated = transform(read(prefs))
            prefs[Keys.hideTimeoutMs] = updated.hideTimeoutMs
            prefs[Keys.animStyle] = updated.animStyle.name
            prefs[Keys.animDurationMs] = updated.animDurationMs
            prefs[Keys.side] = updated.side.name
            prefs[Keys.verticalPercent] = updated.verticalPercent
            prefs[Keys.sizeDp] = updated.sizeDp
        }
    }
}
