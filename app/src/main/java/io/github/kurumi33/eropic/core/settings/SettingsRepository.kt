package io.github.kurumi33.eropic.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.kurumi33.eropic.core.media.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val THUMBNAIL_CACHE_ENABLED = booleanPreferencesKey("thumbnail_cache_enabled")
        val THUMBNAIL_CACHE_MB = intPreferencesKey("thumbnail_cache_mb")
        val THUMBNAIL_BACKFILL_CHARGING_ONLY = booleanPreferencesKey("thumbnail_backfill_charging_only")
        val AUTO_RESUME_INDEX = booleanPreferencesKey("auto_resume_index")
        val DATE_GROUPING = booleanPreferencesKey("date_grouping")
        val SLIDESHOW_INTERVAL = intPreferencesKey("slideshow_interval")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val SIMILARITY = stringPreferencesKey("similarity")
        val SCAN_INCLUDE_HIDDEN = booleanPreferencesKey("scan_include_hidden")
        val SCAN_EXCLUDED_DIRS = stringPreferencesKey("scan_excluded_dirs")
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
            thumbnailCacheEnabled = prefs[Keys.THUMBNAIL_CACHE_ENABLED] ?: defaults.thumbnailCacheEnabled,
            thumbnailCacheMb = (prefs[Keys.THUMBNAIL_CACHE_MB] ?: defaults.thumbnailCacheMb).coerceIn(128, 4096),
            thumbnailBackfillChargingOnly = prefs[Keys.THUMBNAIL_BACKFILL_CHARGING_ONLY]
                ?: defaults.thumbnailBackfillChargingOnly,
            autoResumeIndex = prefs[Keys.AUTO_RESUME_INDEX] ?: defaults.autoResumeIndex,
            dateGrouping = prefs[Keys.DATE_GROUPING] ?: defaults.dateGrouping,
            slideshowIntervalSec = (prefs[Keys.SLIDESHOW_INTERVAL] ?: defaults.slideshowIntervalSec)
                .coerceIn(MIN_SLIDESHOW_SEC, MAX_SLIDESHOW_SEC),
            retention = RetentionOption.fromDays(prefs[Keys.RETENTION_DAYS] ?: defaults.retention.days),
            similarity = SimilarityLevel.fromName(prefs[Keys.SIMILARITY]),
            scanIncludeHidden = prefs[Keys.SCAN_INCLUDE_HIDDEN] ?: defaults.scanIncludeHidden,
            scanExcludedDirs = parseDirs(prefs[Keys.SCAN_EXCLUDED_DIRS]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setGridColumns(columns: Int) =
        context.settingsDataStore.edit { it[Keys.GRID_COLUMNS] = columns.coerceIn(3, 6) }

    suspend fun setShowMeta(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.SHOW_META] = enabled }

    suspend fun setDefaultSort(sort: SortOrder) = context.settingsDataStore.edit { it[Keys.DEFAULT_SORT] = sort.name }

    suspend fun setThumbnailCacheEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.THUMBNAIL_CACHE_ENABLED] = enabled }

    suspend fun setThumbnailCacheMb(mb: Int) =
        context.settingsDataStore.edit { it[Keys.THUMBNAIL_CACHE_MB] = mb.coerceIn(128, 4096) }

    suspend fun setThumbnailBackfillChargingOnly(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.THUMBNAIL_BACKFILL_CHARGING_ONLY] = enabled }

    suspend fun setAutoResumeIndex(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.AUTO_RESUME_INDEX] = enabled }

    suspend fun setDateGrouping(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.DATE_GROUPING] = enabled }

    suspend fun setSlideshowInterval(seconds: Int) =
        context.settingsDataStore.edit { it[Keys.SLIDESHOW_INTERVAL] = seconds.coerceIn(MIN_SLIDESHOW_SEC, MAX_SLIDESHOW_SEC) }

    suspend fun setRetention(option: RetentionOption) =
        context.settingsDataStore.edit { it[Keys.RETENTION_DAYS] = option.days }

    suspend fun setSimilarity(level: SimilarityLevel) =
        context.settingsDataStore.edit { it[Keys.SIMILARITY] = level.name }

    suspend fun setScanIncludeHidden(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.SCAN_INCLUDE_HIDDEN] = enabled }

    /** 新增一个排除目录（相对路径，结尾斜杠会被去掉）；重复添加不报错。 */
    suspend fun addScanExcludedDir(raw: String) {
        val dir = raw.trim().trim('/')
        if (dir.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = parseDirs(prefs[Keys.SCAN_EXCLUDED_DIRS])
            if (current.none { it.equals(dir, ignoreCase = true) }) {
                prefs[Keys.SCAN_EXCLUDED_DIRS] = (current + dir).joinToString(DIR_SEPARATOR)
            }
        }
    }

    suspend fun removeScanExcludedDir(dir: String) {
        context.settingsDataStore.edit { prefs ->
            val current = parseDirs(prefs[Keys.SCAN_EXCLUDED_DIRS])
            prefs[Keys.SCAN_EXCLUDED_DIRS] = current.filterNot { it == dir }.joinToString(DIR_SEPARATOR)
        }
    }

    suspend fun clearScanExcludedDirs() =
        context.settingsDataStore.edit { it.remove(Keys.SCAN_EXCLUDED_DIRS) }

    private companion object {
        const val DIR_SEPARATOR = "\n"
        const val MIN_SLIDESHOW_SEC = 1
        const val MAX_SLIDESHOW_SEC = 15

        fun parseDirs(raw: String?): List<String> =
            raw?.split(DIR_SEPARATOR)?.map { it.trim().trim('/') }?.filter { it.isNotEmpty() }?.distinct()
                ?: emptyList()
    }
}
