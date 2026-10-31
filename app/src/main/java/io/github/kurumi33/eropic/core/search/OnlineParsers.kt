package io.github.kurumi33.eropic.core.search

/**
 * 联网识图的结果解析器（纯函数，零 Android 依赖，可在纯 JVM 单测里直接跑）。
 *
 * 为什么单独抽一层：
 * - 各家以图搜图都没有对个人开发者开放的官方 API，返回的是 HTML 或半结构化 JSON，
 *   解析逻辑混在联网代码里，一端页面改版就只能靠真机猜；
 * - 抽成「文本 -> 结构化命中」的纯函数后，页面结构变化可以拿固定样例离线复现、离线回归；
 * - UI 只认识 [ParsedHit] 的五个字段，不需要知道 ascii2d 的 div 结构或 IQDB 的表格结构。
 *
 * 约定：输入 null / 空串 / 结构不符时一律返回安全默认值，内部 runCatching 兜底，绝不抛异常；
 * 不发网络请求、不碰文件；相对链接统一补成绝对地址，调用方可以直接交给系统浏览器打开。
 */
data class ParsedHit(
    /** 标题（作品名或来源站名）；解析不到时为 null。 */
    val title: String? = null,
    /** 相似度百分比 0..100；服务端不给或不是数字时为 null。 */
    val similarity: Double? = null,
    /** 指向来源站点作品页的外链。 */
    val extUrl: String? = null,
    /**
     * 该命中在搜索服务自身的结果页。
     * 拿不到独立页面时保持 null，避免 UI 上出现两个指向同一地址的按钮。
     */
    val pageUrl: String? = null,
    /** 缩略图直链。 */
    val thumbnailUrl: String? = null,
)

/**
 * SauceNAO output_type=2 的响应。
 *
 * [headerStatus] 原样返回服务端的 header.status，不在解析层做归一化：
 * 语义为 0 成功 / 1 无结果 / 2 限流 / 3 API Key 无效 / 4 请求格式错 / 5 服务端错误。
 * 线上接口也出现过负数（-1 key 无效、-2 限流、-3 搜索错误），调用方按自己的映射表解释即可。
 */
data class SauceNaoResponse(
    val headerStatus: Int,
    /** header.message（服务端给的补充说明）；没有时为 null。 */
    val headerMessage: String? = null,
    val results: List<ParsedHit> = emptyList(),
)

object OnlineParsers {

    private const val GOOGLE_BASE = "https://www.google.com"
    private const val ASCII2D_BASE = "https://ascii2d.net"
    private const val IQDB_BASE = "https://iqdb.org"

    /**
     * 解析 SauceNAO 的 JSON 响应。
     *
     * 只取「列表页用得上」的字段：标题、相似度、原站链接、缩略图。
     * header_status 缺失时按 0（成功）处理，避免调用方因为缺字段就整页报错。
     */
    fun sauceNao(json: String?): SauceNaoResponse {
        val text = json?.trim().orEmpty()
        if (text.isEmpty()) return SauceNaoResponse(headerStatus = 0)
        return runCatching { parseSauceNao(text) }.getOrElse { SauceNaoResponse(headerStatus = 0) }
    }

    /**
     * 解析 ascii2d 的 /search/color 结果页。
     *
     * 页面结构（2026-10 观察到的公开页面）：
     * div.item-box 是一个条目，内部 div.detail-box 里 h6 > a 是标题链接，
     * detail-box 里其余 a[href] 是来源站外链；缩略图在 item-box 的 img 上。
     * [pageBaseUrl] 用于把 /search/file?uri=... 这类相对链接补成绝对地址。
     */
    fun ascii2dHtml(html: String?, pageBaseUrl: String? = null): List<ParsedHit> {
        val text = html?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val base = pageBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: ASCII2D_BASE
        return runCatching { parseAscii2d(text, base) }.getOrElse { emptyList() }
    }

    /**
     * 解析 IQDB 的结果页。
     *
     * 页面结构：每行一个 tr，td.match 里是「93%」这样的相似度文本，
     * td.url 里的 a[href] 是结果链接，td.service 是来源站名（Danbooru / Yande.re 等）。
     */
    fun iqdbHtml(html: String?, pageBaseUrl: String? = null): List<ParsedHit> {
        val text = html?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val base = pageBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: IQDB_BASE
        return runCatching { parseIqdb(text, base) }.getOrElse { emptyList() }
    }

