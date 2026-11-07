package eu.kanade.presentation.tidyfiles

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** staging 一本新書（`GET /api/incoming` 嘅 item，淨低 UI 要用嘅欄）。 */
data class IncomingItem(
    val id: Int,
    val status: String,
    val name: String,
    val sizeText: String,
    val pages: Int,
    val error: String,
    val series: String,
    val volume: String,
    val author: String,
    val tags: List<String>,
)

private fun parseIncomingItems(arr: JSONArray): List<IncomingItem> = buildList {
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val guess = obj.optJSONObject("guess") ?: JSONObject()
        val over = obj.optJSONObject("over") ?: JSONObject()
        // prefill：用戶改過嘅 over 優先，跟住 PC 已 normalize 嘅 tags，最後 PC 嘅 guess
        fun pick(guessKey: String, overKey: String): String =
            over.optString(overKey).ifBlank { guess.optString(guessKey) }
        val overTags = over.optJSONArray("tags")
        val finalTags = obj.optJSONArray("tags")
        val guessTags = guess.optJSONArray("tags")
        fun readTags(arr: JSONArray?): List<String> = (0 until (arr?.length() ?: 0))
            .mapNotNull { arr?.optString(it)?.ifBlank { null } }
        val tags = when {
            overTags != null -> readTags(overTags)
            finalTags != null -> readTags(finalTags)
            else -> readTags(guessTags)
        }
        add(
            IncomingItem(
                id = obj.optInt("id"),
                status = obj.optString("status"),
                name = obj.optString("name"),
                sizeText = obj.optString("size_text"),
                pages = obj.optInt("pages"),
                error = obj.optString("error"),
                series = pick("series", "series"),
                volume = pick("volume", "volume"),
                author = pick("author", "author"),
                tags = tags,
            ),
        )
    }
}

private fun statusText(status: String): String = when (status) {
    "downloading" -> "下載中"
    "ready" -> "可以入庫"
    "error" -> "出錯"
    "skipped" -> "略過咗"
    "ingested" -> "入咗庫"
    "dropped" -> "掉咗"
    else -> status
}

/**
 * 手機端「新書 staging」：PC 嘅 `E:\incoming\`（手機分享／貼 link 暫存嘅新書）。
 *
 * 同 PC 網頁版 `/in` 一樣：逐本打 作品／卷／作者／tag，然後入庫（`POST /api/incoming/ingest`，
 * PC 端經 `library.write_cbz()` 寫入書庫，成功先刪 staging 原檔）。全部只喺 staging 範圍，
 * PC 端呢組 endpoint 本來就准唯讀裝置（手機）call。
 */
class TidyFilesIncomingScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val items = remember { mutableStateListOf<IncomingItem>() }
        var loading by remember { mutableStateOf(false) }
        var loadError by remember { mutableStateOf<String?>(null) }
        val busyIds = remember { mutableStateListOf<Int>() }
        // 每本嘅可編輯欄位（keyed by id）
        val seriesText = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
        val volumeText = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
        val authorText = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
        val tagsText = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

        suspend fun reload() {
            loading = true
            loadError = null
            TidyFilesApi.incomingList().fold(
                onSuccess = { arr ->
                    val parsed = parseIncomingItems(arr)
                    items.clear()
                    items.addAll(parsed)
                    // 新出現嘅 item 先預填一次（唔好覆蓋用戶打緊嘅嘢）
                    seriesText.value = parsed.associate {
                        it.id to (seriesText.value[it.id] ?: it.series)
                    }
                    volumeText.value = parsed.associate {
                        it.id to (volumeText.value[it.id] ?: it.volume)
                    }
                    authorText.value = parsed.associate {
                        it.id to (authorText.value[it.id] ?: it.author)
                    }
                    tagsText.value = parsed.associate {
                        it.id to (tagsText.value[it.id] ?: it.tags.joinToString("、"))
                    }
                },
                onFailure = { loadError = it.message ?: "讀唔到 staging" },
            )
            loading = false
        }

        LaunchedEffect(Unit) {
            while (true) {
                if (busyIds.isEmpty()) reload()
                delay(5_000L)
            }
        }

        fun overridesFor(item: IncomingItem): JSONObject {
            val over = JSONObject()
            seriesText.value[item.id]?.let { if (it.isNotBlank()) over.put("series", it.trim()) }
            volumeText.value[item.id]?.let { if (it.isNotBlank()) over.put("volume", it.trim()) }
            authorText.value[item.id]?.let { if (it.isNotBlank()) over.put("author", it.trim()) }
            tagsText.value[item.id]?.let { if (it.isNotBlank()) over.put("tags", it.trim()) }
            return JSONObject().put(
                "overrides",
                JSONObject().put(item.id.toString(), over),
            )
        }

        fun act(item: IncomingItem, action: String, body: JSONObject, done: String) {
            if (busyIds.contains(item.id)) return
            busyIds.add(item.id)
            scope.launch {
                TidyFilesApi.incomingAction(action, body).fold(
                    onSuccess = {
                        busyIds.remove(item.id)
                        Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                        reload()
                    },
                    onFailure = {
                        busyIds.remove(item.id)
                        Toast.makeText(context, "${item.name}：${it.message}", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "新書 staging",
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loadError != null) {
                    item {
                        Text(
                            text = "讀唔到 staging：$loadError",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (items.isEmpty() && !loading && loadError == null) {
                    item {
                        Text(
                            text = "staging 係空嘅。手機分享 ZIP 去 PC 個 PWA（或者喺 PC 貼 link），" +
                                "之後就會喺呢度逐本打 tag 入庫。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val pending = items.filter { it.status == "downloading" || it.status == "ready" || it.status == "error" }
                val settled = items.size - pending.size
                items(pending, key = { it.id }) { item ->
                    IncomingCard(
                        item = item,
                        busy = busyIds.contains(item.id),
                        series = seriesText.value[item.id].orEmpty(),
                        volume = volumeText.value[item.id].orEmpty(),
                        author = authorText.value[item.id].orEmpty(),
                        tags = tagsText.value[item.id].orEmpty(),
                        onSeries = { seriesText.value = seriesText.value + (item.id to it) },
                        onVolume = { volumeText.value = volumeText.value + (item.id to it) },
                        onAuthor = { authorText.value = authorText.value + (item.id to it) },
                        onTags = { tagsText.value = tagsText.value + (item.id to it) },
                        onIngest = {
                            act(
                                item,
                                "ingest",
                                overridesFor(item).put("ids", JSONArray().put(item.id)),
                                "${item.name} 入咗庫",
                            )
                        },
                        onSkip = {
                            act(
                                item,
                                "skip",
                                JSONObject().put("ids", JSONArray().put(item.id)),
                                "${item.name} 略過咗",
                            )
                        },
                    )
                }
                if (settled > 0) {
                    item {
                        Text(
                            text = "已完成／略過／掉咗 $settled 本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingCard(
    item: IncomingItem,
    busy: Boolean,
    series: String,
    volume: String,
    author: String,
    tags: String,
    onSeries: (String) -> Unit,
    onVolume: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onTags: (String) -> Unit,
    onIngest: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = listOf(item.sizeText, "${item.pages} 頁".takeIf { item.pages > 0 })
                            .filterNotNull()
                            .joinToString("・"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = statusText(item.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.status == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (item.error.isNotBlank()) {
                Text(
                    text = item.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = series,
                onValueChange = onSeries,
                label = { Text("作品") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = volume,
                    onValueChange = onVolume,
                    label = { Text("卷") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = onAuthor,
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = tags,
                onValueChange = onTags,
                label = { Text("tag（用「、」或者「,」分開）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && item.status == "ready", onClick = onIngest) {
                    Text(if (busy) "處理中…" else "入庫")
                }
                TextButton(enabled = !busy, onClick = onSkip) {
                    Text("略過")
                }
            }
        }
    }
}
