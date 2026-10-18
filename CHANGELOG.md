# Changelog

Each release on GitHub shows its section below. The app shows it too when it offers the update. Every detail: [docs/CHANGELOG-full.md](docs/CHANGELOG-full.md).

## Unreleased

- **Long playlists open and scroll faster**: the list is filtered and sorted again only when the search, the sort or a like changes (not at every recomposition), and rows keep their identity across sorts and filters.
- **Notification and lock screen no longer churn**: only a real change (song, play/pause, buffering, heart, shuffle) reaches the media session and the notification; the position is extrapolated by the system in between.
- **Skips a little faster and less work while music plays**: a fade is one call to the page instead of three (the ramp runs in the page while the command is on its way), the volume check comes with the media read instead of polling every 6 s, and the watcher looks every 4 s instead of 2 s when nothing plays.
- **Hearts (liked states) are asked once**: a page and its prefetch share the same request, and what Spotify confirmed less than a minute ago is not asked again (opening a playlist asked the whole list twice, then again for each page of tracks).
- **Far fewer calls to the player page**: "ready" is announced once per page load (twice meant every listener of it ran twice: state, token, quality, volume), the web token is fetched once and shared, and the Connect device list is read at most every 6 s in the page instead of every 2 s.
- **Myspoti's icon in the notification** and in Samsung's Now Bar, instead of a generic music note.
- **Ads that get through are silent**: they are muted on the page itself, no longer through a Spotify Connect volume command that some accounts refuse (the ad was heard). The song after the ad no longer stays paused.
- **Previous no longer pauses**: "previous" in the middle of a song restarts it and keeps playing (the skip fade left the sound at zero, and Android's WebView pauses silent media in the background).
- **Music comes back after a page reload**: when the player page reloads (frozen page, low memory, WebView restart) the song goes on where it was instead of staying paused; a frozen page is caught in a few seconds.
- **A song that stops being heard is restarted**: the app checks that the music really plays, not only what the page says.
- **Play right after launch works**: taps made while the player loads are sent once it is ready.
- **Shorter silence on ad breaks** (free accounts): about 4 s less.
- **Skips are about three times faster**: the next song is heard ~0.8 s after the tap instead of ~2.5 s (the next song is loaded ahead, the first tap is sent at once, a shorter fade, and the hidden Spotify page no longer redraws its home screen at every song).
- **Cover back to its full size** in the player.
- **Myspoti's buttons play on this phone again** when another device (the Spotify app on the same account, for instance) took playback without being picked in Myspoti: the music comes back here first, instead of driving that device (where skips, previous and volume were refused).
- **New languages**: German, Spanish, Portuguese and Greek, on every screen.
- **Music no longer stops for a minute**: a Web Player page that stops answering is reloaded at once, and playback goes on.
- **No more silent songs**: the sound comes back by itself if a skip or a muted ad left it at zero; after a skip it comes back as soon as the next song is there.
- **Smart shuffle** goes on from where the song is (no restart), adds songs to every playlist, and has its own icon in the player.
- **Free accounts refused by Spotify** (some countries): the list plays from its start instead, and a message explains why.
- **GrapheneOS**: the player loads with "secure app spawning" on.
- **Followers and following** lists on profiles; **your liked songs** on artist pages; **episodes** in playlists; unavailable songs greyed out.
- **Fast scrollbar** on long playlists and the library.
- **Share as image** (Instagram story and more); swipe up on the cover for the lyrics; player fits foldables and wide screens.
- **Settings**: autoplay on or off, accent colour, screen rotation (portrait, automatic, landscape).
- **Canvas**: loaded while the mini player shows, so the player opens straight on it; when it is still loading, it fades in smoothly over the cover. Tap it to watch it alone (the player hides), tap again to bring the player back; it is a little darker under the player so the text reads. Its previous / next buttons no longer appear.

## 2.0.0-beta.6

- **Fewer ads abroad**: ads from other countries' ad servers are blocked, sponsored playlists and promotion shelves are hidden; ads are recognised even when they look like songs; an ad that still gets through is muted and skipped.
- **Steadier player**: play, pause and skips go one at a time (fast taps no longer jam it), and a song that did not start is asked again.
- **No more "pop" between songs**: a very short fade on every skip.
- **Smart shuffle from the player**: tap shuffle twice on a playlist or Liked Songs.
- **Remote control**: with another device playing, the notification and the slider keep up.
- **Sleep timer "end of track"** works with episodes, local files and repeat one; artist pages load the whole discography.
- **Artist discography** (Releases) shows albums and singles again.
- **Long names fit**: long playlist names and artist mixes no longer push the layout; big titles shrink to fit, cards in a row keep the same height.
- **A pause stays a pause**, even right at the end of a song.
- **Downloads are safe**: a damaged download list no longer deletes your downloaded songs.
- **Changes Spotify refuses are undone on screen** (likes, playlist visibility), with a message.
- **Search** keeps your query when you come back, and says when it is loading, empty or failed (with Try again). Artist and profile pages can be reloaded too.
- **Jam**: join with a copied link; sharing an episode works.
- **Logout** stops everything (local songs, video, notification); the data saver applies from launch.
- Player buttons are named for TalkBack.

## 2.0.0-beta.5

- **Encrypted downloads**: downloaded songs only play in Myspoti, on your phone.
- **Offline queue**: the queue of downloaded songs shows what really plays next, and can be edited.
- **Downloaded songs are no longer reported to Spotify**: no more album starting over a downloaded song.
- **In-app updates**: download and install without leaving the app.
- **Jam QR scan** no longer crashes; a banner tells when downloads need the SpotiFLAC verification.
- **Your playlists, edited in the app**: name, description, cover (from your photos), public, private or collaborative, delete.
- **Profiles**: search people, open a profile (followers, public playlists), follow; a playlist's owner opens theirs.
- **More from Spotify**: "Don't play this artist", save episodes, search suggestions, "More like this" under albums, library sorted by recents or recently added, pin and move items into folders.
- **Sort in both directions** (tap the chosen sort again), remembered for each playlist.
- **Fixes**: AMOLED black everywhere, lists no longer end under the mini player, full-screen music videos show, the Spotify account page stays under the status bar, "Saved in" confirms each change, fast skips no longer jam the player, no white screen on phones in light mode.
- **Security and reliability fixes** (Android Auto, library pages, logout, links, video lookups).

## 2.0.0

A new engine, written from scratch, and a cleaner app on top of it.

- **No ads**, with a free account: the ad audio is refused and the next song starts.
- **New engine**: faster screens, playback that works on every phone, Spotify updates picked up by themselves.
- **Lossless downloads** (FLAC CD or hi-res, or small AAC), encrypted, with their own offline queue.
- **Sound quality** for streaming and downloads, and a **Settings** page.
- **Connect** shows your headphones; music pauses when they go away.
- **Podcasts and audiobooks**, **Jam**, **New releases**, **Browse**, **Your recap**, **widget**.
- **Library**: sort by title, artist, album, duration or date added; smart shuffle; folders.
- **In-app updates**, **Italian**, new icon; beta and release install side by side.

## 1.1.1

- **Data saver** (profile menu): on mobile data Myspoti uses no data at all. Only downloaded songs and Local Files play, downloads wait for Wi-Fi, and the Spotify player comes back on its own on Wi-Fi.
- **Find in a list**: search inside Liked Songs, a playlist or Local Files (title, artist or album).
- **Your Library**: moving a playlist (hold, then drag) works, in the list and in the grid; the drag stopped as soon as it started.
- **Settings** open in full, so the list scrolls without moving the sheet.

## 1.1.0

- **Downloads**: download a song (from its menu), an album, a playlist or Liked Songs, and listen without a connection, from **Downloaded** in Your Library or from the album or playlist page.
  - The audio comes from YouTube Music, as YouTube serves it (no re-encoding). Each song is checked against the original recording; when none matches, the song is marked as not downloadable instead of saving the wrong one.
  - Files stay inside Myspoti. A downloaded album or playlist stays in step: songs added to it are downloaded, songs that left it (a renewed Blend, a removed song) are deleted, unless another downloaded list or your own download keeps them.
  - Once a list is downloaded, its button becomes a bin. In Downloaded, swipe a song from right to left to remove it, or remove everything from the profile menu.
- **Folders**: Your Library shows your Spotify folders and opens them level by level. The + at the top creates a playlist, a folder (a real Spotify folder) or a playlist on this device; a folder can be renamed.
- **Sort and reorder Your Library**: sort by custom order, alphabetical or creator; hold a playlist, then drag it to move it (kept on this phone). Liked Songs, Local Files and Downloaded stay on top.
- **Local files in playlists**: the + of a song in Local Files adds it to a playlist on this device, or to one of your Spotify playlists, under **Added on this device** (Spotify can't receive phone files, so they stay on the phone).
- **The + of a song**: the first tap saves it in Liked Songs; tapping the green check then opens **Saved in** to add it to your playlists. Saved in only lists the playlists you can change, and says so when Spotify refuses a change.
- **Player**: pull it down from the top to close it, like the back button.
- **Go to song radio** in every song's menu (player, search, playlists…).
- **Music videos**: a title with an apostrophe ("J'SAVAIS") no longer gets the clip of another song; instrumental uploads are skipped.
- **Home** in the bottom bar always opens Home.
- **Search**: playlists in the results, and filters for songs, artists, albums and playlists.
- **Podcasts and audiobooks are hidden for now**; they will come back in a later version.
- **Artist pages**: the whole discography, not only the releases Spotify highlights.
- **Language**: French on a French phone, English on any other, with no more mixed languages. **App language** in the profile menu picks another one (Spotify's labels are translated, Myspoti's own texts stay in English).
- **Steadier and lighter player**: the hidden Spotify player stays on a tested version kept on the phone, so a Spotify website update can no longer break the app overnight, and it no longer loads covers, Canvas loops and fonts nobody sees (about half the memory and a quarter of the requests at startup).
- **Restart the player** in the profile menu, for when the music stops responding.
- **Battery saver**: Myspoti's own saver is independent of Android's (which already lowers the screen rate) and only follows its switch. The AMOLED theme is its own choice too, and returning to the app no longer looks up a music video for nothing.
- **Crossfade**: no background work when it is off, and the setting is kept when the player reloads.
- **About** in the profile menu: the version, the [Discord server](https://discord.gg/jXKnGVFnGS) and the source code.
- **Support Myspoti**: Myspoti is free and has no ads; you can now support it on Ko-fi from the profile menu or About. A short reminder appears a day after installing this version, then every 2 weeks; "No thanks" closes it, and nothing is locked behind a donation.
- New versions are checked every 3 hours instead of once a day.
- Your Library still opens Local Files and Downloaded without a connection, and the notification shows the cover of local and downloaded songs.

## 1.0.4

- **Local Files**: a folder in Your Library, right under Liked Songs, with the music stored on your phone. It plays in the app like the rest (player, mini player, notification, lock screen, headset buttons).
- **Spotify Connect**: the notification now follows track changes on the other device; it could stay on the previous song.
- Fewer updates from the player while it plays on another device (less CPU and battery).
- No like button on local files.
- One more Spotify ad server blocked.
- The update window shows what's new.

## 1.0.3

- **Spotify Connect**: music no longer jumps back to the phone or stops by itself.
  - Playing from Liked Songs goes to the device that is actually playing.
  - Swiping the app away no longer pauses a Connect device.
  - A Connect session closed by the system no longer pulls playback back to the phone.
- **Crossfade** (Settings, 0–12 s): the end of a track fades out and the next one fades in.
- **Background playback**: Myspoti asks once to lift battery optimization, so music and Connect keep going with the screen off. Also in Settings.
- **Android Auto** works again: Liked Songs, playlists, albums, artists, search and voice play.
- **Android 10 and 11** are supported.
- The update window matches the app's style.

## 1.0.2

- New welcome screen before signing in, and a new crash screen.
- Every screen follows the phone's language (official labels in 73 languages, English and French for Myspoti's own texts).
- New icons, new notification icon, older screens removed.

## 1.0.1

- **Spotify Connect**: the notification keeps following the other device with the screen off.
- Reopening the app no longer moves playback back to the phone.

## 1.0.0

- First release: Spotify with a free account and no ads, native interface, synced lyrics, official music videos, Spotify Connect.
