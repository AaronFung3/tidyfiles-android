package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.source.tidyfiles.TidyFilesSource
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryItem>,
    showTitle: Boolean,
    columns: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    // tidyFiles -->
    groupItems: Boolean = false,
    onEditTags: ((LibraryManga) -> Unit)? = null,
    // tidyFiles <--
) {
    // tidyFiles -->
    // remember() 只可以喺 @Composable context 叫，所以喺 LazyGridScope 之前算好
    val tidyRows: List<LibraryRow> = remember(items, groupItems) {
        if (groupItems) buildLibraryRows(items) else emptyList()
    }
    // tidyFiles <--
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        // tidyFiles -->
        if (groupItems) {
            items(
                items = tidyRows,
                span = { row ->
                    if (row is LibraryRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
                contentType = { row ->
                    when (row) {
                        is LibraryRow.Header -> "library_group_header"
                        is LibraryRow.Manga -> "library_compact_grid_item"
                    }
                },
            ) { row ->
                when (row) {
                    is LibraryRow.Header -> LibraryGroupHeader(name = row.name, count = row.count)
                    is LibraryRow.Manga -> CompactItem(
                        libraryItem = row.item,
                        showTitle = showTitle,
                        selection = selection,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onClickContinueReading = onClickContinueReading,
                        onEditTags = onEditTags,
                    )
                }
            }
        } else {
            // tidyFiles <--
            items(
                items = items,
                contentType = { "library_compact_grid_item" },
            ) { libraryItem ->
                CompactItem(
                    libraryItem = libraryItem,
                    showTitle = showTitle,
                    selection = selection,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickContinueReading = onClickContinueReading,
                    onEditTags = onEditTags,
                )
            }
            // tidyFiles -->
        }
        // tidyFiles <--
    }
}

@Composable
private fun CompactItem(
    libraryItem: LibraryItem,
    showTitle: Boolean,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    // tidyFiles --> 書櫃每格直接加標籤
    onEditTags: ((LibraryManga) -> Unit)? = null,
    // tidyFiles <--
) {
    val manga = libraryItem.libraryManga.manga
    MangaCompactGridItem(
        isSelected = manga.id in selection,
        title = manga.title.takeIf { showTitle },
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            ogUrl = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverBadgeStart = {
            DownloadsBadge(count = libraryItem.downloadCount)
            UnreadBadge(count = libraryItem.unreadCount)
        },
        coverBadgeEnd = {
            // tidyFiles --> 只留來源圖示，唔顯示語言國旗
            SourceIconBadge(source = libraryItem.source)
            // tidyFiles <--
        },
        onLongClick = { onLongClick(libraryItem.libraryManga) },
        onClick = { onClick(libraryItem.libraryManga) },
        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
            { onClickContinueReading(libraryItem.libraryManga) }
        } else {
            null
        },
        // tidyFiles --> 只限 tidyFiles 來源嘅書先有「改 tag」掣
        onClickEditTags = onEditTags
            ?.takeIf { libraryItem.libraryManga.manga.source == TidyFilesSource.ID }
            ?.let { { it(libraryItem.libraryManga) } },
        // tidyFiles <--
    )
}
