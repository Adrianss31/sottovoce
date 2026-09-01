package it.sottovoce.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaParserExtractorAdapter
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import it.sottovoce.app.MainActivity
import it.sottovoce.app.R
import it.sottovoce.app.SottovoceApp
import it.sottovoce.app.data.Book
import it.sottovoce.app.data.chapterPlaybackStart
import it.sottovoce.app.data.chapterTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

object PlaybackSignals {
    val error = MutableStateFlow<String?>(null)
    val timer = MutableStateFlow("")
    const val TIMER_COMMAND = "it.sottovoce.SET_TIMER"
    const val TOGGLE_TIMER_COMMAND = "it.sottovoce.TOGGLE_TIMER_30"
}

fun Book.mediaItems(): List<MediaItem> = chapterTimeline().map { chapter ->
    val track = tracks[chapter.trackIndex]
    val extras = Bundle().apply {
        putString("bookId", id); putInt("trackIndex", chapter.trackIndex)
        putLong("chapterStartMs", chapter.startMs); putInt("chapterOrdinal", chapter.ordinal); putInt("chapterTotal", chapter.total)
    }
    val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(chapter.startMs).apply {
        if (chapter.endMs > chapter.startMs) setEndPositionMs(chapter.endMs)
    }.build()
    MediaItem.Builder().setMediaId("$id/${track.id}/${chapter.startMs}").setUri(track.uri).apply {
        if (track.name.substringAfterLast('.').lowercase() in setOf("m4b", "m4a", "mp4")) setMimeType(MimeTypes.AUDIO_MP4)
    }.setClippingConfiguration(clipping)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(chapter.title).setArtist(author)
            .setSubtitle("Capitolo ${chapter.ordinal} di ${chapter.total}").setAlbumTitle(title).setExtras(extras)
            .setArtworkUri(coverPath?.let { Uri.fromFile(File(it)) }).build()).build()
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val app get() = application as SottovoceApp
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var chapterEnd = -1L
    private var chapterTrack = -1
    private var ticks = 0
    private val timerCommand = SessionCommand(PlaybackSignals.TOGGLE_TIMER_COMMAND, Bundle.EMPTY)
    private fun mediaButtons() = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Indietro 10 secondi")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK).build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(R.drawable.ic_timer_notification)
            .setDisplayName(if (deadline > 0) "Disattiva timer" else "Timer 30 minuti")
            .setSessionCommand(timerCommand).setSlots(CommandButton.SLOT_FORWARD).build(),
    )
    private fun updateMediaButtons() {
        session?.let { mediaSession ->
            val notification = mediaSession.mediaNotificationControllerInfo
            if (notification == null) mediaSession.setMediaButtonPreferences(mediaButtons())
            else mediaSession.setMediaButtonPreferences(notification, mediaButtons())
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) stopTimer(pause = true)
            if (chapterTrack >= 0 &&
                (player.currentMediaItemIndex != chapterTrack || chapterEnd > 0 && player.currentPosition >= chapterEnd - 50)) {
                finishChapterTimer()
            }
            if (++ticks % 12 == 0 && player.isPlaying) save()
            handler.postDelayed(this, 250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("preferences", MODE_PRIVATE)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val playerBuilder = if (Build.VERSION.SDK_INT >= 30) ExoPlayer.Builder(this, renderers, PlatformMediaSourceFactory(this))
            else ExoPlayer.Builder(this, renderers)
        player = playerBuilder.setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(prefs.getInt("skipForward", 30) * 1000L)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { save() }
                    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) save(finished = true) }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        save()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (chapterTrack >= 0 && player.currentMediaItemIndex != chapterTrack) finishChapterTimer()
                        save()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && chapterTrack >= 0) stopTimer(false)
                        save()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("SottovocePlayback", "Riproduzione non riuscita: ${error.errorCodeName}", error)
                        val detail = generateSequence(error.cause) { it.cause }.lastOrNull()?.message
                            ?.replace(Regex("\\s+"), " ")?.take(180)
                        PlaybackSignals.error.value = buildString {
                            append("Impossibile riprodurre questo audio (${error.errorCodeName}).")
                            if (!detail.isNullOrBlank()) append(" ").append(detail)
                        }
                    }
                })
            }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setMediaButtonPreferences(mediaButtons()).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val base = super.onConnect(session, controller)
                val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(base.availableSessionCommands.buildUpon()
                        .add(SessionCommand(PlaybackSignals.TIMER_COMMAND, Bundle.EMPTY))
                        .add(timerCommand)
                        .add(SessionCommand("it.sottovoce.STOP_AND_SAVE", Bundle.EMPTY)).build()).build()
                if (!session.isMediaNotificationController(controller)) return builder
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(timerCommand).build())
                    .setAvailablePlayerCommands(base.availablePlayerCommands.buildUpon()
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build())
                    .setMediaButtonPreferences(mediaButtons()).build()
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                val known = app.library.books.value.flatMap { it.mediaItems() }.associateBy { it.mediaId }
                val resolved = mediaItems.mapNotNull { request ->
                    known[request.mediaId]?.takeIf { item -> app.library.isSafeAudioUri(item.localConfiguration?.uri.toString()) }
                }.toMutableList()
                return Futures.immediateFuture(resolved)
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction == PlaybackSignals.TOGGLE_TIMER_COMMAND &&
                    (session.isMediaNotificationController(controller) || controller.packageName == packageName)) {
                    configureTimer(if (deadline > 0) 0 else 30)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (controller.packageName != packageName)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                if (command.customAction == "it.sottovoce.STOP_AND_SAVE") {
                    val future = SettableFuture.create<SessionResult>()
                    val extras = player.currentMediaItem?.mediaMetadata?.extras
                    val id = extras?.getString("bookId")
                    val index = extras?.getInt("trackIndex") ?: 0
                    val position = (extras?.getLong("chapterStartMs", 0) ?: 0L) + player.currentPosition
                    val speed = player.playbackParameters.speed
                    player.pause(); player.stop(); player.clearMediaItems(); stopTimer(false)
                    app.scope.launch {
                        try {
                            if (id != null) app.library.savePosition(id, index, position, speed)
                            future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        } catch (e: Exception) { future.setException(e) }
                    }
                    return future
                }
                if (command.customAction != PlaybackSignals.TIMER_COMMAND)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                configureTimer(args.getInt("minutes", 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                app.scope.launch {
                    try {
                        app.library.load()
                        val book = app.library.books.value.firstOrNull { !it.needsRelink && it.lastPlayedAt > 0 }
                            ?: throw IllegalStateException("Nessun ascolto da riprendere.")
                        player.setPlaybackSpeed(book.speed)
                        val start = book.chapterPlaybackStart()
                        future.set(MediaSession.MediaItemsWithStartPosition(book.mediaItems(), start.itemIndex, start.positionMs))
                    } catch (e: Exception) { future.setException(e) }
                }
                return future
            }
        }).build()
        handler.post(tick)
    }

    @androidx.annotation.RequiresApi(30)
    private class PlatformMediaSourceFactory(context: android.content.Context) : MediaSource.Factory {
        private val delegate = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(context), MediaParserExtractorAdapter.Factory()
        )

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val source = delegate.createMediaSource(mediaItem)
            val clipping = mediaItem.clippingConfiguration
            return if (clipping == MediaItem.ClippingConfiguration.UNSET) source
                else ClippingMediaSource.Builder(source).setClippingConfiguration(clipping).build()
        }
        override fun getSupportedTypes(): IntArray = delegate.supportedTypes
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(provider); return this
        }
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(policy); return this
        }
    }
    private fun save(finished: Boolean = false) {
        if (!::player.isInitialized) return
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
        val id = extras.getString("bookId") ?: return
        val index = extras.getInt("trackIndex")
        val position = (extras.getLong("chapterStartMs", 0) + player.currentPosition).coerceAtLeast(0)
        val speed = player.playbackParameters.speed
        app.scope.launch { app.library.savePosition(id, index, position, speed, finished) }
    }
    private fun configureTimer(minutes: Int) {
        stopTimer(false)
        if (minutes == -1) {
            chapterTrack = player.currentMediaItemIndex
            chapterEnd = player.duration.takeIf { it > 0 } ?: C.TIME_UNSET
            player.pauseAtEndOfMediaItems = true
            PlaybackSignals.timer.value = "Fine capitolo"
        } else if (minutes in 1..180) {
            deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            PlaybackSignals.timer.value = "$minutes minuti"
        }
        updateMediaButtons()
    }
    private fun finishChapterTimer() {
        val itemIndex = chapterTrack
        val endPosition = chapterEnd
        deadline = 0; chapterEnd = -1; chapterTrack = -1
        PlaybackSignals.timer.value = ""
        if (::player.isInitialized) {
            player.pauseAtEndOfMediaItems = false
            player.pause()
            if (itemIndex >= 0 && endPosition > 0) {
                player.seekTo(itemIndex, (endPosition - 1).coerceAtLeast(0))
            }
        }
        updateMediaButtons()
    }
    private fun stopTimer(pause: Boolean) {
        deadline = 0; chapterEnd = -1; chapterTrack = -1
        PlaybackSignals.timer.value = ""
        if (::player.isInitialized) { player.pauseAtEndOfMediaItems = false; if (pause) player.pause() }
        updateMediaButtons()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        save(); stopTimer(false)
        session?.release(); session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
