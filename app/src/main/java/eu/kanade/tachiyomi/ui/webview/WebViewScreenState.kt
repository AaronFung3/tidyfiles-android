package eu.kanade.tachiyomi.ui.webview

sealed interface WebViewScreenState {
    data object Loading : WebViewScreenState
}
