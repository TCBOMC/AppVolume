package com.trseimc.appvolume.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trseimc.appvolume.audio.AppVolumeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.volumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_volumes")

/** 每个应用的音量配置持久化。按包名存，卸载重装配置会丢，这是 DataStore 的正常行为。 */
class VolumeRepository(private val context: Context) {

    private fun keyOf(packageName: String) = stringPreferencesKey("v_$packageName")

    val all: Flow<Map<String, AppVolumeState>> = context.volumeDataStore.data.map { prefs ->
        prefs.asMap().entries.associate { entry ->
            val pkg = entry.key.name.removePrefix("v_")
            pkg to AppVolumeState.parse(entry.value as? String)
        }
    }

    suspend fun save(packageName: String, state: AppVolumeState) {
        if (state.isDefault) {
            remove(packageName)
            return
        }
        context.volumeDataStore.edit { prefs ->
            prefs[keyOf(packageName)] = state.serialize()
        }
    }

    suspend fun remove(packageName: String) {
        context.volumeDataStore.edit { prefs ->
            prefs.remove(keyOf(packageName))
        }
    }
}
