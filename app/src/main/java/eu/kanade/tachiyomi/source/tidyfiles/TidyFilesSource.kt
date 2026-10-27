package eu.kanade.tachiyomi.source.tidyfiles

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * tidyFiles 來源：直接讀 PC 上嘅書庫（cleanup-app 嘅 HTTP API），手機零儲存。
 *
 * - 一本漫畫 = 一個 seriesKey（同系列自動合併）
 * - 一個章節 = 一個 cbz / 一個資料夾
 * - 圖片一律經 `/api/view/page` 串流，NPU 超分由 HttpPageLoader 嘅 ImageEnhancer 接手
 */
class TidyFilesSource(
    private val sourcePreferences: SourcePreferences,
) : HttpSource() {

    companion object {
        const val ID = 987654321000L
        const val NAME = "tidyFiles"

        private const val USER_AGENT = "tidyFiles-android/1"
        private const val DEFAULT_URL = "http://127.0.0.1:8765"

        // 書架快取有效期：書庫更新會逐本書問一次，唔快取就會每本書掃一次 PC 書庫
        private const val SHELF_TTL_MS = 5 * 60_000L
    }

    // PC 書架快照。圖書館更新逐本漫畫查詢，每本都打一次 /api/shelf 會令進度永遠停在 0%
    @Volatile private var shelfCache: List<ShelfItem>? = null

    @Volatile private var shelfFetchedAt: Long = 0L

    override val id: Long = ID
    override val name: String = NAME
    override val lang: String = "zh"
    override val supportsLatest: Boolean = false
    override val supportsRelatedMangas: Boolean = false

    override val baseUrl: String
        get() = sourcePreferences.tidyFilesUrl().get().trim().ifEmpty { DEFAULT_URL }.trimEnd('/')

    // 唔用 by lazy：用戶改完設定即刻生效（headers 每次重建，成本可忽略）
    override val headers: Headers
        get() = headersBuilder().build()

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().add("User-Agent", USER_AGENT)
        val token = token()
        if (token.isNotEmpty()) builder.add("X-Token", token)
        return builder
    }

    // ---- 書架 ----

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(groupBySeries(fetchShelf(force = true)), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val shelves = groupBySeries(fetchShelf(force = true))
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) return MangasPage(shelves, false)
        return MangasPage(shelves.filter { it.title.lowercase().contains(keyword) }, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val items = fetchShelf().filter { it.seriesKey == manga.url }
        val updatedManga = if ((fetchDetails || manga.title.isBlank()) && items.isNotEmpty()) {
            toSManga(manga.url, items)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            items.sortedBy { it.number }.map { toChapter(it) }
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rel = chapter.url
        if (rel.isEmpty()) return emptyList()
        val json = fetchJson("/api/view/pages", listOf("rel" to rel, "lib" to "1"))
        val count = json.optInt("n", 0)
        if (count <= 0) return emptyList()
        return List(count) { index -> Page(index, rel, pageUrl(rel, index)) }
    }

    // ---- HTTP ----

    private suspend fun fetchJson(path: String, params: List<Pair<String, String>>): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(apiUrl(path, params))
                .headers(headers)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("tidyFiles：$path 回應 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw Exception("tidyFiles：$path 回應空白")
                }
                JSONObject(body)
            }
        }

    /**
     * 讀 PC 書架。
     *
     * 一定要快取：書庫更新會逐本漫畫問一次（`UpdateMangaFromRemote` → `getMangaUpdate`），
     * 每本都重新掃一次 PC 書架（288 本要十幾秒）就會令「更新書櫃」進度永遠停喺 0%。
     * force = 用戶主動開來源／搜尋時，才真正重新掃描。
     */
    private suspend fun fetchShelf(force: Boolean = false): List<ShelfItem> {
        val cached = shelfCache
        if (!force && cached != null && System.currentTimeMillis() - shelfFetchedAt < SHELF_TTL_MS) {
            return cached
        }
        val json = fetchJson("/api/shelf", listOf("lib" to "1"))
        if (!json.optBoolean("ok", false)) {
            throw Exception("tidyFiles：書架讀取失敗")
        }
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<ShelfItem>(items.length())
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val rel = obj.optString("rel")
            if (rel.isEmpty()) continue
            out += ShelfItem(
                rel = rel,
                name = obj.optString("name").ifEmpty { rel },
                series = obj.optString("series").ifEmpty { obj.optString("name") }.ifEmpty { rel },
                seriesKey = obj.optString("series_key").ifEmpty { rel },
                number = obj.optString("number").toFloatOrNull() ?: -1f,
                author = obj.optString("author"),
                tags = obj.optString("tags")
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                pages = obj.optInt("pages", 0),
                coverN = obj.optInt("cover_n", 0),
                mtime = obj.optLong("mtime", 0L),
                addedAt = obj.optLong("added_at", 0L),
            )
        }
        shelfCache = out
        shelfFetchedAt = System.currentTimeMillis()
        // 每次真正讀到 PC 書架就順手同步落本地書庫（內部 30 秒節流，唔會拖慢）
        syncLibrary(out)
        return out
    }

    /** 把 PC 書架寫入本地書庫，並將 tag 變成分類 tab。 */
    private suspend fun syncLibrary(items: List<ShelfItem>) {
        if (items.isEmpty()) return
        val series = items.groupBy { it.seriesKey }.map { (seriesKey, list) ->
            val first = list.minByOrNull { if (it.number < 0f) Float.MAX_VALUE else it.number } ?: list.first()
            val cover = list.maxByOrNull { it.coverN } ?: first
            TidyFilesSeries(
                key = seriesKey,
                title = first.series,
                author = first.author.ifEmpty { null },
                tags = list.flatMap { it.tags }.distinct(),
                description = buildDescription(first, list),
                coverUrl = pageUrl(cover.rel, cover.coverN.coerceAtLeast(0)),
                // tidyFiles --> 用「母本落地時間」做加入日期：ComicInfo TidyAddedAt
                // （入庫時由 dump 母本寫入，係真正嘅下載時間）；舊書冇呢個欄
                // 就退返 CBZ 檔案日期。（秒→毫秒）
                dateAdded = list.maxOfOrNull {
                    if (it.addedAt > 0L) it.addedAt else it.mtime
                }?.takeIf { it > 0L }?.times(1000L) ?: 0L,
            )
        }
        TidyFilesSync.sync(series, id)
    }

    private fun apiUrl(path: String, params: List<Pair<String, String>>): String {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw Exception("tidyFiles：伺服器地址無效（$baseUrl）")
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val token = token()
        if (token.isNotEmpty()) builder.addQueryParameter("k", token)
        return builder.build().toString()
    }

    private fun pageUrl(rel: String, index: Int): String = apiUrl(
        "/api/view/page",
        listOf(
            "rel" to rel,
            "n" to index.toString(),
            "hd" to "0",
            "lib" to "1",
        ),
    )

    private fun token(): String = sourcePreferences.tidyFilesToken().get().trim()

    // ---- 轉換 ----

    private fun groupBySeries(items: List<ShelfItem>): List<SManga> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<ShelfItem>>()
        items.forEach { item ->
            grouped.getOrPut(item.seriesKey) { mutableListOf() }.add(item)
        }
        return grouped.map { (seriesKey, list) -> toSManga(seriesKey, list) }
    }

    private fun toSManga(seriesKey: String, items: List<ShelfItem>): SManga {
        val first = items.minByOrNull { if (it.number < 0f) Float.MAX_VALUE else it.number } ?: items.first()
        val cover = items.maxByOrNull { it.coverN } ?: first
        return SManga.create().apply {
            url = seriesKey
            title = first.series
            author = first.author.ifEmpty { null }
            artist = first.author.ifEmpty { null }
            genre = first.tags.joinToString(", ").ifEmpty { null }
            status = SManga.UNKNOWN
            description = buildDescription(first, items)
            thumbnail_url = pageUrl(cover.rel, cover.coverN.coerceAtLeast(0))
            initialized = true
        }
    }

    private fun toChapter(item: ShelfItem): SChapter = SChapter.create().apply {
        url = item.rel
        name = item.name
        chapter_number = if (item.number < 0f) -1f else item.number
        scanlator = null
    }

    private fun buildDescription(first: ShelfItem, items: List<ShelfItem>): String = buildString {
        appendLine(first.series)
        appendLine()
        appendLine("章節：${items.size}")
        if (first.author.isNotEmpty()) appendLine("作者：${first.author}")
        if (first.tags.isNotEmpty()) appendLine("Tag：${first.tags.joinToString("、")}")
        append("（PC 書庫串流，手機不佔空間）")
    }

    private data class ShelfItem(
        val rel: String,
        val name: String,
        val series: String,
        val seriesKey: String,
        val number: Float,
        val author: String,
        val tags: List<String>,
        val pages: Int,
        val coverN: Int,
        val mtime: Long,
        val addedAt: Long,
    )
}