    /**
     * 从 Google Lens 的结果页 HTML 里找「图搜结果」的链接。
     *
     * 优先级：href 带 udm=37 / udm=44 / udm=48 的链接（Lens 的图搜结果 tab）
     * -> link rel=canonical -> 调用方给的 [baseUrl]。
     * 相对地址按 https://www.google.com 补全。
     */
    fun googleLensResultUrl(html: String?, baseUrl: String? = null): String? {
        val fallback = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        val text = html?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        return runCatching { findGoogleLensResultUrl(text, fallback) }.getOrElse { fallback }
    }

    // ---------- SauceNAO ----------

    private fun parseSauceNao(json: String): SauceNaoResponse {
        val root = JsonScan.parse(json)
        val header = JsonScan.field(root, "header")
        val status = JsonScan.asInt(JsonScan.field(header, "status"))
            ?: JsonScan.asInt(JsonScan.field(root, "status"))
            ?: 0
        val message = JsonScan.asString(JsonScan.field(header, "message"))
        val results = JsonScan.asArray(JsonScan.field(root, "results"))
            .mapNotNull { toSauceNaoHit(it) }
        return SauceNaoResponse(headerStatus = status, headerMessage = message, results = results)
    }

    private fun toSauceNaoHit(node: JsonValue): ParsedHit? {
        val item = node as? JsonValue.Obj ?: return null
        val header = JsonScan.field(item, "header")
        val data = JsonScan.field(item, "data")
        val title = firstNonBlank(
            JsonScan.asString(JsonScan.field(data, "title")),
            JsonScan.asString(JsonScan.field(data, "source")),
            JsonScan.asString(JsonScan.field(data, "material")),
            JsonScan.asString(JsonScan.field(data, "characters")),
            JsonScan.asString(JsonScan.field(item, "title")),
        )
        val similarity = JsonScan.asDouble(JsonScan.field(header, "similarity"))
            ?: JsonScan.asDouble(JsonScan.field(item, "similarity"))
        val thumbnailUrl = JsonScan.asString(JsonScan.field(header, "thumbnail"))
            ?: JsonScan.asString(JsonScan.field(data, "thumbnail"))
        val extUrl = JsonScan.asArray(JsonScan.field(data, "ext_urls"))
            .mapNotNull { JsonScan.asString(it) }
            .firstOrNull { it.isAbsoluteHttpUrl() }
            ?.let { HtmlScan.decodeEntities(it) }
        val hit = ParsedHit(
            title = title,
            similarity = similarity,
            extUrl = extUrl,
            thumbnailUrl = thumbnailUrl,
        )
        return if (hit.hasAnyField()) hit else null
    }

    // ---------- ascii2d ----------

    private fun parseAscii2d(html: String, baseUrl: String): List<ParsedHit> {
        val regions = HtmlScan.regionsWithClassToken(html, "item-box")
        if (regions.isEmpty()) return emptyList()
        val hits = ArrayList<ParsedHit>(regions.size)
        for (region in regions) {
            val detail = HtmlScan.subregionWithClassToken(region, "detail-box")
            val title = HtmlScan.firstAnchorTextInH6(detail)
            val detailHrefs = HtmlScan.hrefs(detail)
            // 外链优先排除 ascii2d 自己的页面，否则「外链」会变成本站的详情页
            val extUrl = detailHrefs.firstOrNull { it.isAbsoluteHttpUrl() && !HtmlScan.isAscii2dHost(it) }
                ?: detailHrefs.firstOrNull { it.isAbsoluteHttpUrl() }
            val itemHrefs = HtmlScan.hrefs(region)
            val selfHref = itemHrefs.firstOrNull { it.startsWith("/") }
                ?: itemHrefs.firstOrNull { HtmlScan.isAscii2dHost(it) }
            val hit = ParsedHit(
                title = title,
                extUrl = extUrl,
                pageUrl = HtmlScan.resolveUrl(baseUrl, selfHref),
                thumbnailUrl = HtmlScan.resolveUrl(baseUrl, HtmlScan.firstImgSrc(region)),
            )
            if (hit.hasAnyField()) hits.add(hit)
        }
        return hits
    }

