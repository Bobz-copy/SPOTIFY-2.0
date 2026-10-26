/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 */
package com.music.spotui.ui.immersive.screens

import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import java.util.Calendar
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.myspoti.engine.R
import com.music.spotui.data.api.Features
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.pressHint
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.immersive.components.ImmersiveLargeAlbumCard
import com.music.spotui.ui.immersive.components.ImmersiveMessageState
import com.music.spotui.ui.immersive.components.ImmersiveShelf
import com.music.spotui.ui.immersive.components.ImmersiveShelfCard
import com.music.spotui.ui.immersive.components.ImmersiveShelfRow
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.components.immersiveFeedSkeleton
import com.music.spotui.ui.immersive.components.immersiveHeroCardWidth
import com.music.spotui.ui.immersive.shell.ImmersiveFloatingHeader
import com.music.spotui.ui.immersive.shell.LocalBarControlSize
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveHeaderContentPadding
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.viewmodel.HomeViewModel

/**
 * Home in the Immersive look: the greeting as the page's large title with the profile disc at its
 * end, the facet pills, then the feed — the first carousel as big cards, the rest as shelves. The
 * data, clicks, prefetch and profile actions are those of the Classic Home screen.
 */
@Composable
fun ImmersiveHomeScreen(navController: NavController) {
    val homeViewModel: HomeViewModel = viewModel()
    val home by homeViewModel.home.collectAsState()
    val feed = (home as? Response.Success)?.data
    val facet = homeViewModel.facet.value
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProfileCache.ensure(context) }

    // Re-tapping the Home tab while on Home scrolls back to the top, as in the Classic screen.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        TabReselect.home.collect {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val homeTitle = stringResource(R.string.navigationbar_musicappitems_home_title)
    val title = feed?.greeting?.takeIf { it.isNotBlank() } ?: homeTitle
    var showProfile by remember { mutableStateOf(false) }
    if (showProfile) {
        ImmersiveProfileSheet(
            onOpen = { route -> showProfile = false; navController.navigate(route) },
            onDismiss = { showProfile = false },
        )
    }

    // Podcasts can be off: their shows and episodes then leave the feed.
    val sections = remember(feed) { feed?.let { visibleSections(it) }.orEmpty() }
    // Only the untitled section the web home starts with is a shortcut grid; a titled lead
    // section is a carousel like any other.
    val gridSection = sections.firstOrNull()?.takeIf { it.title.isBlank() }
    val carousels = if (gridSection != null) sections.drop(1) else sections
    // The shortcuts at the top load in the background, ready before a tap.
    LaunchedEffect(gridSection?.items?.map { it.name }) {
        val top = gridSection?.items.orEmpty().take(8).mapNotNull { item ->
            when (item) {
                is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
                is HomeItem.Playlist -> item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
                else -> null
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
        LazyColumn(
            state = listState,
            // The greeting is the floating header's own title, so the feed starts right under it
            // (it used to be a large title under an empty bar row: the gap at the top of Home).
            contentPadding = PaddingValues(top = immersiveHeaderContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource().immersiveChromeScroll(),
        ) {
            item(key = "home:travel-hero") {
                HomeTravelHero()
            }
            item(key = "home:facets") {
                ImmersiveChipRow {
                    ImmersiveChip(stringResource(R.string.filter_chip_all_label), selected = facet.isEmpty()) {
                        if (facet.isNotEmpty()) homeViewModel.selectFacet(facet)
                    }
                    ImmersiveChip(stringResource(R.string.recents_content_filter_music), selected = facet == "music-chip") {
                        homeViewModel.selectFacet("music-chip")
                    }
                    if (Features.PODCASTS) {
                        ImmersiveChip(stringResource(R.string.ad_category_title_podcasts), selected = facet == "podcasts-chip") {
                            homeViewModel.selectFacet("podcasts-chip")
                        }
                        ImmersiveChip(stringResource(R.string.audiobooks_title), selected = facet == "audiobooks-chip") {
                            homeViewModel.selectFacet("audiobooks-chip")
                        }
                    }
                }
            }
            when {
                feed != null && feed.sections.isNotEmpty() -> {
                    gridSection?.let { section ->
                        item(key = "home:shortcuts") {
                            ShortcutGrid(navController, section.items.take(8))
                        }
                    }
                    carousels.forEachIndexed { index, section ->
                        item(key = "home:section:$index:${section.title}") {
                            // The lead carousel gets the big cards, unless it is a row of artists.
                            val hero = index == 0 && section.items.none { it is HomeItem.Artist }
                            if (hero) HeroSection(navController, section) else ShelfSection(navController, section)
                        }
                    }
                    // Sections from extensions (home.sections), after Spotify's own.
                    item(key = "home:extensions") {
                        com.music.spotui.extensions.ExtensionBlocks(app.myspoti.extensions.Hook.HOME, app.myspoti.extensions.Surface.HOME, "{}")
                    }
                }
                home is Response.Loading -> immersiveFeedSkeleton(firstIsHero = true)
                else -> item(key = "home:message") {
                    ImmersiveMessageState(
                        message = stringResource(if (home is Response.Error) R.string.home_error else R.string.home_empty),
                        actionLabel = if (home is Response.Error) stringResource(R.string.home_retry) else null,
                        onAction = if (home is Response.Error) ({ homeViewModel.retry() }) else null,
                    )
                }
            }
        }
        // The greeting and the profile disc on floating glass; the feed scrolls under it.
        ImmersiveFloatingHeader(title) {
            val size = LocalBarControlSize.current
            Box(
                Modifier.size(size).graphicsLayer { scaleX = size / 44.dp; scaleY = scaleX },
                contentAlignment = Alignment.Center,
            ) {
                ProfileDisc(onClick = { showProfile = true })
            }
        }
    }
}

private fun visibleSections(feed: HomeFeedModel): List<HomeSection> =
    if (Features.PODCASTS) feed.sections
    else feed.sections.map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }.filter { it.items.isNotEmpty() }

/**
 * A quiet, time-aware travel photograph above the real Spotify feed. The chosen image is stable
 * for the current time block, so this is a hero, not an autoplaying slideshow. The dark gradient
 * remains a usable fallback if a photograph cannot load.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeTravelHero() {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val (photo, place) = when (hour) {
        in 5..9 -> "https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e?auto=format&fit=crop&w=1800&q=85" to "KYOTO, JAPAN"
        in 10..14 -> "https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=1800&q=85" to "THE GREAT WALL, CHINA"
        in 15..17 -> "https://images.unsplash.com/photo-1528127269322-539801943592?auto=format&fit=crop&w=1800&q=85" to "VIETNAM"
        in 18..21 -> "https://images.unsplash.com/photo-1536599018102-9f803c140fc1?auto=format&fit=crop&w=1800&q=85" to "HONG KONG"
        else -> "https://images.unsplash.com/photo-1519501025264-65ba15a82390?auto=format&fit=crop&w=1800&q=85" to "ASIA AFTER DARK"
    }
    val (japanese, english) = when (hour) {
        in 5..10 -> "おはようございます。" to "Good morning"
        in 11..16 -> "こんにちは。" to "Good afternoon"
        else -> "こんばんは。" to "Good evening"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter)
            .padding(top = 8.dp, bottom = 20.dp)
            .height(228.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Immersive.Raised),
    ) {
        GlideImage(
            model = photo,
            contentDescription = place,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.08f),
                    0.38f to Color.Black.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = place,
                style = Immersive.Caption.copy(
                    color = Color.White.copy(alpha = 0.78f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                ),
            )
            Text(
                text = japanese,
                style = Immersive.Title.copy(color = Color.White, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = english,
                style = Immersive.Subhead.copy(color = Color.White.copy(alpha = 0.84f)),
            )
        }
    }
}

/** Albums and playlists start loading under the finger. */
private fun prefetchHomeItem(item: HomeItem) {
    when (item) {
        is HomeItem.Album -> if (item.id.isNotBlank()) ContextViewModel.prefetch("album", item.id)
        is HomeItem.Playlist -> if (item.id.isNotBlank()) ContextViewModel.prefetch("playlist", item.id)
        else -> {}
    }
}

private fun onHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.id))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Show ->
            if (item.isEpisode) SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(showRoute(item.id, item.name))
        // The real playlist content by its id (daily mixes, etc).
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}

