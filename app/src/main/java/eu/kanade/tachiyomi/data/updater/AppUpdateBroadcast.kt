package eu.kanade.tachiyomi.data.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import tachiyomi.i18n.kmk.KMR

class AppUpdateBroadcast : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appUpdateNotifier = AppUpdateNotifier(context)

        if (intent.action == AppUpdateDownloadJob.PACKAGE_INSTALLED_ACTION) {
            /*
             * Callback on PackageInstaller status
             */
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                    context.startActivity(confirmIntent?.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

                    appUpdateNotifier.cancelInstallNotification()
                    val uri = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_FILE_URI) ?: return
                    val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                    appUpdateNotifier.promptInstall(uri.toUri(), title)
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    try {
                        // tidyFiles --> 裝完：清更新狀態，順手試自動開返 app
                        AppUpdateState.clear(context)
                        relaunchApp(context)
                        // tidyFiles <--
                        appUpdateNotifier.onInstallFinished()
                    } finally {
                        AppUpdateDownloadJob.stop(context)
                        appUpdateNotifier.cancelInstallNotification()
                    }
                }
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_ABORTED,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE,
                -> {
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        // tidyFiles --> 安裝失敗：app 內更新頁要識得講返出咗咩事
                        AppUpdateState.setFailed(context, "install status=$status")
                        // tidyFiles <--
                        context.toast(KMR.strings.could_not_install_update)
                        val uri = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_FILE_URI) ?: return
                        val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
                        appUpdateNotifier.cancelInstallNotification()
                        appUpdateNotifier.onInstallError(uri.toUri(), title)
                    }
                }
            }
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            /*
             * System broadcast that is sent when the current application package has been replaced with a new version,
             * to perform actions when app is updated or reinstalled.
             */
            // tidyFiles --> 自更新裝完會殺 process，呢個係新 process 收到嘅第一手機會：清狀態＋自動重開
            AppUpdateState.clear(context)
            relaunchApp(context)
            // tidyFiles <--
            appUpdateNotifier.onInstallFinished()
        }
    }

    /**
     * tidyFiles --> 裝完之後試下自動開返 app。
     *
     * Android 10+ 對「背景啟動 Activity」有攔截，所以呢步係盡做：失敗通常唔會拋例外，
     * 只會喺 logcat 見到 Background activity launch blocked；用戶仍然可以撳「更新完成」通知開返。
     */
    private fun relaunchApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            xLogE("Unable to relaunch app after update", e)
        }
    }
    // tidyFiles <--
}
