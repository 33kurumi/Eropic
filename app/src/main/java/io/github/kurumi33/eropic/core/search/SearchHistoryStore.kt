package io.github.kurumi33.eropic.core.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 图库 / 文件夹搜索历史的本地存储（v0.6）。
 *
 * 设计要点：
 * - 单独一个 DataStore 文件，不和界面设置混在一起：搜索历史写得很频繁，
 *   混进 eropic_settings 会让"改个主题"也触发整包偏好重读；
 * - 存成多行文本（一条一行）。用户搜的内容里不会出现换行，编码简单、可读、坏了也不会整包解析失败；
 * - 最多保留 [MAX_ENTRIES] 条，新的在最前，重复词先去重再插入；
 * - 纯本地，不上传、不统计。
 */
class SearchHistoryStore(private val context: Context) {

    /** 搜索历史（最新在最前）；读取失败一律当作空列表，不阻塞 UI。 */
    val history: Flow<List<String>> = context.searchHistoryDataStore.data
        .map { prefs -> decode(prefs[Keys.HISTORY]) }
        .distinctUntilChanged()

    /** 记录一次搜索：去首尾空白、忽略空串、去重后插到最前面。 */
    suspend fun add(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        context.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[Keys.HISTORY])
            val next = buildList {
                add(clean)
                for (item in current) if (item != clean) add(item)
            }.take(MAX_ENTRIES)
            prefs[Keys.HISTORY] = next.joinToString(SEPARATOR)
        }
    }

    /** 删除单条历史。 */
    suspend fun remove(entry: String) {
        context.searchHistoryDataStore.edit { prefs ->
            val next = decode(prefs[Keys.HISTORY]).filterNot { it == entry }
            prefs[Keys.HISTORY] = next.joinToString(SEPARATOR)
        }
    }

    /** 一键清空历史。 */
    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs -> prefs.remove(Keys.HISTORY) }
    }

    private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.take(MAX_ENTRIES)
        ?: emptyList()

    private object Keys {
        val HISTORY = stringPreferencesKey("search_history")
    }

    private companion object {
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 50
    }
}

private val Context.searchHistoryDataStore by preferencesDataStore(name = "eropic_search_history")
