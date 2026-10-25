package eu.kanade.presentation.more.settings.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * tidyFiles 連線設定：PC 上嘅 cleanup-app 地址同存取 token。
 * 兩樣嘢都寫入 SourcePreferences，改完即刻生效（TidyFilesSource 每次讀 build 一次 headers）。
 */
class SettingsTidyFilesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val preferences = remember { Injekt.get<SourcePreferences>() }

        var url by remember { mutableStateOf(preferences.tidyFilesUrl().get()) }
        var token by remember { mutableStateOf(preferences.tidyFilesToken().get()) }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.tidyfiles_settings_title),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.tidyfiles_settings_url_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("http://192.168.50.163:8765") },
                )
                Text(
                    text = stringResource(MR.strings.tidyfiles_settings_token_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    text = stringResource(MR.strings.tidyfiles_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        preferences.tidyFilesUrl().set(url.trim())
                        preferences.tidyFilesToken().set(token.trim())
                        Toast.makeText(context, "已儲存", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
            }
        }
    }
}
