package eu.kanade.presentation.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * tidyFiles：「更多」頁只有書庫連線資訊、設定、關於。
 *
 * 原本嘅下載佇列、分類、統計、備份／儲存空間、批次加入、最近更新、歷史、
 * 書庫更新錯誤、Discord 狀態回報一律移除。
 */
@Composable
fun MoreScreen(
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val serverUrl = sourcePreferences.tidyFilesUrl().get()

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
                    title = "書庫伺服器",
                    subtitle = serverUrl,
                    icon = Icons.Outlined.Link,
                    onPreferenceClick = onClickSettings,
                )
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
            item {
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