/** The uri of the thing this card opens, to tell whether it is what is playing. */
private fun HomeItem.contextUri(): String = when (this) {
    is HomeItem.Album -> if (id.isNotBlank()) "spotify:album:$id" else ""
    is HomeItem.Playlist -> if (id.isNotBlank()) "spotify:playlist:$id" else ""
    is HomeItem.Artist -> if (id.isNotBlank()) "spotify:artist:$id" else ""
    is HomeItem.Show -> if (id.isNotBlank() && !isEpisode) "spotify:show:$id" else ""
}

/** True while this card's album, playlist or artist is the playing context. */
@Composable
private fun HomeItem.isPlayingNow(): Boolean {
    val uri = contextUri()
    if (uri.isEmpty()) return false
    return SongPlayer.nowPlaying.value.contextUri == uri && Graph.currentSongState.playingState.value
}

@Composable
private fun HomeItem.cardSubtitle(): String = when (this) {
    is HomeItem.Album -> subtitle
    is HomeItem.Playlist -> subtitle
    is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
    is HomeItem.Show -> subtitle
}

/** The lead carousel: big artwork cards with the caption over a scrim, the next one peeking in. */
@Composable
private fun HeroSection(navController: NavController, section: HomeSection) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(section.title)
        BoxWithConstraints {
            // Measured: the card has a ceiling as well as a fraction, and the skeleton agrees.
            val cardWidth = immersiveHeroCardWidth(maxWidth)
            ImmersiveShelfRow {
                items(section.items.size) { i ->
                    val item = section.items[i]
                    ImmersiveLargeAlbumCard(
                        title = item.name,
                        subtitle = item.cardSubtitle(),
                        imageUrl = item.imageUrl,
                        onClick = { onHomeItemClick(navController, item) },
                        modifier = Modifier.width(cardWidth).pressHint { prefetchHomeItem(item) },
                        playing = item.isPlayingNow(),
                    )
                }
            }
        }
    }
}

