package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

/**
 * tidyFiles：tag 面板 —— 全部 tag（作者／品質／其他三堆）攤開做一版。
 * 用 Popup 浮喺書架上面，唔會推郁個 grid；撳一粒即篩，再撳同一粒就取消。
 * `values` = (kind, value, count)，`selected` = (kind, value) or null。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryTagPanel(
    values: List<Triple<String, String, Int>>,
    selected: Pair<String, String>?,
    onSelect: (String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Tag（撳一粒篩書，再撳取消）",
                style = MaterialTheme.typography.titleSmall,
            )
            values.groupBy { it.first }.forEach { (kind, entries) ->
                Text(
                    text = kind,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { (_, value, count) ->
                        FilterChip(
                            selected = selected?.first == kind && selected?.second == value,
                            onClick = {
                                if (selected?.first == kind && selected?.second == value) {
                                    onSelect(null, null)
                                } else {
                                    onSelect(kind, value)
                                }
                            },
                            label = { Text("$value ($count)") },
                        )
                    }
                }
            }
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null, null) },
                label = { Text("全部（清返篩選）") },
            )
        }
    }
}
