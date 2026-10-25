/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt at edc1e6f.
 * Modified for Myspoti, 2026-10.
 *
 * Changes: the sections (now playing, next in queue, next from the context), placement-only
 * item motion and the fading edges, over Myspoti's SongPlayer.engineQueue. A row is removed
 * by swiping it away, and shuffle and repeat sit in the header as in the queue sheet.
 * Drag-to-reorder, history and the autoplay heading are left out for now.
 */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.screens.ExplicitBadge

/**
 * How a queue row moves when the running order changes under it.
 *
 * Placement only — the fades are off deliberately. A row Compose treats as removed or
 * inserted goes on being drawn at the slot it used to hold for as long as it takes to fade,
 * and a queue row's background is transparent: the fading copy and whichever row is sliding
 * through that slot both draw their title and artist into the same few pixels. Without the
 * fades a row that leaves is gone the moment it leaves, so movement is the only thing left
 * to see. Bounded rather than the default spring so a track change settles at once.
 */
private val QUEUE_ROW_MOTION = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

/** Placement-only item motion; see [QUEUE_ROW_MOTION]. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.queueMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = QUEUE_ROW_MOTION)

/**
 * The queue of the player, inside the player: what is playing, then "Next in queue" and
 * "Next from <context>". Tap a row to play it, swipe it away to remove it; shuffle and repeat
 * are in the header.
 *
 * The list brings its own side gutter; [contentPadding] is the extra room it keeps clear of
 * whatever the player draws over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveQueuePanel(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    DisposableEffect(Unit) {
        SongPlayer.watchQueue(true)
        onDispose { SongPlayer.watchQueue(false) }
    }
    val queue by SongPlayer.engineQueue
    val song = Graph.currentSongState
    val shuffle = song.shuffle.value
    val repeatMode = SongPlayer.repeatMode.value
    val contextName = SongPlayer.nowPlaying.value.contextName

    // Rows swiped away, hidden at once: the queue takes a moment to report the removal.
    val removed = remember { mutableStateListOf<String>() }
    LaunchedEffect(queue) { removed.clear() }
    val queued = queue.queued.dedupedKeys("q").filter { it.first !in removed }
    val nextUp = queue.nextUp.dedupedKeys("n").filter { it.first !in removed }

    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    val direction = LocalLayoutDirection.current
    val current = queue.current
    val hasCurrent = song.title.value.isNotBlank() || current != null

    LazyColumn(
        state = listState,
        modifier = modifier
            .nestedScroll(keepScroll)
            .fadingEdges(),
        contentPadding = PaddingValues(
            start = Immersive.Gutter + contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = Immersive.Gutter + contentPadding.calculateEndPadding(direction),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "header") {
            QueueHeader(
                shuffle = shuffle,
                repeatMode = repeatMode,
                onShuffle = {
                    SongPlayer.setShuffle(!shuffle)
                    song.shuffle.value = !shuffle
                },
                onRepeat = { SongPlayer.cycleRepeat() },
            )
        }
        if (hasCurrent) {
            item(key = "heading-now") {
                QueueHeading(
                    title = stringResource(R.string.immersive_queue_now_playing),
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            item(key = "now-playing") {
                QueueRow(
                    title = song.title.value.ifBlank { current?.title.orEmpty() },
                    artist = song.singer.value.ifBlank { current?.singer.orEmpty() },
                    cover = song.coverUri.value.ifBlank { current?.coverUri.orEmpty() },
                    isCurrent = true,
                    explicit = current?.explicit == true,
                    onClick = {},
                    modifier = queueMotion(),
                )
            }
        }
        if (queued.isNotEmpty()) {
            item(key = "heading-queued") {
                QueueHeading(
                    title = stringResource(R.string.next_in_queue),
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                ) {
                    Text(
                        text = stringResource(R.string.clear_queue),
                        style = Immersive.Subhead,
                        color = Immersive.TextSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { SongPlayer.clearQueue() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            queueSection(queued, removed)
        }
        if (nextUp.isNotEmpty()) {
            item(key = "heading-next") {
                QueueHeading(
                    title = if (contextName.isNotBlank()) {
                        stringResource(R.string.next_from, contextName)
                    } else {
                        stringResource(R.string.next_up)
                    },
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            queueSection(nextUp, removed)
        }
        if (!hasCurrent && queued.isEmpty() && nextUp.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.immersive_queue_empty),
                    style = Immersive.Body,
                    color = Immersive.TextSecondary,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
    }
}

/** Keys each item by its own id, with a suffix where two rows share one: a lazy list throws on a repeat. */
private fun List<SongPlayer.QueueItem>.dedupedKeys(prefix: String): List<Pair<String, SongPlayer.QueueItem>> {
    val used = HashSet<String>()
    return map { item ->
        val base = "$prefix:${item.uid}|${item.uri}"
        var key = base
        var repeat = 1
        while (!used.add(key)) key = base + "~" + repeat++
        key to item
    }
}

/** One section's rows, each swiped away to remove it. */
@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.queueSection(
    rows: List<Pair<String, SongPlayer.QueueItem>>,
    removed: SnapshotStateList<String>,
) {
    items(rows.size, key = { rows[it].first }) { index ->
        val (key, item) = rows[index]
        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                removed += key
                SongPlayer.removeFromQueue(item)
                true
            } else {
                false
            }
        })
        SwipeToDismissBox(
            state = dismiss,
            enableDismissFromStartToEnd = false,
            modifier = queueMotion(),
            backgroundContent = {
                Box(
                    Modifier.fillMaxSize().padding(end = 4.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.immersive_queue_remove),
                        tint = Immersive.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        ) {
            QueueRow(
                title = item.song.title,
                artist = item.song.singer,
                cover = item.song.coverUri,
                isCurrent = false,
                explicit = item.song.explicit,
                onClick = { SongPlayer.skipTo(item) },
            )
        }
    }
}

@Composable
private fun QueueHeader(
    shuffle: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.immersive_queue_title),
            style = Immersive.Title,
            modifier = Modifier.weight(1f),
        )
        QueueToggle(Icons.Rounded.Shuffle, stringResource(R.string.immersive_queue_shuffle), shuffle, onShuffle)
        Spacer(Modifier.width(8.dp))
        QueueToggle(
            if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            stringResource(R.string.immersive_queue_repeat),
            repeatMode != 0,
            onRepeat,
        )
    }
}

/** A round toggle: lit white when on, a quiet disc when off. */
@Composable
private fun QueueToggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun QueueHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = Immersive.BodyStrong,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    title: String,
    artist: String,
    cover: String,
    isCurrent: Boolean,
    explicit: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Immersive.RowHeight)
            .clip(Immersive.CoverMedium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isCurrent,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlideImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(Immersive.CoverSmall)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = Immersive.Body,
                color = if (isCurrent) Immersive.Accent else Immersive.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = artist,
                    style = Immersive.Subhead,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = stringResource(R.string.immersive_queue_now_playing),
                tint = Immersive.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
