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
import kotlinx.coroutines.launch

/**
 * 「PC 書庫管理」彈窗：直接改 PC 端嘅書（改書名／揀封面頁／手打 tag）。
 *
 * PC 端三個 endpoint 本來就准手機 call：
 *   · `POST /api/shelf/rename` —— 改書內 ComicInfo `<Series>` ＋ folder／檔名
 *   · `POST /api/shelf/cover` —— 第幾頁做封面（0 ＝ 第一頁）
 *   · `POST /api/view/tags` —— 手打 tag（同 PC 網頁版同一個 `booktags.json`）
 *
 * `rel` 用第一卷嘅 rel 做錨點：tag／封面係「一本書」粒度，系列咁多卷就用第一卷
 * 代表（PC 端書架 chips 本來就會合埋所有卷嘅 tag）。改書名會令 PC 端 rel 變，
 * 但讀書狀態會由 PC 書簽反向同步補返（只前進唔後退）。
 */
@Composable
fun TidyFilesManageDialog(
    rel: String,
    title: String,
    tags: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(title) }
    var coverPage by remember { mutableStateOf("0") }
    var tagsText by remember { mutableStateOf(tags.joinToString("、")) }
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
                        if (cleanName.isNotEmpty() && cleanName != title) {
                            TidyFilesApi.rename(rel, cleanName).fold(
                                onSuccess = { results += "書名改咗 ✓" },
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
