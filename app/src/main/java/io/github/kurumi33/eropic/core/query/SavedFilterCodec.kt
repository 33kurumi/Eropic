package io.github.kurumi33.eropic.core.query

import org.json.JSONArray
import org.json.JSONObject

/**
 * [MediaFilter] 与 JSON 互转，用于保存筛选条件（B-17）。
 *
 * 只写非空字段：JSON 里没有的键读回来就是 null，因此给 MediaFilter 加字段不需要改这里两次。
 */
object SavedFilterCodec {

    fun encode(filter: MediaFilter): String {
        val json = JSONObject()
        filter.sizeMinBytes?.let { json.put("sizeMinBytes", it) }
        filter.sizeMaxBytes?.let { json.put("sizeMaxBytes", it) }
        filter.widthMin?.let { json.put("widthMin", it) }
        filter.heightMin?.let { json.put("heightMin", it) }
        if (filter.mimeTypes.isNotEmpty()) {
            json.put("mimeTypes", JSONArray(filter.mimeTypes.sorted()))
        }
        filter.dateFromMs?.let { json.put("dateFromMs", it) }
        filter.dateToMs?.let { json.put("dateToMs", it) }
        filter.modifiedFromMs?.let { json.put("modifiedFromMs", it) }
        filter.modifiedToMs?.let { json.put("modifiedToMs", it) }
        if (filter.favoritesOnly) json.put("favoritesOnly", true)
        if (filter.folders.isNotEmpty()) {
            json.put("folders", JSONArray(filter.folders))
        }
        if (!filter.includeSubfolders) json.put("includeSubfolders", false)
        filter.minBlurScore?.let { json.put("minBlurScore", it.toDouble()) }
        filter.maxBlurScore?.let { json.put("maxBlurScore", it.toDouble()) }
        filter.nameLike?.takeIf { it.isNotBlank() }?.let { json.put("nameLike", it) }
        return json.toString()
    }

    /** 解析失败返回 null（旧版本写入的脏数据不应该让清理页崩掉）。 */
    fun decode(raw: String): MediaFilter? = runCatching {
        val json = JSONObject(raw)
        MediaFilter(
            sizeMinBytes = if (json.has("sizeMinBytes")) json.getLong("sizeMinBytes") else null,
            sizeMaxBytes = if (json.has("sizeMaxBytes")) json.getLong("sizeMaxBytes") else null,
            widthMin = if (json.has("widthMin")) json.getInt("widthMin") else null,
            heightMin = if (json.has("heightMin")) json.getInt("heightMin") else null,
            mimeTypes = json.optJSONArray("mimeTypes")?.let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            } ?: emptySet(),
            dateFromMs = if (json.has("dateFromMs")) json.getLong("dateFromMs") else null,
            dateToMs = if (json.has("dateToMs")) json.getLong("dateToMs") else null,
            modifiedFromMs = if (json.has("modifiedFromMs")) json.getLong("modifiedFromMs") else null,
            modifiedToMs = if (json.has("modifiedToMs")) json.getLong("modifiedToMs") else null,
            favoritesOnly = json.optBoolean("favoritesOnly", false),
            folders = json.optJSONArray("folders")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            includeSubfolders = json.optBoolean("includeSubfolders", true),
            minBlurScore = if (json.has("minBlurScore")) json.getDouble("minBlurScore").toFloat() else null,
            maxBlurScore = if (json.has("maxBlurScore")) json.getDouble("maxBlurScore").toFloat() else null,
            nameLike = if (json.has("nameLike")) json.getString("nameLike") else null,
        )
    }.getOrNull()
}
