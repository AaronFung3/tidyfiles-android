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

    // ---- PC 書庫管理（由書本詳情頁嘅「PC 書庫管理」彈窗用）--------------------
    //
    // PC 端三個 endpoint 本來就准手機 call（同書簽一樣只過 token）：
    //   · /api/shelf/rename  改書內 ComicInfo <Series> ＋ folder／檔名
    //   · /api/shelf/cover   揀第幾頁做封面（只寫 <書庫>\.tidy\covers.json）
    //   · /api/view/tags     手打 tag（只寫 <work>\booktags.json）

    /** PC 端回 ok=true 就算成功；否則回 PC 畀嘅錯誤字串（或者 HTTP 碼）。 */
    private suspend fun post(path: String, body: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = Injekt.get<SourcePreferences>()
            val base = prefs.tidyFilesUrl().get().trim().trimEnd('/')
            val token = prefs.tidyFilesToken().get().trim()
            if (base.isBlank() || token.isBlank()) throw Exception("未設定伺服器／授權碼")
            val request = Request.Builder()
                .url("$base$path")
                .header("X-Token", token)
                .header("User-Agent", "tidyFiles-android/1")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            client().newCall(request).execute().use { response ->
                val obj = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                if (response.isSuccessful && obj.optBoolean("ok", false)) return@use
                throw Exception(obj.optString("error").ifBlank { "HTTP ${response.code}" })
            }
        }.map { }
    }

    /** 改書名（PC 端會改 ComicInfo <Series> ＋ folder／檔名）。 */
    suspend fun rename(rel: String, name: String): Result<Unit> =
        post("/api/shelf/rename", JSONObject().put("rel", rel).put("name", name))

    /** 揀第 N 頁做封面（0 ＝ 第一頁）。 */
    suspend fun setCover(rel: String, page: Int): Result<Unit> =
        post("/api/shelf/cover", JSONObject().put("rel", rel).put("page", page))

    /** 手打 tag（整組取代；`tags` 空清單 ＝ 清走呢本書嘅手打 tag）。 */
    suspend fun setTags(rel: String, tags: List<String>): Result<Unit> =
        post("/api/view/tags", JSONObject().put("rel", rel).put("tags", org.json.JSONArray(tags)))
}
