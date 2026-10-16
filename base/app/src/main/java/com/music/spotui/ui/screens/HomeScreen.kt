package com.music.spotui.ui.screens

import com.music.spotui.ui.components.SkeletonKind
import com.music.spotui.ui.components.pressable

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.HomeViewModel
import java.time.LocalTime


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController){

    val homeViewModel : HomeViewModel = viewModel()
    val home by homeViewModel.home.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val feed = (home as? Response.Success)?.data

        when {
            // Preferred: the real personalized Spotify home feed.
            feed != null && feed.sections.isNotEmpty() -> {
                HomeFeedContent(navController, feed, homeViewModel.facet.value) { homeViewModel.selectFacet(it) }
            }

            // No feed to show (loading, empty chip, error): keep the chips on screen
            // so the user can always switch back to "Tout".
            else -> androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                HomeHeaderRow(navController, homeViewModel.facet.value) { homeViewModel.selectFacet(it) }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        home is Response.Loading -> Loader(SkeletonKind.Home)
                        else -> Text(
                            text = if (home is Response.Error) stringResource(R.string.home_error) else stringResource(R.string.home_empty),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Albums and playlists start loading under the finger (see pressHint). */
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
            if (item.isEpisode) com.music.spotui.di.SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(com.music.spotui.ui.navigation.showRoute(item.id, item.name))
        // Load the real playlist content by its Spotify id (daily mixes, etc).
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun HomeFeedContent(navController: NavController, feed: HomeFeedModel, facet: String = "", onFacet: (String) -> Unit = {}) {
    // Mirror open.spotify.com exactly: sections render in the order the feed
    // returns them. The 2-column "shortcuts" grid is only used for the UNTITLED
    // section the web home starts with — if the feed leads with a titled section
    // ("Jump back in", "Made For …"), it renders as a titled carousel first,
    // not force-squeezed into the grid.
    // Podcasts are off for now: their shows and episodes leave the feed.
    val sections = if (com.music.spotui.data.api.Features.PODCASTS) feed.sections
        else feed.sections.map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }.filter { it.items.isNotEmpty() }
    val gridSection = sections.firstOrNull()?.takeIf { it.title.isBlank() }
    val carousels = if (gridSection != null) sections.drop(1) else sections
    // The shortcuts at the top load in the background, ready before a tap.
    androidx.compose.runtime.LaunchedEffect(gridSection?.items?.map { it.name }) {
        val top = gridSection?.items.orEmpty().take(8).mapNotNull { item ->
            when (item) {
                is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
                is HomeItem.Playlist -> item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
                else -> null
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        // Sticky filter row: content scrolls underneath it, like the official app.
        stickyHeader {
            // Fade (not a solid band): opaque under the status bar, transparent at the bottom.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(AppBackground.toArgb()),
                            0.55f to Color(AppBackground.toArgb()).copy(alpha = 0.85f),
                            1f to Color.Transparent,
                        )
                    )
                    .statusBarsPadding()
                    .padding(bottom = 10.dp)
            ) {
                HomeHeaderRow(navController, facet, onFacet)
            }
        }
        gridSection?.let { section ->
            item {
                HomeShortcutGrid(navController, section.items.take(8))
            }
        }
        items(carousels.size) { i ->
            HomeFeedSection(navController, carousels[i])
        }
    }
}

/** Spotify-style top row: profile avatar on the left (opens Settings, like the
 *  official app's profile drawer), then the filter pills — one single row. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeHeaderRow(navController: NavController, facet: String, onFacet: (String) -> Unit) {
    var showProfile by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showProfile) ProfileSheet(onOpen = { route -> showProfile = false; navController.navigate(route) }) { showProfile = false }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 10.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        val initial = com.music.spotui.data.api.ProfileCache.name
            ?.trim()?.firstOrNull()?.uppercase() ?: "•"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8622C))
                .clickable { showProfile = true },
        ) {
            if (avatarUrl != null) {
                GlideImage(
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = stringResource(R.string.recently_played_user_profile),
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Text(
                    text = initial,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // Scrolls sideways if the pills don't fit, like Spotify's.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 12.dp, end = 8.dp),
        ) {
            // Spotify's home chips; tapping the selected one goes back to "Tout".
            HomePill(stringResource(R.string.filter_chip_all_label), selected = facet.isEmpty()) { onFacet("") }
            HomePill(stringResource(R.string.recents_content_filter_music), selected = facet == "music-chip") { onFacet("music-chip") }
            if (com.music.spotui.data.api.Features.PODCASTS) {
                HomePill(stringResource(R.string.ad_category_title_podcasts), selected = facet == "podcasts-chip") { onFacet("podcasts-chip") }
                HomePill(stringResource(R.string.audiobooks_title), selected = facet == "audiobooks-chip") { onFacet("audiobooks-chip") }
            }
        }
    }
}

@Composable
private fun HomePill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) com.music.spotui.ui.theme.AppAccent else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeShortcutGrid(navController: NavController, items: List<HomeItem>) {
    Column(modifier = Modifier.padding(8.dp, 4.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
            ) {
                rowItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.pressable(0.96f, onPressHint = { prefetchHomeItem(item) }) { onHomeItemClick(navController, item) }
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(GridBackground.toArgb()))
                            ,
                    ) {
                        GlideImage(
                            modifier = Modifier.size(48.dp),
                            contentScale = ContentScale.Crop,
                            model = item.imageUrl,
                            loading = placeholder(R.drawable.placeholder),
                            failure = placeholder(R.drawable.placeholder),
                            contentDescription = "",
                        )
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            modifier = Modifier.padding(8.dp, 4.dp),
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeFeedSection(navController: NavController, section: HomeSection) {
    Text(
        text = section.title,
        color = Color.White,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp),
    )
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp, 0.dp)) {
        items(section.items.size) { i ->
            HomeFeedCard(section.items[i]) { onHomeItemClick(navController, section.items[i]) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeFeedCard(item: HomeItem, onClick: () -> Unit) {
    val isArtist = item is HomeItem.Artist
    val subtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
        is HomeItem.Show -> item.subtitle
    }
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier.pressable(0.96f, onPressHint = { prefetchHomeItem(item) }) { onClick() }
            .width(150.dp)
            .padding(6.dp)
            ,
    ) {
        GlideImage(
            modifier = Modifier
                .size(150.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = subtitle,
            color = Color(0xFFB3B3B3),
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Profile sheet behind the avatar: account name and sign-out (in the Web Player). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
private fun ProfileSheet(onOpen: (route: String) -> Unit = {}, onDismiss: () -> Unit) {
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val name = com.music.spotui.data.api.ProfileCache.name.orEmpty()
    val avatar = com.music.spotui.data.api.ProfileCache.imageUrl
    // Opened in full: half-open, the sheet and its list both moved under the finger.
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF282828),
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxWidth().then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars))
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (avatar != null) GlideImage(
                    model = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
                Text(name.ifBlank { stringResource(R.string.spotify_account) }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp))
                if (com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE)
                    com.music.spotui.ui.components.SupporterBadge(Modifier.padding(start = 10.dp))
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFF3E3E3E), modifier = Modifier.padding(vertical = 8.dp))
            EnergyOptionRow(
                label = stringResource(R.string.energy_saver_title),
                detail = stringResource(R.string.energy_saver_summary),
                checked = energy.manualSaver,
                onChecked = app.myspoti.power.EnergySettings::setSaver,
            )
            EnergyOptionRow(
                label = stringResource(R.string.data_saver_title),
                detail = stringResource(R.string.data_saver_summary),
                checked = com.music.spotui.data.DataSaver.enabled,
                onChecked = com.music.spotui.data.DataSaver::set,
            )
            EnergyOptionRow(
                label = stringResource(R.string.settings_amoled_theme),
                detail = stringResource(R.string.settings_amoled_theme_subtitle),
                checked = energy.blackPreference,
                onChecked = app.myspoti.power.EnergySettings::setBlackBackground,
            )
            EnergyOptionRow(
                label = stringResource(R.string.settings_item_canvas_title),
                detail = stringResource(if (energy.policy.saverEnabled) R.string.energy_saver_summary else R.string.video_settings_canvas_subtitle),
                checked = energy.canvasEnabled && !energy.policy.saverEnabled,
                enabled = !energy.policy.saverEnabled,
                onChecked = app.myspoti.power.EnergySettings::setCanvasEnabled,
            )
            // In-app recap: every listen on this phone, Spotify, downloads and local files.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { onOpen(com.music.spotui.ui.navigation.Routes.Recap.route) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.recap_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.recap_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // The Spotify account page (plan, privacy, devices), in the app's signed-in browser.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    com.music.spotui.ui.screens.AccountBrowser.open(com.music.spotui.di.Graph.app, com.music.spotui.ui.screens.AccountBrowser.ACCOUNT_URL)
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.account_page_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.account_page_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            LanguageRow()
            // Battery optimization off = music and Connect keep going with the screen off.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var exempt by remember { androidx.compose.runtime.mutableStateOf(app.myspoti.power.BatteryExemption.isExempt(ctx)) }
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exempt = app.myspoti.power.BatteryExemption.isExempt(ctx)
            }
            EnergyOptionRow(
                label = stringResource(R.string.background_playback_title),
                detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                checked = exempt,
                onChecked = {
                    if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    else app.myspoti.power.BatteryExemption.request(ctx)
                },
            )
            // The hidden Web Player stuck or crashed: rebuild it without leaving the app.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    app.myspoti.host.android.MyspotiRuntime.host.reload()
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.restart_player_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.restart_player_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            if (com.music.spotui.data.offline.OfflineStore.tracks.isNotEmpty()) RemoveDownloadsRow()
            // Donations (Ko-fi): always here, never in the way.
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { com.music.spotui.data.api.SupportReminder.open(ctx) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.support_title), color = com.music.spotui.ui.theme.AppAccent, fontSize = 16.sp)
                Text(stringResource(R.string.support_row_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            // About: version, Discord, source code and Ko-fi.
            var about by remember { androidx.compose.runtime.mutableStateOf(false) }
            if (about) com.music.spotui.ui.components.AboutSheet(onDismiss = { about = false })
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { about = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.creator_biography_section_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.about_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            var perks by remember { androidx.compose.runtime.mutableStateOf(false) }
            if (perks) com.music.spotui.ui.components.SupporterSheet(onDismiss = { perks = false })
            if (com.music.spotui.data.api.SupportReminder.PERKS_ENABLED) androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().clickable { perks = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.supporter_perks_title), color = Color.White, fontSize = 16.sp)
                Text(stringResource(R.string.supporter_perks_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.connect_device_menu_logout),
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        val app = com.music.spotui.di.Graph.app
                        app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("LoggedIn", false).remove("CurrentAccountName").apply()
                        com.music.spotui.data.api.Api.HomeCache.clear()
                        com.music.spotui.data.api.Api.LikedCache.tracks = null
                        com.music.spotui.data.api.ProfileCache.clearSession()
                        com.music.spotui.di.SongPlayer.clearSession()
                        listOf("PlaybackState", "LibraryCache", "RecentItems", "LikedSongs",
                            "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
                            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        }
                        com.music.spotui.di.SessionActions.logout()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}


/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_app_language_title), color = Color.White, fontSize = 16.sp)
        Text(label(current), color = Color(0xFFB3B3B3), fontSize = 12.sp)
    }
    if (picking) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { picking = false },
        containerColor = Color(0xFF282828),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.settings_item_app_language_title), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(stringResource(R.string.language_partial_note), color = Color(0xFFB3B3B3), fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
            items(com.music.spotui.data.AppLanguage.AVAILABLE.size) { i ->
                val tag = com.music.spotui.data.AppLanguage.AVAILABLE[i]
                val selected = tag == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        picking = false
                        if (!selected) {
                            com.music.spotui.data.AppLanguage.apply(ctx, tag)
                            // Screens take the new language when the activity is rebuilt.
                            generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(label(tag), color = if (selected) com.music.spotui.ui.theme.AppAccent else Color.White, fontSize = 16.sp)
                        if (tag == com.music.spotui.data.AppLanguage.SYSTEM)
                            Text(stringResource(R.string.language_auto_detail), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                    }
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = com.music.spotui.ui.theme.AppAccent)
                }
            }
        }
    }
}

/** "Remove all downloads", with the space they take; asks first, like the official app. */
@Composable
private fun RemoveDownloadsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { androidx.compose.runtime.mutableStateOf(false) }
    val size = android.text.format.Formatter.formatShortFileSize(ctx, com.music.spotui.data.offline.OfflineStore.totalBytes())
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { ask = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_remove_downloads_title), color = Color.White, fontSize = 16.sp)
        Text(stringResource(R.string.downloads_size, size), color = Color(0xFFB3B3B3), fontSize = 12.sp)
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        containerColor = Color(0xFF282828),
        title = { Text(stringResource(R.string.settings_storage_remove_downloads_dialog_title), color = Color.White) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), color = Color(0xFFB3B3B3)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                ask = false
                com.music.spotui.data.offline.OfflineDownloader.removeAll()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = com.music.spotui.ui.theme.AppAccent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { ask = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = Color.White)
            }
        },
    )
}



@Composable
private fun EnergyOptionRow(label: String, detail: String, checked: Boolean,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            Text(detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
