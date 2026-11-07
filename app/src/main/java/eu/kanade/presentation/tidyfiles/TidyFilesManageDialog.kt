package eu.kanade.presentation.tidyfiles

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesApi
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesSource
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesTagKind
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 「PC 書庫管理」彈窗：直接改 PC 端嘅書（改書名／揀封面頁／手打 tag）。
 *
 * PC 端三個 endpoint 本來就准手機 call：
 *   · `POST /api/shelf/rename` —— 改書內 ComicInfo `<Series>` ＋ folder／檔名
 *   · `POST /api/shelf/cover` —— 第幾頁做封面（0 ＝ 第一頁）
 *   · `POST /api/view/tags` —— 手打 tag（同 PC 網頁版同一個 `booktags.json`）
 *
 * `rel` 用第一卷嘅 rel 做錨點：tag／封面係「一本書」粒度，系列咁多卷就用第一卷
 * 代表（PC 端書架 chips 本來就會合埋所有卷嘅 tag）。
 *
 * 存完會即刻重掃 PC 書架同步落本地（tag／書名唔使等下一次全域更新）。
 * 改書名會令 PC 端 seriesKey 變 → 同步會出「新條目」；舊條目自動移出書櫃
 * （只係本地書櫃唔再顯示，PC 檔案同讀書狀態一啲都唔郁）。
 */
@Composable
fun TidyFilesManageDialog(
    rel: String,
    title: String,
    tags: List<String>,
    onDismissRequest: () -> Unit,
    // tidyFiles --> 改名後清舊條目用：開呢個彈窗嗰本書嘅 seriesKey（空 ＝ 跳過）
    oldKey: String = "",
    // tidyFiles <--
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(title) }
    var coverPage by remember { mutableStateOf("0") }
    // 只 prefill 手打 tag（「作者：」「品質：」嗰啲住喺 ComicInfo，唔關呢個編輯器事）
    var tagsText by remember { mutableStateOf(TidyFilesTagKind.handTagsOf(tags).joinToString("、")) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = { Text("PC 書庫管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "直接改 PC 端嘅書，改完下次同步會跟返嚟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("書名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = coverPage,
                    onValueChange = { coverPage = it.filter(Char::isDigit).take(4) },
                    label = { Text("封面用第幾頁（0 ＝ 第一頁）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tag（用「、」或者「,」分開）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    message = null
                    scope.launch {
                        val results = mutableListOf<String>()
                        val cleanName = name.trim()
                        val cleanTags = tagsText.split("、", ",", "，", ";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        var renamed = false
                        if (cleanName.isNotEmpty() && cleanName != title) {
                            TidyFilesApi.rename(rel, cleanName).fold(
                                onSuccess = {
                                    renamed = true
                                    results += "書名改咗 ✓"
                                },
                                onFailure = { results += "書名改唔到：${it.message}" },
                            )
                        }
                        coverPage.toIntOrNull()?.let { page ->
                            TidyFilesApi.setCover(rel, page).fold(
                                onSuccess = { results += "封面設定咗 ✓" },
                                onFailure = { results += "封面設定唔到：${it.message}" },
                            )
                        }
                        TidyFilesApi.setTags(rel, cleanTags).fold(
                            onSuccess = { results += "Tag 存咗 ✓" },
                            onFailure = { results += "Tag 存唔到：${it.message}" },
                        )
                        // tidyFiles --> 存完即刻同步：tag／書名／封面即刻跟返嚟，
                        // 唔使等下一次全域更新。改咗名嘅話同步會出「新條目」，
                        // 舊 seriesKey 喺 PC 已經冇咗 → 舊條目（即係開住呢個彈窗嗰本）
                        // 移出書櫃（只係本地書櫃唔再顯示，唔刪任何檔案）。
                        runCatching {
                            val source = Injekt.get<SourceManager>()
                                .get(TidyFilesSource.ID) as? TidyFilesSource
                            val keys = source?.refreshNow().orEmpty()
                            if (renamed && oldKey.isNotEmpty() && keys.isNotEmpty() && oldKey !in keys) {
                                val repo = Injekt.get<MangaRepository>()
                                repo.getMangaByUrlAndSourceId(oldKey, TidyFilesSource.ID)?.let { old ->
                                    repo.update(MangaUpdate(id = old.id, favorite = false))
                                    results += "舊條目已移出書櫃，書櫃會見到新條目"
                                }
                            }
                        }
                        // tidyFiles <--
                        busy = false
                        val text = results.joinToString("；")
                        message = text
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismissRequest) { Text("關閉") }
        },
    )
}
