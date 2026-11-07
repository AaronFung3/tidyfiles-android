package eu.kanade.presentation.tidyfiles

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesApi
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesSource
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val BULK_OPS = listOf(
    "tags_add" to "加 tag",
    "rating" to "評分",
    "finished" to "讀完",
)

/**
 * 「PC 批量」彈窗：書櫃多選之後一次過改 PC 端（`POST /api/shelf/bulk`）。
 *
 * PC 端逐本做、唔 rollback；批量改 series／author PC 會 403（只准本機），
 * 所以手機只開三個 op：加 tag／評分（0–5）／讀完（標記／取消）。
 * `rels` 由 LibraryScreenModel 用每本第一卷嘅 rel 砌好。
 * 套用成功會即刻重掃 PC 書架，令 tag／評分跟返手機書櫃。
 */
@Composable
fun TidyFilesBulkDialog(
    rels: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var op by remember { mutableStateOf("tags_add") }
    var rating by remember { mutableIntStateOf(3) }
    var finished by remember { mutableStateOf(true) }
    var tagsText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = { Text("PC 批量（${rels.size} 本）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "套用喺選中嘅 ${rels.size} 本書，直接寫 PC 書庫。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BULK_OPS.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = op == value, onClick = { op = value }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = op == value, onClick = { op = value })
                        Text(label)
                    }
                }
                when (op) {
                    "tags_add" -> OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("加 tag（用「、」或者「,」分開）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    "rating" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (0..5).forEach { value ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.selectable(
                                    selected = rating == value,
                                    onClick = { rating = value },
                                ),
                            ) {
                                RadioButton(selected = rating == value, onClick = { rating = value })
                                Text("$value")
                            }
                        }
                    }
                    else -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(true to "標記讀完", false to "取消讀完").forEach { (value, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.selectable(
                                    selected = finished == value,
                                    onClick = { finished = value },
                                ),
                            ) {
                                RadioButton(selected = finished == value, onClick = { finished = value })
                                Text(label)
                            }
                        }
                    }
                }
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
                enabled = !busy && (op != "tags_add" || tagsText.isNotBlank()),
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    message = null
                    scope.launch {
                        val (realOp, value) = when (op) {
                            "tags_add" -> "tags_add" to tagsText
                            "rating" -> "rating" to rating.toString()
                            else -> "finished" to finished.toString()
                        }
                        TidyFilesApi.bulk(realOp, rels, value).fold(
                            onSuccess = {
                                // 即刻同步：加嘅 tag／評分即刻跟返手機書櫃
                                runCatching {
                                    (Injekt.get<SourceManager>().get(TidyFilesSource.ID) as? TidyFilesSource)
                                        ?.refreshNow()
                                }
                                busy = false
                                Toast.makeText(context, "PC 批量搞掂（${rels.size} 本）", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            },
                            onFailure = {
                                busy = false
                                message = "做唔到：${it.message}"
                            },
                        )
                    }
                },
            ) {
                Text("套用")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismissRequest) { Text("取消") }
        },
    )
}
