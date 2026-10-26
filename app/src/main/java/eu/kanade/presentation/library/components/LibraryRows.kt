package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem

/**
 * tidyFiles：書架「一堆堆」分組。
 *
 * 有 tag（ComicInfo 嘅 Genre／Tags，來源已經放落 manga.genre）就按 tag 分堆，
 * 一本書有幾個 tag 就會出現喺幾堆；冇 tag 就按書名開頭分堆（0-9／A-Z／其他）。
 */
internal sealed interface LibraryRow {
    data class Header(val name: String, val count: Int) : LibraryRow
    data class Manga(val item: LibraryItem) : LibraryRow
}

private const val OTHER_GROUP = "其他"
private const val DIGIT_GROUP = "0-9"

internal fun buildLibraryRows(items: List<LibraryItem>): List<LibraryRow> {
    if (items.size < 2) return emptyList()
    val hasTags = items.any { !it.libraryManga.manga.genre.isNullOrEmpty() }
    // 冇 tag 而書名全部係雜湊字串（PC 書庫未打 tag 時）→ 分組冇意義，
    // 唔好出一個「0-9 全部」嘅假標題。
    if (!hasTags && items.all { looksLikeHash(it.libraryManga.manga.title) }) return emptyList()

    val grouped: List<Pair<String, List<LibraryItem>>> = if (hasTags) {
        val map = LinkedHashMap<String, MutableList<LibraryItem>>()
        items.forEach { item ->
            val tags = item.libraryManga.manga.genre.orEmpty().filter { it.isNotBlank() }
            if (tags.isEmpty()) {
                map.getOrPut(OTHER_GROUP) { mutableListOf() }.add(item)
            } else {
                tags.forEach { tag -> map.getOrPut(tag) { mutableListOf() }.add(item) }
            }
        }
        map.entries
            .sortedWith(compareBy({ it.key == OTHER_GROUP }, { it.key }))
            .map { it.key to it.value.toList() }
    } else {
        val map = LinkedHashMap<String, MutableList<LibraryItem>>()
        items.forEach { item ->
            map.getOrPut(bucketOf(item.libraryManga.manga.title)) { mutableListOf() }.add(item)
        }
        map.entries
            .sortedBy { bucketOrder(it.key) }
            .map { it.key to it.value.toList() }
    }

    // 得一組嘅話標題只係噪音（睇落同冇分組一樣），索性唔分
    if (grouped.size <= 1) return emptyList()

    return grouped.flatMap { (name, list) ->
        buildList<LibraryRow> {
            add(LibraryRow.Header(name, list.size))
            list.forEach { add(LibraryRow.Manga(it)) }
        }
    }
}

/** 書名似 PC 書庫嘅雜湊資料夾名（例如 003d46cb1a…）就當冇可讀標題。 */
private fun looksLikeHash(title: String): Boolean {
    val text = title.trim()
    return text.length >= 8 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private fun bucketOf(title: String): String {
    val first = title.trim().firstOrNull() ?: return OTHER_GROUP
    return when {
        first.isDigit() -> DIGIT_GROUP
        first in 'A'..'Z' -> first.toString()
        first in 'a'..'z' -> first.uppercaseChar().toString()
        else -> OTHER_GROUP
    }
}

private fun bucketOrder(name: String): String = when (name) {
    DIGIT_GROUP -> "\u0000"
    OTHER_GROUP -> "\uFFFF"
    else -> name
}

/** 一個組別嘅標題行（橫跨成個 grid）。 */
@Composable
internal fun LibraryGroupHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "  $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
