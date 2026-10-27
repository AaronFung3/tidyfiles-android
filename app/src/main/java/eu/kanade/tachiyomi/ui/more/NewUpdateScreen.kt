package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.data.updater.AppUpdateState
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val changelogInfoNoChecksum = remember {
            changelogInfo
        }

        // tidyFiles --> app 內下載／安裝進度（原本只靠通知欄，通知權限未開就完全睇唔到）
        var downloading by remember { mutableStateOf(false) }
        var installing by remember { mutableStateOf(false) }
        var downloadedBytes by remember { mutableStateOf(0L) }
        var totalBytes by remember { mutableStateOf(0L) }
        var problem by remember { mutableStateOf<String?>(null) }
        var installingTooLong by remember { mutableStateOf(false) }

        // 同 AppUpdateDownloadJob 用同一個檔案，所以可以續傳、亦可以直接讀大細做進度
        val apkFile = remember { File(context.externalCacheDir, "update.apk") }

        fun startDownload() {
            downloading = true
            installing = false
            problem = null
            installingTooLong = false
            downloadedBytes = 0L
            totalBytes = 0L

            // 先問一次檔案大細（HEAD），用嚟算百分比
            scope.launch {
                totalBytes = withContext(Dispatchers.IO) {
                    runCatching {
                        val request = Request.Builder().url(downloadLink).head().build()
                        OkHttpClient().newCall(request).execute().use { response ->
                            response.header("Content-Length")?.toLongOrNull() ?: 0L
                        }
                    }.getOrDefault(0L)
                }
            }

            // 交俾 WorkManager 落載：佢負責續傳、前景通知，S 以上會自己開安裝器（靜默裝）
            AppUpdateDownloadJob.start(
                context = context,
                url = downloadLink,
                title = versionName,
            )

            // 一路輪詢：檔案大細（下載進度）＋ AppUpdateState（落載完轉安裝中、失敗原因）
            scope.launch {
                var lastSize = -1L
                var lastChangeAt = System.currentTimeMillis()
                var installingSince = 0L
                var sawWorkingPhase = false

                while (downloading || installing) {
                    delay(400)

                    val size = withContext(Dispatchers.IO) { if (apkFile.exists()) apkFile.length() else 0L }
                    downloadedBytes = size
                    if (size != lastSize) {
                        lastSize = size
                        lastChangeAt = System.currentTimeMillis()
                    }

                    when (AppUpdateState.phase(context)) {
                        AppUpdateState.PHASE_FAILED -> {
                            problem = AppUpdateState.error(context) ?: "落載／安裝失敗"
                            downloading = false
                            installing = false
                            break
                        }
                        AppUpdateState.PHASE_INSTALLING -> {
                            sawWorkingPhase = true
                            downloading = false
                            installing = true
                        }
                        AppUpdateState.PHASE_DOWNLOADING -> {
                            sawWorkingPhase = true
                        }
                        else -> {
                            // 落到呢度即係 phase 已經清返 idle：如果之前見過 working，代表流程收咗尾
                            if (sawWorkingPhase) {
                                downloading = false
                                installing = false
                                break
                            }
                        }
                    }

                    if (!installing) {
                        val total = totalBytes
                        if (total > 0 && size >= total) {
                            // 檔案落齊：job 好快就會 commit，直接轉「安裝中」，唔好再扮 download
                            downloading = false
                            installing = true
                        }
                    }

                    if (installing) {
                        if (installingSince == 0L) {
                            installingSince = System.currentTimeMillis()
                        }
                        if (!installingTooLong && System.currentTimeMillis() - installingSince > 90_000L) {
                            installingTooLong = true
                        }
                    }

                    if (!downloading && !installing) {
                        break
                    }
                    if (downloading && System.currentTimeMillis() - lastChangeAt > 60_000L) {
                        problem = "下載停咗超過 60 秒，可能係網絡斷咗。可以再試一次。"
                        downloading = false
                        break
                    }
                }
            }
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfoNoChecksum,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onRejectUpdate = navigator::pop,
            onAcceptUpdate = { startDownload() },
        )

        if (downloading) {
            val fraction = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
            AlertDialog(
                onDismissRequest = { /* 下載中，唔好誤觸關閉 */ },
                title = { Text(text = "正在下載 $versionName") },
                text = {
                    Column {
                        Text(text = sizeText(downloadedBytes, totalBytes))
                        if (fraction != null) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                progress = { fraction },
                            )
                            Text(
                                text = "${(fraction * 100).toInt()}%",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { downloading = false }) {
                        Text(text = "喺背景繼續下載")
                    }
                },
            )
        }

        if (installing) {
            AlertDialog(
                onDismissRequest = { /* 安裝進行中，唔好誤觸關閉 */ },
                title = { Text(text = "正在安裝 $versionName") },
                text = {
                    Column {
                        Text(text = "已經落載好，Android 而家喺背後安裝，大約要幾十秒到一分鐘。")
                        Text(
                            text = "裝完 app 會自己關閉，之後開返就係新版。",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (installingTooLong) {
                            Text(
                                text = "等咗好耐仲未關閉？可能安裝失敗咗，去通知欄或者檔案管理員睇下 update.apk。",
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { installing = false }) {
                        Text(text = "喺背景等")
                    }
                },
            )
        }

        problem?.let { message ->
            AlertDialog(
                onDismissRequest = { problem = null },
                title = { Text(text = "更新出咗問題") },
                text = { Text(text = message) },
                confirmButton = {
                    TextButton(onClick = { problem = null }) {
                        Text(text = "關閉")
                    }
                },
                dismissButton = {},
            )
        }
        // tidyFiles <--
    }

    private fun formatMb(bytes: Long): String = "%.1f".format(bytes / 1024f / 1024f)

    private fun sizeText(downloaded: Long, total: Long): String {
        return if (total > 0) {
            "${formatMb(downloaded)} / ${formatMb(total)} MB"
        } else {
            "${formatMb(downloaded)} MB"
        }
    }
}
