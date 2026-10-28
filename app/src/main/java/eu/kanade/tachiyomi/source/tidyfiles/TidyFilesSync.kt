package eu.kanade.tachiyomi.source.tidyfiles

import android.util.Log
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** 一本 PC 書庫系列（同一 series_key 嘅所有章節）。 */
data class TidyFilesSeries(
    val key: String,
    val title: String,
    val author: String?,
    val tags: List<String>,
    val description: String,
    val coverUrl: String?,
    /** PC 檔案日期（epoch 毫秒，0 = 冇）→ 做「加入書櫃日期」。 */
    val dateAdded: Long = 0L,
    /** PC 端書簽（reader.json）：反向同步「已讀／睇到邊頁」用。 */
    val progress: List<TidyFilesChapterProgress> = emptyList(),
)

/** 一卷嘅 PC 端書簽：`rel` 係章節 url（同本地章節一對一）。 */
data class TidyFilesChapterProgress(
    val rel: String,
    val finished: Boolean,
    val progressPage: Int,
)

/**
 * tidyFiles 書庫同步：把 PC 書庫嘅系列寫入本地書庫，並把 tag 變成分類。
 *
 * - PC 係唯一真相來源：每次同步都會重設每本書嘅分類（跟 PC 上嘅 tag）
 * - 已經喺書庫嘅書唔會重複插入，亦唔會改你手動改過嘅資料
 * - 讀書狀態雙向：PC 睇過／睇到一半嘅卷會同步落本地章節
 *   （只會前進唔會後退：唔會清走本地已讀，唔會縮細 last_page_read）
 * - 失敗只記 log，唔會阻住書架／瀏覽
 */
object TidyFilesSync {

    private const val TAG = "TidyFilesSync"

    /** 兩次同步之間最短間隔，避免每次開瀏覽都掃一次。 */
    private const val MIN_INTERVAL_MS = 30_000L

    private val mutex = Mutex()
    private var lastSyncAt = 0L

    suspend fun sync(series: List<TidyFilesSeries>, sourceId: Long) {
        if (series.isEmpty()) return
        val now = System.currentTimeMillis()
        mutex.withLock {
            if (now - lastSyncAt < MIN_INTERVAL_MS) return@withLock
            lastSyncAt = now
            withContext(Dispatchers.IO) {
                try {
                    runSync(series, sourceId)
                } catch (e: Exception) {
                    Log.e(TAG, "書庫同步失敗", e)
                }
            }
        }
    }

    private suspend fun runSync(series: List<TidyFilesSeries>, sourceId: Long) {
        val mangaRepository = Injekt.get<MangaRepository>()
        val categoryRepository = Injekt.get<CategoryRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()

        val categoryByName = categoryRepository.getAll()
            .associateBy { it.name.lowercase() }
            .toMutableMap()
        var nextOrder = (categoryByName.values.maxOfOrNull { it.order } ?: 0L) + 1L
        val timestamp = System.currentTimeMillis()

        var categorised = 0
        var progressSynced = 0
        val newlyAdded = mutableListOf<Manga>()

        series.forEach { item ->
            val existing = mangaRepository.getMangaByUrlAndSourceId(item.key, sourceId)
            val manga = if (existing != null) {
                existing
            } else {
                mangaRepository.insertNetworkManga(
                    listOf(item.toManga(sourceId, timestamp)),
                    updateInfo = false,
                ).firstOrNull()?.also { newlyAdded += it }
            }
            if (manga == null || manga.id <= 0L) return@forEach

            // tidyFiles --> 用 PC 檔案日期做「加入書櫃日期」，已存在嘅書都會校正
            if (existing != null && item.dateAdded > 0L && item.dateAdded != manga.dateAdded) {
                mangaRepository.update(MangaUpdate(id = manga.id, dateAdded = item.dateAdded))
            }
            // tidyFiles <--

            // tidyFiles --> 反向同步讀書狀態：PC 睇過嘅，手機書櫃都要知。
            // 規矩：只會「前進」唔會「後退」——
            //   · PC 話 finished → 標記已讀（唔會反過來清走本地已讀）
            //   · PC 睇到第 N 頁 → 本地 last_page_read 只有喺 N 更大先更新
            //     （手機睇得更遠、或者未 sync 返 PC 嗰陣，唔會被舊資料蓋走）
            if (item.progress.isNotEmpty()) {
                val pcByRel = item.progress.associateBy { it.rel }
                val updates = chapterRepository.getChapterByMangaId(manga.id)
                    .mapNotNull { ch ->
                        val pc = pcByRel[ch.url] ?: return@mapNotNull null
                        when {
                            pc.finished && !ch.read ->
                                ChapterUpdate(id = ch.id, read = true)
                            !ch.read && pc.progressPage > 0 && ch.lastPageRead < pc.progressPage ->
                                ChapterUpdate(id = ch.id, lastPageRead = pc.progressPage.toLong())
                            else -> null
                        }
                    }
                if (updates.isNotEmpty()) {
                    chapterRepository.updateAll(updates)
                    progressSynced += updates.size
                }
            }
            // tidyFiles <--

            // tidyFiles：tag 唔再逐粒開分類，改成「作者 / 品質 / 其他」三個分類 tab，
            // 值（作者名、品質標記）交返手機端用 chips 篩。
            val kinds = item.tags.mapNotNull { TidyFilesTagKind.kindOf(it) }.distinct()
            if (kinds.isEmpty()) return@forEach

            val categoryIds = kinds.mapNotNull { kind ->
                categoryByName[kind.lowercase()]?.id ?: run {
                    val newId = categoryRepository.insert(
                        Category(
                            id = 0L,
                            name = kind,
                            order = nextOrder,
                            flags = 0L,
                            hidden = false,
                        ),
                    )
                    nextOrder += 1L
                    if (newId > 0L) {
                        categoryRepository.get(newId)?.let { categoryByName[kind.lowercase()] = it }
                    }
                    newId.takeIf { it > 0L }
                }
            }

            if (categoryIds.isNotEmpty()) {
                mangaRepository.setMangaCategories(manga.id, categoryIds)
                categorised++
            }
        }

        // 新入庫嘅書即刻拉章節，否則書櫃會顯示「0 章」
        if (newlyAdded.isNotEmpty()) {
            val updater = Injekt.get<UpdateMangaFromRemote>()
            newlyAdded.forEach { manga ->
                updater(manga, fetchDetails = true, fetchChapters = true)
            }
        }

        if (newlyAdded.isNotEmpty() || categorised > 0 || progressSynced > 0) {
            Log.i(
                TAG,
                "同步完成：新增 ${newlyAdded.size} 本，重設分類 $categorised 本，" +
                    "讀書狀態跟 PC 更新 $progressSynced 卷",
            )
        }
    }

    private fun TidyFilesSeries.toManga(sourceId: Long, timestamp: Long): Manga = Manga.create().copy(
        source = sourceId,
        url = key,
        ogTitle = title,
        ogArtist = author,
        ogAuthor = author,
        ogThumbnailUrl = coverUrl,
        ogDescription = description,
        ogGenre = tags.ifEmpty { null },
        ogStatus = 0L,
        favorite = true,
        dateAdded = dateAdded.takeIf { it > 0L } ?: timestamp,
        lastUpdate = timestamp,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
    )
}
