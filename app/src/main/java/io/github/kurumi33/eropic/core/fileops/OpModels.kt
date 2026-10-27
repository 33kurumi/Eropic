package io.github.kurumi33.eropic.core.fileops

import org.json.JSONArray
import org.json.JSONObject

/** 单条失败原因：结果报告页（C-07）要逐条说明"哪张、为什么失败"，而不是只给个数字。 */
data class OpFailure(val name: String, val reason: String)

/** 一次写操作的逐项记录，用于撤销（C-08）与报告页（C-07）。 */
data class OpItem(
    /** MOVE/RENAME/COPY：mediaId；TRASH：trashId（撤销时按回收站 id 恢复）。 */
    val id: Long,
    val name: String,
    /** 操作前的绝对路径；TRASH 是原路径；COPY 是源路径。 */
    val from: String? = null,
    /** 操作后的绝对路径；TRASH 是回收站里的路径；COPY 是新文件的路径。 */
    val to: String? = null,
)

/**
 * operation_log.payloadJson 的编解码。
 *
 * v1 只存了 id 数组（M3 的 TRASH/MOVE），无法逐项撤销；v2 存 from/to 才能把文件搬回去。
 * [decode] 兼容 v1：碰到 JSONArray 就当"只有 id 的老记录"，撤销时给出明确提示而不是崩。
 */
object OperationPayload {

    const val VERSION = 2

    fun encode(items: List<OpItem>): String {
        val array = JSONArray()
        for (item in items) {
            val json = JSONObject()
            json.put("id", item.id)
            json.put("name", item.name)
            item.from?.let { json.put("from", it) }
            item.to?.let { json.put("to", it) }
            array.put(json)
        }
        return JSONObject().put("v", VERSION).put("items", array).toString()
    }

    fun decode(raw: String?): List<OpItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                // M3 的旧格式：纯 id 数组
                val array = JSONArray(trimmed)
                (0 until array.length()).map { index ->
                    OpItem(id = array.optLong(index), name = "")
                }
            } else {
                val json = JSONObject(trimmed)
                val array = json.optJSONArray("items") ?: JSONArray()
                (0 until array.length()).map { index ->
                    val item = array.optJSONObject(index) ?: JSONObject()
                    OpItem(
                        id = item.optLong("id"),
                        name = item.optString("name"),
                        from = item.optString("from").takeIf { it.isNotBlank() },
                        to = item.optString("to").takeIf { it.isNotBlank() },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 老记录（只有 id）无法逐项搬回，用它判断是否需要提示"这条记录太旧，无法撤销"。 */
    fun isLegacy(raw: String?): Boolean = raw?.trim()?.startsWith("[") == true
}
