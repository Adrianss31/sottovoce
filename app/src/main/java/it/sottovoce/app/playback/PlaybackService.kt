package it.sottovoce.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import it.sottovoce.app.MainActivity
import it.sottovoce.app.SottovoceApp
import it.sottovoce.app.data.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

object PlaybackSignals {
    val error = MutableStateFlow<String?>(null)
    val timer = MutableStateFlow("")
    const val TIMER_COMMAND = "it.sottovoce.SET_TIMER"
}

fun Book.mediaItems(): List<MediaItem> = tracks.mapIndexed { index, track ->
    val extras = Bundle().apply { putString("bookId", id); putInt("trackIndex", index) }
    MediaItem.Builder().setMediaId("$id/${track.id}").setUri(track.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(author)
            .setSubtitle(track.name).setAlbumTitle(title).setExtras(extras)
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
    private val tick = object : Runnable {
        override fun run() {
            if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) stopTimer(pause = true)
            if (chapterEnd >= 0 && (player.currentMediaItemIndex != chapterTrack || player.currentPosition >= chapterEnd)) stopTimer(pause = true)
            if (++ticks % 12 == 0 && player.isPlaying) save()
            handler.postDelayed(this, 250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("preferences", MODE_PRIVATE)
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(prefs.getInt("skipBack", 15) * 1000L)
            .setSeekForwardIncrementMs(prefs.getInt("skipForward", 30) * 1000L)
            .build().apply {
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
                        if (chapterTrack >= 0 && player.currentMediaItemIndex != chapterTrack) stopTimer(pause = true)
                        save()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && chapterTrack >= 0) stopTimer(false)
                        save()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        PlaybackSignals.error.value = "Impossibile leggere questo file. Controlla che sia presente, accessibile e in un formato supportato. Puoi ricollegarlo dalla scheda del libro."
                    }
                })
            }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val base = super.onConnect(session, controller)
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(base.availableSessionCommands.buildUpon()
                        .add(SessionCommand(PlaybackSignals.TIMER_COMMAND, Bundle.EMPTY))
                        .add(SessionCommand("it.sottovoce.STOP_AND_SAVE", Bundle.EMPTY)).build()).build()
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                val known = app.library.books.value.flatMap { it.mediaItems() }.associateBy { it.mediaId }
                val resolved = mediaItems.mapNotNull { request ->
                    known[request.mediaId]?.takeIf { item -> app.library.isSafeAudioUri(item.localConfiguration?.uri.toString()) }
                }.toMutableList()
                return Futures.immediateFuture(resolved)
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                if (command.customAction == "it.sottovoce.STOP_AND_SAVE") {
                    val future = SettableFuture.create<SessionResult>()
                    val extras = player.currentMediaItem?.mediaMetadata?.extras
                    val id = extras?.getString("bookId")
                    val index = extras?.getInt("trackIndex") ?: 0
                    val position = player.currentPosition
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
                        future.set(MediaSession.MediaItemsWithStartPosition(book.mediaItems(), book.trackIndex, book.positionMs))
                    } catch (e: Exception) { future.setException(e) }
                }
                return future
            }
        }).build()
        handler.post(tick)
    }
    private fun save(finished: Boolean = false) {
        if (!::player.isInitialized) return
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
        val id = extras.getString("bookId") ?: return
        val index = extras.getInt("trackIndex")
        val position = player.currentPosition.coerceAtLeast(0)
        val speed = player.playbackParameters.speed
        app.scope.launch { app.library.savePosition(id, index, position, speed, finished) }
    }
    private fun configureTimer(minutes: Int) {
        stopTimer(false)
        if (minutes == -1) {
            val id = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
            val book = app.library.books.value.find { it.id == id } ?: return
            chapterTrack = player.currentMediaItemIndex
            val track = book.tracks.getOrNull(chapterTrack) ?: return
            chapterEnd = track.chapters.firstOrNull { it.startMs > player.currentPosition + 500 }?.startMs ?: -1L
            player.pauseAtEndOfMediaItems = chapterEnd < 0
            PlaybackSignals.timer.value = "Fine capitolo"
        } else if (minutes in 1..180) {
            deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            PlaybackSignals.timer.value = "$minutes minuti"
        }
    }
    private fun stopTimer(pause: Boolean) {
        deadline = 0; chapterEnd = -1; chapterTrack = -1
        PlaybackSignals.timer.value = ""
        if (::player.isInitialized) { player.pauseAtEndOfMediaItems = false; if (pause) player.pause() }
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
