package dev.irohaco.ui

import dev.irohaco.R

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.irohaco.core.designsystem.icon.IrohacoIcons
import dev.irohaco.core.designsystem.preview.IrohacoPreviews
import dev.irohaco.core.designsystem.theme.IrohacoTheme

/** The four top-level destinations (PRD §8.1, DESIGN §3). Settings opens from the top app bar later. */
enum class TopLevelDestination(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    PHOTOS(R.string.destination_photos, IrohacoIcons.Photo, IrohacoIcons.PhotoFilled),
    ALBUMS(R.string.destination_albums, IrohacoIcons.PhotoAlbum, IrohacoIcons.PhotoAlbumFilled),
    SEARCH(R.string.destination_search, IrohacoIcons.Search, IrohacoIcons.Search),
    LIBRARY(R.string.destination_library, IrohacoIcons.Folder, IrohacoIcons.FolderFilled),
}

/**
 * The app shell. NavigationSuiteScaffold shows a short navigation bar on Compact widths and a wide
 * navigation rail on wider windows. Back from another tab returns to Photos before leaving the app.
 * Each tab keeps its saved state (such as the scroll position) while another tab is shown.
 */
@Composable
fun IrohacoApp(
    initialDestination: TopLevelDestination = TopLevelDestination.PHOTOS,
    destinationContent: @Composable (TopLevelDestination) -> Unit = { DestinationPlaceholder(it) },
) {
    var current by rememberSaveable { mutableStateOf(initialDestination) }
    val tabStates = rememberSaveableStateHolder()
    BackHandler(enabled = current != TopLevelDestination.PHOTOS) { current = TopLevelDestination.PHOTOS }
    NavigationSuiteScaffold(
        navigationItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == current
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { current = destination },
                    icon = {
                        Icon(
                            painterResource(if (selected) destination.selectedIcon else destination.icon),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        tabStates.SaveableStateProvider(current.name) { destinationContent(current) }
    }
}

/** Stands in for each tab until its feature Issue (M0-10 timeline and later) replaces it. */
@Composable
internal fun DestinationPlaceholder(destination: TopLevelDestination) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(destination.label)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}

@IrohacoPreviews
@Composable
private fun IrohacoAppPreview() {
    IrohacoTheme(dynamicColor = false) { IrohacoApp() }
}

@Preview(name = "Expanded", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun IrohacoAppExpandedPreview() {
    IrohacoTheme(dynamicColor = false) { IrohacoApp(TopLevelDestination.SEARCH) }
}
