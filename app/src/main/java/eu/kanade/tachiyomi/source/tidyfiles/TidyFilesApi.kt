package eu.kanade.tachiyomi.source.tidyfiles

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * tidyFiles PC 端嘅非讀取類 API（唔經 source object 嗰個 OkHttp client，
 * 因為進度同步喺 ViewModel 度觸發，嗰度攞唔到 source 嘅 client）。
 *
 * - `postProgressAsync`：fire-and-forget 將「睇到邊頁／讀完」同步返 PC
 *   （`ReaderViewModel` 退出 reader 嗰陣 call）。任何失敗都吞咗 ——
 *   同步唔可以影響睇書，下次退出 reader 又會再報一次。
 * - `pair`：一次性配對碼（6 位數字）換真 token（SetupScreen 用；
 *   PC 端規矩：5 分鐘過期、單一用途、錯 8 次作廢）。
 */
object TidyFilesApi {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * PC 端 `POST /api/view/progress`：`{rel, page, total?, name, finished?}`。
     * PC 端會寫 `<書庫>\_cleanup\reader.json`，書架「繼續睇」條就會跟到。
     */
    fun postProgressAsync(rel: String, page: Int, name: String, finished: Boolean) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val prefs = Injekt.get<SourcePreferences>()
                val base = prefs.tidyFilesUrl().get().trim().trimEnd('/')
                val token = prefs.tidyFilesToken().get().trim()
                if (base.isBlank() || token.isBlank() || rel.isBlank()) return@launch
                val body = JSONObject()
                    .put("rel", rel)
                    .put("page", page)
                    .put("name", name)
                    .put("finished", finished)
                val request = Request.Builder()
                    .url("$base/api/view/progress")
                    .header("X-Token", token)
                    .header("User-Agent", "tidyFiles-android/1")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client().newCall(request).execute().use { response ->
                    response.body?.string()
                }
            }
        }
    }

    /** PC 端 `POST /api/pair`：6 位配對碼換真 token；換唔到回 null。 */
    suspend fun pair(url: String, code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$url/api/pair")
                .header("User-Agent", "tidyFiles-android/1")
                .post(JSONObject().put("code", code).toString().toRequestBody(jsonType))
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val obj = JSONObject(body)
                if (obj.optBoolean("ok", false)) obj.optString("token", "").takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}
