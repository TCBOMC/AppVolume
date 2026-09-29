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
 * 一个屏幕方向下的按钮位置。
 * 横竖屏的音量条弹出位置不同，所以位置三项各自存一份；大小/动画/消失时间全局共用。
 */
data class ButtonPosition(
    val side: ButtonSide = ButtonSide.RIGHT,
    val edgeMarginDp: Int = 12,   // 距屏幕边缘距离（0–50 dp）
    val verticalPercent: Int = 50 // 纵向位置，屏幕高度的百分比（10–90）
)

/**
 * 音量键悬浮按钮的外观与行为设置。
 *
 * 消失时间只对「按键事件」通道生效；命中的是「窗口扫描」通道时，
 * 按钮与系统音量条窗口同步出现、同步消失，不按时间。
 * animDurationMs = 0 表示不做动画，直接出现/消失。
 */
data class ButtonSettings(
    val sizeDp: Int = 64,             // 24–96，2 dp 刻度
    val hideTimeoutMs: Int = 2000,
    val animStyle: ButtonAnimStyle = ButtonAnimStyle.SLIDE,
    val animDurationMs: Int = 200,    // 0–500 ms，1 ms 刻度
    val portrait: ButtonPosition = ButtonPosition(),
    val landscape: ButtonPosition = ButtonPosition()
)

/** 悬浮按钮设置的持久化（独立 DataStore 文件，和每应用音量配置互不干扰）。 */
class ButtonSettingsStore(private val context: Context) {

    private object Keys {
        val sizeDp = intPreferencesKey("size_dp")
        val hideTimeoutMs = intPreferencesKey("hide_timeout_ms")
        val animStyle = stringPreferencesKey("anim_style")
        val animDurationMs = intPreferencesKey("anim_duration_ms")
        val portraitSide = stringPreferencesKey("p_side")
        val portraitEdge = intPreferencesKey("p_edge_dp")
        val portraitVertical = intPreferencesKey("p_vertical_percent")
        val landscapeSide = stringPreferencesKey("l_side")
        val landscapeEdge = intPreferencesKey("l_edge_dp")
        val landscapeVertical = intPreferencesKey("l_vertical_percent")
    }

    private fun side(prefs: Preferences, key: androidx.datastore.preferences.core.Preferences.Key<String>): ButtonSide =
        prefs[key]?.let { runCatching { ButtonSide.valueOf(it) }.getOrNull() } ?: ButtonSide.RIGHT

    private fun read(prefs: Preferences): ButtonSettings = ButtonSettings(
        sizeDp = prefs[Keys.sizeDp] ?: 64,
        hideTimeoutMs = prefs[Keys.hideTimeoutMs] ?: 2000,
        animStyle = prefs[Keys.animStyle]?.let { runCatching { ButtonAnimStyle.valueOf(it) }.getOrNull() }
            ?: ButtonAnimStyle.SLIDE,
        animDurationMs = prefs[Keys.animDurationMs] ?: 200,
        portrait = ButtonPosition(
            side = side(prefs, Keys.portraitSide),
            edgeMarginDp = prefs[Keys.portraitEdge] ?: 12,
            verticalPercent = prefs[Keys.portraitVertical] ?: 50
        ),
        landscape = ButtonPosition(
            side = side(prefs, Keys.landscapeSide),
            edgeMarginDp = prefs[Keys.landscapeEdge] ?: 12,
            verticalPercent = prefs[Keys.landscapeVertical] ?: 50
        )
    )

    val settings: Flow<ButtonSettings> = context.buttonSettingsStore.data.map(::read)

    suspend fun update(transform: (ButtonSettings) -> ButtonSettings) {
        context.buttonSettingsStore.edit { prefs ->
            val s = transform(read(prefs))
            prefs[Keys.sizeDp] = s.sizeDp
            prefs[Keys.hideTimeoutMs] = s.hideTimeoutMs
            prefs[Keys.animStyle] = s.animStyle.name
            prefs[Keys.animDurationMs] = s.animDurationMs
            prefs[Keys.portraitSide] = s.portrait.side.name
            prefs[Keys.portraitEdge] = s.portrait.edgeMarginDp
            prefs[Keys.portraitVertical] = s.portrait.verticalPercent
            prefs[Keys.landscapeSide] = s.landscape.side.name
            prefs[Keys.landscapeEdge] = s.landscape.edgeMarginDp
            prefs[Keys.landscapeVertical] = s.landscape.verticalPercent
        }
    }
}