    // ---------- IQDB ----------

    private fun parseIqdb(html: String, baseUrl: String): List<ParsedHit> {
        val rows = HtmlScan.tableRows(html)
        if (rows.isEmpty()) return emptyList()
        val hits = ArrayList<ParsedHit>(rows.size)
        for (row in rows) {
            val cells = HtmlScan.tableCells(row)
            val matchCell = cells.firstOrNull { HtmlScan.hasClass(it.attributes, "match") }?.body
            val urlCell = cells.firstOrNull { HtmlScan.hasClass(it.attributes, "url") }?.body
            val serviceCell = cells.firstOrNull { HtmlScan.hasClass(it.attributes, "service") }?.body
            // 表头等没有任何相关 class 的行直接跳过
            if (matchCell == null && urlCell == null && serviceCell == null) continue
            val similarity = matchCell?.let { HtmlScan.percent(it) }
            val extUrl = HtmlScan.resolveUrl(
                baseUrl,
                urlCell?.let { HtmlScan.hrefs(it).firstOrNull() },
            )
            val title = serviceCell?.let { HtmlScan.textOnly(it).takeIf { name -> name.isNotEmpty() } }
            val hit = ParsedHit(
                title = title,
                similarity = similarity,
                extUrl = extUrl,
                thumbnailUrl = HtmlScan.resolveUrl(baseUrl, HtmlScan.firstImgSrc(row)),
            )
            if (hit.hasAnyField()) hits.add(hit)
        }
        return hits
    }

    // ---------- Google Lens ----------

    private fun findGoogleLensResultUrl(html: String, fallback: String?): String? {
        // 有些页面把 / 转义成 \/，先还原再找链接
        val text = html.replace("\\/", "/")
        val hrefs = HtmlScan.hrefs(text)
        val udmHref = hrefs.firstOrNull { href ->
            href.contains("udm=37") || href.contains("udm=44") || href.contains("udm=48")
        }
        if (udmHref != null) return HtmlScan.resolveUrl(GOOGLE_BASE, udmHref)
        val canonicalHref = HtmlScan.canonicalHref(text)
        if (canonicalHref != null) return HtmlScan.resolveUrl(fallback ?: GOOGLE_BASE, canonicalHref)
        return fallback
    }
}

/** 一条命中是否至少有一个可用字段；全空说明这一块不是结果（页头、广告位等）。 */
private fun ParsedHit.hasAnyField(): Boolean =
    !title.isNullOrBlank() || similarity != null || !extUrl.isNullOrBlank() ||
        !pageUrl.isNullOrBlank() || !thumbnailUrl.isNullOrBlank()

/** 取第一个非空白字符串（去掉首尾空白）；全空时返回 null。 */
private fun firstNonBlank(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim()
        if (!trimmed.isNullOrEmpty()) return trimmed
    }
    return null
}

private fun String.isAbsoluteHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/**
 * HTML 的最小化扫描工具。
 *
 * 为什么不用 Jsoup：项目约定不新增第三方依赖，而这里只需要「按 class 找块 + 取 href/文本」，
 * 正则 + 下标切片足够，还能在纯 JVM 单测里跑（Jsoup 会引入新的依赖与体积）。
 * 只处理结构规整的服务端返回页面，不追求浏览器级别的容错。
 */
