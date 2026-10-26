package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * tidyFiles：分類 tab 入面嘅值 chips（作者名／品質標記／其他 tag）。
 *
 * 第一粒係「全部」，之後每一粒係一個值同出現次數，撳一粒即刻篩書；
 * 再撳同一粒就取消。
 */
@Composable
internal fun LibraryTagChips(
    values: List<Pair<String, Int>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "__all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部") },
            )
        }
        items(values, key = { it.first }) { entry ->
            FilterChip(
                selected = selected == entry.first,
                onClick = { onSelect(if (selected == entry.first) null else entry.first) },
                label = { Text("${entry.first} (${entry.second})") },
            )
        }
    }
}
