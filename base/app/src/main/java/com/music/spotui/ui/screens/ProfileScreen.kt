package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.api.UserProfile
import app.myspoti.api.best
import app.myspoti.engine.R
import app.myspoti.host.android.MyspotiRuntime
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import kotlinx.coroutines.launch

/**
 * A Spotify user's profile, as the Web Player shows it: picture, name, followers and
 * following, a Follow button (not on your own profile) and the public playlists.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ProfileScreen(navController: NavController, userId: String, userName: String = "") {
    val uri = "spotify:user:$userId"
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    var following by remember(userId) { mutableStateOf<Boolean?>(null) }
    val me = com.music.spotui.data.api.ProfileCache.id == userId
    val scope = rememberCoroutineScope()
    LaunchedEffect(userId) {
        runCatching { MyspotiRuntime.api.users.profile(uri) }
            .onSuccess { profile = it }.onFailure { failed = true }
        if (!me) following = runCatching { MyspotiRuntime.api.users.isFollowing(listOf(uri)).single() }.getOrNull()
    }
    val p = profile
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.music.spotui.ui.components.bottomBarsSpace()),
        modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding(),
    ) {
        item {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accessibility_navigate_back), tint = Color.White,
                modifier = Modifier.pressable(0.96f) { navController.navigateUp() }.padding(16.dp).size(26.dp),
            )
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                GlideImage(
                    model = p?.images?.best(300)?.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    failure = placeholder(R.drawable.placeholder),
                    loading = placeholder(R.drawable.placeholder),
                    modifier = Modifier.size(150.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(14.dp))
                Text(p?.name ?: userName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    val parts = listOfNotNull(
                        p.followers?.let { pluralStringResource(R.plurals.profile_followers, it, it) },
                        p.following?.let { stringResource(R.string.profile_following_count, it) },
                    )
                    if (parts.isNotEmpty()) Text(parts.joinToString(" • "), color = Color(0xFFB3B3B3), fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                val f = following
                if (!me && f != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(if (f) R.string.curation_button_following_state_text else R.string.curation_button_follow_state_text),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .border(1.dp, if (f) Color.White else Color(0xFF878787), RoundedCornerShape(50))
                            .pressable(0.96f) {
                                com.music.spotui.di.Haptics.confirm()
                                following = !f
                                scope.launch {
                                    runCatching {
                                        if (f) MyspotiRuntime.api.users.unfollow(listOf(uri)) else MyspotiRuntime.api.users.follow(listOf(uri))
                                    }.onFailure { following = f }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
        when {
            p == null && !failed -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent)
                }
            }
            p == null -> item {
                Text(stringResource(R.string.home_error), color = Color.White, modifier = Modifier.padding(16.dp))
            }
            p.publicPlaylists.isNotEmpty() -> {
                item {
                    Text(stringResource(R.string.profile_public_playlists), color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp))
                }
                items(p.publicPlaylists, key = { it.uri }) { pl ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier.fillMaxWidth()
                            .pressable(0.98f) { navController.navigate(playlistRoute(pl.uri.substringAfterLast(':'), pl.name)) }
                            .padding(16.dp, 8.dp),
                    ) {
                        GlideImage(
                            model = pl.images.best(160)?.url, contentDescription = null, contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            pl.followers?.let {
                                Text(pluralStringResource(R.plurals.profile_followers, it.toInt(), it.toInt()),
                                    color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
