package com.trseimc.appvolume.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.volumeSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "volume_settings")

/**
 * 音量调节相关的全局设置（设置浮窗「音量设置」组）。
 *
 * 应用增益的衰减范围 = -(量程 × 系统主音量比例) - 额外衰减：
 * - 量程是主音量 100% 时的全量程，默认 80dB——偏大是保险，小了会出现
 *   「滑条拖到头衰减仍然不够」的情况；
 * - 额外衰减是在按主音量收缩后的底端上再加深 n dB
 *   （例：量程 80、主音量 10% → 底端 -8dB；额外衰减 2 → 最终 -10dB）。
 */
data class VolumeSettings(
    val fullRangeDb: Int = 80, // 20–120，1 dB 刻度
    val extraDb: Int = 0       // 0–20，1 dB 刻度
)

/** 音量调节全局设置的持久化（独立 DataStore 文件，与其他设置互不干扰）。 */
class VolumeSettingsStore(private val context: Context) {

    private object Keys {
        val fullRangeDb = intPreferencesKey("full_range_db")
        val extraDb = intPreferencesKey("extra_db")
    }

    private fun read(prefs: Preferences): VolumeSettings = VolumeSettings(
        fullRangeDb = (prefs[Keys.fullRangeDb] ?: 80).coerceIn(20, 120),
        extraDb = (prefs[Keys.extraDb] ?: 0).coerceIn(0, 20)
    )

    val settings: Flow<VolumeSettings> = context.volumeSettingsStore.data.map(::read)

    suspend fun update(transform: (VolumeSettings) -> VolumeSettings) {
        context.volumeSettingsStore.edit { prefs ->
            val s = transform(read(prefs))
            prefs[Keys.fullRangeDb] = s.fullRangeDb
            prefs[Keys.extraDb] = s.extraDb
        }
    }
}
