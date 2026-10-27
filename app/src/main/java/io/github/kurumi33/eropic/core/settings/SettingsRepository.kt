package io.github.kurumi33.eropic.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.kurumi33.eropic.core.media.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "eropic_settings")

/** 界面设置读写；读取失败一律回落到默认值，不阻塞 UI。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val SHOW_META = booleanPreferencesKey("show_meta")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            gridColumns = (prefs[Keys.GRID_COLUMNS] ?: defaults.gridColumns).coerceIn(3, 6),
            showMeta = prefs[Keys.SHOW_META] ?: defaults.showMeta,
            defaultSort = prefs[Keys.DEFAULT_SORT]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: defaults.defaultSort,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setGridColumns(columns: Int) =
        context.settingsDataStore.edit { it[Keys.GRID_COLUMNS] = columns.coerceIn(3, 6) }

    suspend fun setShowMeta(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.SHOW_META] = enabled }

    suspend fun setDefaultSort(sort: SortOrder) = context.settingsDataStore.edit { it[Keys.DEFAULT_SORT] = sort.name }
}
