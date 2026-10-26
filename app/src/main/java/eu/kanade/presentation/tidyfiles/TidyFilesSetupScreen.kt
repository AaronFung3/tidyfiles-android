package eu.kanade.presentation.tidyfiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 首次啟動閘：未驗證過 PC 伺服器就一直停喺呢一頁，其他畫面一律入唔到。
 *
 * 驗證成功會順手把地址同授權碼寫入偏好設定，並即刻同步一次書庫。
 */
@Composable
fun TidyFilesSetupScreen(
    initialUrl: String,
    initialToken: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }

    var url by remember { mutableStateOf(initialUrl.ifBlank { "http://100.78.220.22:8782" }) }
    var token by remember { mutableStateOf(initialToken) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "授權",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "要連上你自己的伺服器才可以使用。輸入地址同授權碼，驗證成功會自動載入內容。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            label = { Text("伺服器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                error = null
            },
            label = { Text("授權碼") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                if (busy) return@Button
                val cleanUrl = url.trim().trimEnd('/')
                val cleanToken = token.trim()
                if (cleanUrl.isBlank() || cleanToken.isBlank()) {
                    error = "兩項都要填。"
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    val failure = verify(cleanUrl, cleanToken)
                    if (failure != null) {
                        busy = false
                        error = failure
                        return@launch
                    }
                    sourcePreferences.tidyFilesUrl().set(cleanUrl)
                    sourcePreferences.tidyFilesToken().set(cleanToken)
                    sourcePreferences.tidyFilesSetupDone().set(true)
                    busy = false
                    // 即刻放行：書庫同步交畀背景做，唔好卡住授權頁（同步 288 本要幾分鐘）
                    onDone()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        runCatching {
                            (Injekt.get<SourceManager>().get(TidyFilesSource.ID) as? TidyFilesSource)
                                ?.getPopularManga(1)
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(if (busy) "驗證中…" else "驗證並開始")
        }
    }
}

/** 回傳 null = 驗證成功；否則回傳可以顯示畀人睇嘅錯誤原因。 */
private suspend fun verify(url: String, token: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("$url/api/shelf?lib=1&k=${URLEncoder.encode(token, "UTF-8")}")
            .header("X-Token", token)
            .header("User-Agent", "tidyFiles-android/1")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                // 伺服器用 Python json.dumps，會有 `{"ok": true, ...}` 嘅空格，所以一定要用 JSON parser 而唔係字串比對
                !response.isSuccessful -> "伺服器回 HTTP ${response.code}。地址啱唔啱？授權碼啱唔啱？"
                JSONObject(body).optBoolean("ok", false) -> null
                else -> "伺服器有回應，但唔係預期嘅 tidyFiles 回應（可能係其他程式喺呢個端口）。"
            }
        }
    }.getOrElse { e ->
        val reason = e.message ?: e::class.java.simpleName
        "連唔到：$reason。檢查地址、端口，同埋兩邊係咪同一個網絡（Wi-Fi 或者 Tailscale）。"
    }
}