private object HtmlScan {

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)
    private val DOT_ALL = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val CLASS_ATTRIBUTE = Regex("class\\s*=\\s*[\"']([^\"']*)[\"']", IGNORE_CASE)
    private val HREF_ATTRIBUTE = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", IGNORE_CASE)
    private val SRC_ATTRIBUTE = Regex("src\\s*=\\s*[\"']([^\"']*)[\"']", IGNORE_CASE)
    private val IMG_TAG = Regex("<img\\b[^>]*>", IGNORE_CASE)
    private val H6_BLOCK = Regex("<h6\\b[^>]*>(.*?)</h6>", DOT_ALL)
    private val ANCHOR_BLOCK = Regex("<a\\b[^>]*>(.*?)</a>", DOT_ALL)
    private val TABLE_ROW = Regex("<tr\\b[^>]*>(.*?)</tr>", DOT_ALL)
    private val TABLE_CELL = Regex("<td\\b([^>]*)>(.*?)</td>", DOT_ALL)
    private val CANONICAL_LINK = Regex("<link\\b[^>]*rel\\s*=\\s*[\"']?canonical[\"']?[^>]*>", IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val PERCENT = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*%")

    /** 找出所有 class 含 [token] 的标签起始位置，并把 HTML 切成若干区域（到下一个同类标签为止）。 */
    fun regionsWithClassToken(html: String, token: String): List<String> {
        val starts = ArrayList<Int>()
        for (match in CLASS_ATTRIBUTE.findAll(html)) {
            if (hasTokenInClassValue(match.groupValues[1], token)) starts.add(match.range.first)
        }
        if (starts.isEmpty()) return emptyList()
        val regions = ArrayList<String>(starts.size)
        for (index in starts.indices) {
            val end = if (index + 1 < starts.size) starts[index + 1] else html.length
            regions.add(html.substring(starts[index], end))
        }
        return regions
    }

    /** 区域里第一处 class 含 [token] 的标签之后的部分；找不到就返回整个区域。 */
    fun subregionWithClassToken(region: String, token: String): String {
        val match = CLASS_ATTRIBUTE.findAll(region)
            .firstOrNull { hasTokenInClassValue(it.groupValues[1], token) }
            ?: return region
        return region.substring(match.range.first)
    }

    /** [attributes] 是标签属性片段（如 class="match"），判断 class 列表里是否有 [token]。 */
    fun hasClass(attributes: String, token: String): Boolean {
        val match = CLASS_ATTRIBUTE.find(attributes) ?: return false
        return hasTokenInClassValue(match.groupValues[1], token)
    }

    /** [classValue] 是已经取出来的 class 属性值（如 "item-box"），直接按空白拆成多个类名比较。 */
    private fun hasTokenInClassValue(classValue: String, token: String): Boolean =
        classValue.split(WHITESPACE).any { it.equals(token, ignoreCase = true) }

    fun hrefs(html: String): List<String> =
        HREF_ATTRIBUTE.findAll(html).map { decodeEntities(it.groupValues[1]) }.toList()

    fun firstImgSrc(html: String): String? {
        for (img in IMG_TAG.findAll(html)) {
            val src = SRC_ATTRIBUTE.find(img.value)?.groupValues?.get(1)
            if (!src.isNullOrBlank()) return decodeEntities(src)
        }
        return null
    }

    /** detail-box 里 h6 > a 的文本；找不到 h6 时退回整个区域里第一个 a 的文本。 */
    fun firstAnchorTextInH6(html: String): String? {
        for (block in H6_BLOCK.findAll(html)) {
            val inner = block.groupValues[1]
            val anchor = ANCHOR_BLOCK.find(inner)
            val text = textOnly(anchor?.groupValues?.get(1) ?: inner)
            if (text.isNotEmpty()) return text
        }
        val firstAnchor = ANCHOR_BLOCK.find(html)?.groupValues?.get(1) ?: return null
        return textOnly(firstAnchor).takeIf { it.isNotEmpty() }
    }

    fun textOnly(html: String): String {
        val withoutTags = TAG.replace(html, " ")
        return WHITESPACE.replace(decodeEntities(withoutTags), " ").trim()
    }

    fun percent(html: String): Double? =
        PERCENT.find(textOnly(html))?.groupValues?.get(1)?.toDoubleOrNull()

    fun tableRows(html: String): List<String> = TABLE_ROW.findAll(html).map { it.groupValues[1] }.toList()

    fun tableCells(row: String): List<HtmlCell> = TABLE_CELL.findAll(row)
        .map { HtmlCell(attributes = it.groupValues[1], body = it.groupValues[2]) }
        .toList()

    fun canonicalHref(html: String): String? {
        val tag = CANONICAL_LINK.find(html)?.value ?: return null
        return hrefs(tag).firstOrNull()
    }

    /** 把 href 变成绝对地址：已是绝对地址直接用；协议相对补 https；相对地址按 [baseUrl] 拼。 */
    fun resolveUrl(baseUrl: String?, href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val url = decodeEntities(raw).replace("\\/", "/")
        if (url.isAbsoluteHttpUrl()) return url
        if (url.startsWith("//")) return "https:" + url
        val lower = url.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") ||
            lower.startsWith("data:") || lower.startsWith("about:")
        ) {
            return null
        }
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (url.startsWith("/")) base.trimEnd('/') + url else base.trimEnd('/') + "/" + url
    }

    fun isAscii2dHost(url: String): Boolean = url.contains("ascii2d.net", ignoreCase = true)

    /** 解码常见的 HTML 实体；未知实体原样保留（宁可见到 &foo; 也不要丢字符）。 */
    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c != '&') {
                out.append(c)
                index++
                continue
            }
            val semi = raw.indexOf(';', index + 1)
            if (semi < 0 || semi - index > 10) {
                out.append(c)
                index++
                continue
            }
            val entity = raw.substring(index + 1, semi)
            val decoded = when (entity.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> if (entity.startsWith("#")) decodeNumericEntity(entity) else null
            }
            if (decoded == null) {
                out.append(c)
                index++
            } else {
                out.append(decoded)
                index = semi + 1
            }
        }
        return out.toString()
    }

    private fun decodeNumericEntity(entity: String): String? {
        val body = entity.substring(1)
        val isHex = body.startsWith("x", ignoreCase = true)
        val digits = if (isHex) body.substring(1) else body
        val code = digits.toIntOrNull(if (isHex) 16 else 10) ?: return null
        if (code <= 0 || code > 0x10FFFF) return null
        return String(Character.toChars(code))
    }
}

