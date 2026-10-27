package eu.kanade.tachiyomi.data.updater

import android.content.Context

/**
 * tidyFiles：app 內更新嘅狀態通道。
 *
 * 下載喺 [AppUpdateDownloadJob]（WorkManager）行、安裝結果由 [AppUpdateBroadcast] 收，
 * 但用戶睇緊嘅係 app 內更新頁（`eu.kanade.tachiyomi.ui.more.NewUpdateScreen`）——
 * 三者唔同生命週期，所以用 SharedPreferences 做最簡單嘅溝通，唔使加依賴。
 *
 * 注意：自更新安裝成功嗰刻系統會殺咗成個 process，所以「裝完」通常寫唔切；
 * 真正嘅收尾係下次啟動收到 ACTION_MY_PACKAGE_REPLACED 嗰時 clear。
 */
object AppUpdateState {
    private const val PREFS_NAME = "tidyfiles_app_update"
    private const val KEY_PHASE = "phase"
    private const val KEY_ERROR = "error"

    const val PHASE_IDLE = "idle"
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_INSTALLING = "installing"
    const val PHASE_FAILED = "failed"

    fun phase(context: Context): String =
        prefs(context).getString(KEY_PHASE, PHASE_IDLE) ?: PHASE_IDLE

    fun error(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    fun setDownloading(context: Context) = set(context, PHASE_DOWNLOADING, null)

    fun setInstalling(context: Context) = set(context, PHASE_INSTALLING, null)

    fun setFailed(context: Context, error: String?) = set(context, PHASE_FAILED, error)

    fun clear(context: Context) = set(context, PHASE_IDLE, null)

    private fun set(context: Context, phase: String, error: String?) {
        prefs(context).edit()
            .putString(KEY_PHASE, phase)
            .apply {
                if (error == null) {
                    remove(KEY_ERROR)
                } else {
                    putString(KEY_ERROR, error)
                }
            }
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