/** A titled row of square cards (round for artists). */
@Composable
private fun ShelfSection(navController: NavController, section: HomeSection) {
    ImmersiveShelf(title = section.title) {
        items(section.items.size) { i ->
            val item = section.items[i]
            ImmersiveShelfCard(
                title = item.name,
                subtitle = item.cardSubtitle(),
                imageUrl = item.imageUrl,
                onClick = { onHomeItemClick(navController, item) },
                modifier = Modifier.width(com.music.spotui.ui.immersive.components.ImmersiveShelfCardWidth)
                    .pressHint { prefetchHomeItem(item) },
                round = item is HomeItem.Artist,
                playing = item.isPlayingNow(),
            )
        }
    }
}

/** The two-column shortcuts the web home starts with: a small cover and a name on a raised tile. */
@Composable
private fun ShortcutGrid(navController: NavController, items: List<HomeItem>) {
    Column(
        modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val playing = item.isPlayingNow()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .pressHint { prefetchHomeItem(item) }
                            .immersiveClickable(onClick = { onHomeItemClick(navController, item) })
                            .clip(Immersive.CoverMedium)
                            .background(Immersive.Raised),
                    ) {
                        ImmersiveCover(
                            url = item.imageUrl,
                            modifier = Modifier.size(56.dp),
                            shape = Immersive.CoverMedium,
                            border = false,
                        )
                        Text(
                            text = item.name,
                            style = Immersive.Subhead.copy(
                                color = if (playing) Immersive.Accent else Immersive.Text,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 17.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp).weight(1f),
                        )
                    }
                }
                if (rowItems.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