private data class HtmlCell(val attributes: String, val body: String)

/**
 * 手写的最小 JSON 读取器。
 *
 * 不用 org.json 的原因：它来自 android.jar，在纯 JVM 单测里只有桩实现（调用即抛 Stub!），
 * 除非引入 Robolectric —— 而这一层本来就应该是「纯字符串处理」，值得自己实现。
 */
private object JsonScan {

    fun parse(text: String): JsonValue = JsonReader(text).readDocument()

    fun field(node: JsonValue?, key: String): JsonValue? = (node as? JsonValue.Obj)?.fields?.get(key)

    fun asString(node: JsonValue?): String? = when (node) {
        is JsonValue.Str -> node.value
        else -> null
    }

    fun asDouble(node: JsonValue?): Double? = when (node) {
        is JsonValue.Num -> node.value
        is JsonValue.Str -> node.value.trim().removeSuffix("%").trim().toDoubleOrNull()
        else -> null
    }

    fun asInt(node: JsonValue?): Int? = when (node) {
        is JsonValue.Num -> node.value.toInt()
        is JsonValue.Str -> node.value.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    fun asArray(node: JsonValue?): List<JsonValue> = (node as? JsonValue.Arr)?.items.orEmpty()
}

private sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    object Null : JsonValue
}

private class JsonReader(private val source: String) {

    private var index = 0

    fun readDocument(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        return value
    }

    private fun readValue(): JsonValue {
        skipWhitespace()
        if (index >= source.length) throw IllegalArgumentException("JSON 提前结束")
        return when (val c = source[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("非法字符")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            fields[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> throw IllegalArgumentException("对象缺少分隔符")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> throw IllegalArgumentException("数组缺少分隔符")
            }
        }
    }

    private fun readString(): String {
        skipWhitespace()
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= source.length) throw IllegalArgumentException("字符串未闭合")
            val c = source[index++]
            when (c) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (index >= source.length) throw IllegalArgumentException("转义不完整")
                    when (val escaped = source[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > source.length) throw IllegalArgumentException("unicode 转义不完整")
                            val code = source.substring(index, index + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("非法 unicode 转义")
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("未知转义")
                    }
                }
                else -> builder.append(c)
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        while (index < source.length) {
            val c = source[index]
            val isNumberChar = c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
            if (!isNumberChar) break
            index++
        }
        val raw = source.substring(start, index)
        val value = raw.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字")
        return JsonValue.Num(value)
    }

    private fun readLiteral(word: String, value: JsonValue): JsonValue {
        if (!source.startsWith(word, index)) throw IllegalArgumentException("非法字面量")
        index += word.length
        return value
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (peek() != expected) throw IllegalArgumentException("缺少字符")
        index++
    }

    private fun peek(): Char {
        if (index >= source.length) throw IllegalArgumentException("JSON 提前结束")
        return source[index]
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}
