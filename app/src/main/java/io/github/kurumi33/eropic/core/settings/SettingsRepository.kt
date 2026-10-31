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
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val SHOW_META = booleanPreferencesKey("show_meta")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val THUMBNAIL_CACHE_ENABLED = booleanPreferencesKey("thumbnail_cache_enabled")
        val THUMBNAIL_CACHE_MB = intPreferencesKey("thumbnail_cache_mb")
        val THUMBNAIL_BACKFILL_CHARGING_ONLY = booleanPreferencesKey("thumbnail_backfill_charging_only")
        val AUTO_RESUME_INDEX = booleanPreferencesKey("auto_resume_index")
        val DATE_GROUP_MODE = stringPreferencesKey("date_group_mode")
        val TRASH_RELATIVE_DIR = stringPreferencesKey("trash_relative_dir")
        val SLIDESHOW_INTERVAL = intPreferencesKey("slideshow_interval")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val SIMILARITY = stringPreferencesKey("similarity")
        val SCAN_INCLUDE_HIDDEN = booleanPreferencesKey("scan_include_hidden")
        val SCAN_EXCLUDED_DIRS = stringPreferencesKey("scan_excluded_dirs")
        val PINNED_FOLDERS = stringPreferencesKey("pinned_folders")
        val FOLDER_COVERS = stringPreferencesKey("folder_covers")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            gridColumns = (prefs[Keys.GRID_COLUMNS] ?: defaults.gridColumns)
                .coerceIn(AppSettings.MIN_GRID_COLUMNS, AppSettings.MAX_GRID_COLUMNS),
            showMeta = prefs[Keys.SHOW_META] ?: defaults.showMeta,
            defaultSort = prefs[Keys.DEFAULT_SORT]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: defaults.defaultSort,
            thumbnailCacheEnabled = prefs[Keys.THUMBNAIL_CACHE_ENABLED] ?: defaults.thumbnailCacheEnabled,
            thumbnailCacheMb = (prefs[Keys.THUMBNAIL_CACHE_MB] ?: defaults.thumbnailCacheMb).coerceIn(128, 4096),
            thumbnailBackfillChargingOnly = prefs[Keys.THUMBNAIL_BACKFILL_CHARGING_ONLY]
                ?: defaults.thumbnailBackfillChargingOnly,
            autoResumeIndex = prefs[Keys.AUTO_RESUME_INDEX] ?: defaults.autoResumeIndex,
            dateGroupMode = DateGroupMode.fromName(prefs[Keys.DATE_GROUP_MODE]),
            trashRelativeDir = prefs[Keys.TRASH_RELATIVE_DIR] ?: defaults.trashRelativeDir,
            slideshowIntervalSec = (prefs[Keys.SLIDESHOW_INTERVAL] ?: defaults.slideshowIntervalSec)
                .coerceIn(MIN_SLIDESHOW_SEC, MAX_SLIDESHOW_SEC),
            retention = RetentionOption.fromDays(prefs[Keys.RETENTION_DAYS] ?: defaults.retention.days),
            similarity = SimilarityLevel.fromName(prefs[Keys.SIMILARITY]),
            scanIncludeHidden = prefs[Keys.SCAN_INCLUDE_HIDDEN] ?: defaults.scanIncludeHidden,
            scanExcludedDirs = parseDirs(prefs[Keys.SCAN_EXCLUDED_DIRS]),
            pinnedFolders = parsePinned(prefs[Keys.PINNED_FOLDERS]),
            folderCovers = parseCovers(prefs[Keys.FOLDER_COVERS]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    /** v1.1：界面语言（存 code，读不到或非法时回落到跟随系统）。 */
    suspend fun setAppLanguage(language: AppLanguage) =
        context.settingsDataStore.edit { it[Keys.APP_LANGUAGE] = language.code }

    suspend fun setGridColumns(columns: Int) =
        context.settingsDataStore.edit {
            it[Keys.GRID_COLUMNS] = columns.coerceIn(AppSettings.MIN_GRID_COLUMNS, AppSettings.MAX_GRID_COLUMNS)
        }

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

    /** v0.2：图库时间分组粒度。 */
    suspend fun setDateGroupMode(mode: DateGroupMode) =
        context.settingsDataStore.edit { it[Keys.DATE_GROUP_MODE] = mode.name }

    /** 兼容 M4 的布尔入口：true = 按月，false = 不分组。 */
    suspend fun setDateGrouping(enabled: Boolean) =
        setDateGroupMode(if (enabled) DateGroupMode.MONTH else DateGroupMode.NONE)

    /**
     * v0.2：设置回收站目录（相对主存储根）。
     *
     * 规范化规则：去掉首尾空白与斜杠、拒绝绝对路径与 ".."、末级不是 .EropicTrash 时自动补上；
     * 非法输入或空串一律当作"默认位置"（<卷根>/.EropicTrash）。
     */
    suspend fun setTrashRelativeDir(relativeDir: String) {
        val normalized = normalizeTrashDir(relativeDir)
        context.settingsDataStore.edit { it[Keys.TRASH_RELATIVE_DIR] = normalized }
    }

    suspend fun setSlideshowInterval(seconds: Int) =
        context.settingsDataStore.edit { it[Keys.SLIDESHOW_INTERVAL] = seconds.coerceIn(MIN_SLIDESHOW_SEC, MAX_SLIDESHOW_SEC) }

    suspend fun setRetention(option: RetentionOption) =
        context.settingsDataStore.edit { it[Keys.RETENTION_DAYS] = option.days }

    suspend fun setSimilarity(level: SimilarityLevel) =
        context.settingsDataStore.edit { it[Keys.SIMILARITY] = level.name }

    /** v0.5：置顶 / 取消置顶一个文件夹。路径会在入库前统一去掉首尾斜杠。 */
    suspend fun setFolderPinned(relativePath: String, pinned: Boolean) {
        val path = normalizeFolderPath(relativePath)
        if (path.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = parsePinned(prefs[Keys.PINNED_FOLDERS])
            val next = if (pinned) {
                if (current.any { it.equals(path, ignoreCase = true) }) current else current + path
            } else {
                current.filterNot { it.equals(path, ignoreCase = true) }
            }
            prefs[Keys.PINNED_FOLDERS] = next.joinToString(DIR_SEPARATOR)
        }
    }

    /** v0.5：设置或清除自定义图集封面（mediaId = null 表示恢复默认）。 */
    suspend fun setFolderCover(relativePath: String, mediaId: Long?) {
        val path = normalizeFolderPath(relativePath)
        if (path.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = parseCovers(prefs[Keys.FOLDER_COVERS]).toMutableMap()
            current.remove(path)
            if (mediaId != null && mediaId > 0) current[path] = mediaId
            prefs[Keys.FOLDER_COVERS] = current.entries.joinToString(DIR_SEPARATOR) { "${it.key}|${it.value}" }
        }
    }

    /**
     * v0.5：文件夹被重命名后同步迁移置顶与封面配置。
     *
     * 不做迁移的话，重命名会让「置顶」悄悄消失、封面回退成最新图，
     * 用户会觉得软件把他的设置弄丢了。
     */
    suspend fun renameFolderPrefs(oldPath: String, newPath: String) {
        val from = normalizeFolderPath(oldPath)
        val to = normalizeFolderPath(newPath)
        if (from.isEmpty() || to.isEmpty() || from == to) return
        context.settingsDataStore.edit { prefs ->
            val pinned = parsePinned(prefs[Keys.PINNED_FOLDERS]).map {
                if (it == from || it.startsWith("$from/")) to + it.removePrefix(from) else it
            }
            prefs[Keys.PINNED_FOLDERS] = pinned.joinToString(DIR_SEPARATOR)
            val covers = parseCovers(prefs[Keys.FOLDER_COVERS]).entries.associate { (path, id) ->
                (if (path == from || path.startsWith("$from/")) to + path.removePrefix(from) else path) to id
            }
            prefs[Keys.FOLDER_COVERS] = covers.entries.joinToString(DIR_SEPARATOR) { "${it.key}|${it.value}" }
        }
    }

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

        /** 回收站目录规范化：只允许主存储根下的相对路径，末级固定为 .EropicTrash。 */
        fun normalizeTrashDir(raw: String): String {
            val trimmed = raw.trim().replace('\\', '/').trim('/')
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("/")) return ""
            val segments = trimmed.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) return ""
            val withLeaf = if (segments.last() == io.github.kurumi33.eropic.core.media.StoragePaths.TRASH_DIR_NAME) {
                segments
            } else {
                segments + io.github.kurumi33.eropic.core.media.StoragePaths.TRASH_DIR_NAME
            }
            return withLeaf.joinToString("/")
        }

        fun parseDirs(raw: String?): List<String> =
            raw?.split(DIR_SEPARATOR)?.map { it.trim().trim('/') }?.filter { it.isNotEmpty() }?.distinct()
                ?: emptyList()

        /** 文件夹相对路径规范化：反斜杠转正斜杠、去掉首尾斜杠与多余空白。 */
        fun normalizeFolderPath(raw: String): String =
            raw.trim().replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")

        fun parsePinned(raw: String?): List<String> =
            raw?.split(DIR_SEPARATOR)?.map { normalizeFolderPath(it) }?.filter { it.isNotEmpty() }?.distinct()
                ?: emptyList()

        /** 封面配置格式：每行 "相对路径|mediaId"，解析时忽略坏行。 */
        fun parseCovers(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            val result = LinkedHashMap<String, Long>()
            for (line in raw.split(DIR_SEPARATOR)) {
                val index = line.lastIndexOf('|')
                if (index <= 0) continue
                val path = normalizeFolderPath(line.substring(0, index))
                val id = line.substring(index + 1).trim().toLongOrNull() ?: continue
                if (path.isNotEmpty() && id > 0) result[path] = id
            }
            return result
        }
    }
}
