package eu.kanade.presentation.more

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * tidyFiles：「更多」頁只剩兩個入口（設定、關於）。
 *
 * 原本嘅下載佇列、分類、統計、備份／儲存空間、批次加入、最近更新、歷史、
 * 書庫更新錯誤、Discord 狀態回報一律移除。
 */
@Composable
fun MoreScreen(
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    Scaffold { contentPadding ->
        ScrollbarLazyColumn(
            // KMK: use contentPadding as preferable padding for ScrollbarLazyColumn when not using stickyHeader
            contentPadding = contentPadding,
        ) {
            item {
                LogoHeader()
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onClickSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
        }
    }
}
