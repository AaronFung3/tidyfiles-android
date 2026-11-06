package eu.kanade.tachiyomi.ui.reader.setting

import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 畫格：逐頁「同下一頁合併」嘅標記。
 *
 * 掃描本有時會將一張跨頁切成兩張圖，用家想淨係某幾頁合併返一齊睇。
 * 標記以 `"<mangaId>:<chapterId>:<pageIndex>"` 存喺 SharedPreferences 嘅 StringSet
 * （唔加 DB migration），重開 app 都仲記得；換書／換章節互不影響。
 */
object PageMergeStore {
    private val prefs: ReaderPreferences by lazy { Injekt.get() }

    private fun key(mangaId: Long, chapterId: Long, pageIndex: Int) = "$mangaId:$chapterId:$pageIndex"

    fun isMerged(mangaId: Long, chapterId: Long, pageIndex: Int): Boolean {
        if (mangaId <= 0L || chapterId <= 0L || pageIndex < 0) return false
        return key(mangaId, chapterId, pageIndex) in prefs.mergePages().get()
    }

    /** 切換標記，回傳切換之後嘅狀態（true = 已合併）。 */
    fun toggle(mangaId: Long, chapterId: Long, pageIndex: Int): Boolean {
        if (mangaId <= 0L || chapterId <= 0L || pageIndex < 0) return false
        val pref = prefs.mergePages()
        val current = pref.get()
        val k = key(mangaId, chapterId, pageIndex)
        val merged = k !in current
        pref.set(if (merged) current + k else current - k)
        return merged
    }

    /** 清走某一章嘅全部合併標記。 */
    fun clearChapter(mangaId: Long, chapterId: Long) {
        val pref = prefs.mergePages()
        val prefix = "$mangaId:$chapterId:"
        pref.set(pref.get().filterNot { it.startsWith(prefix) }.toSet())
    }
}
