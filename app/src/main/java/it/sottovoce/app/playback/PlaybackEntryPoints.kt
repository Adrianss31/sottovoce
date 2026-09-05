package it.sottovoce.app.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import it.sottovoce.app.MainActivity
import it.sottovoce.app.R
import it.sottovoce.app.SottovoceApp
import it.sottovoce.app.data.Book
import it.sottovoce.app.data.chapterPlaybackStart
import it.sottovoce.app.data.currentChapter
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
private object ExternalPlaybackActions {
    const val TOGGLE = "it.sottovoce.widget.TOGGLE"
    const val BACK = "it.sottovoce.widget.BACK"
    const val TIMER = "it.sottovoce.widget.TIMER"
    const val STATUS = "it.sottovoce.widget.STATUS"

    fun run(context: Context, action: String, finished: (Boolean) -> Unit = {}) {
        val app = context.applicationContext as SottovoceApp
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrElse { finished(false); return@addListener }
            app.scope.launch {
                runCatching {
                    app.library.load()
                    when (action) {
                        TOGGLE -> toggle(controller, app)
                        BACK -> controller.seekBack()
                        TIMER -> controller.sendCustomCommand(SessionCommand(PlaybackSignals.TOGGLE_TIMER_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
                    }
                }
                if (action == TOGGLE && controller.playWhenReady && !controller.isPlaying) {
                    kotlinx.coroutines.withTimeoutOrNull(3000) {
                        while (!controller.isPlaying && controller.playWhenReady && controller.playerError == null) kotlinx.coroutines.delay(50)
                    }
                }
                val playing = controller.isPlaying
                WidgetUpdater.update(app, playing = playing)
                finished(playing)
                MediaController.releaseFuture(future)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun toggle(controller: MediaController, app: SottovoceApp) {
        if (controller.isPlaying) return controller.pause()
        if (controller.currentMediaItem == null) {
            val book = app.library.books.value.firstOrNull { !it.needsRelink && it.lastPlayedAt > 0 && it.tracks.all { track -> app.library.isSafeAudioUri(track.uri) } }
                ?: app.library.books.value.firstOrNull { !it.needsRelink && it.tracks.all { track -> app.library.isSafeAudioUri(track.uri) } }
                ?: return
            val start = book.chapterPlaybackStart()
            controller.setMediaItems(book.mediaItems(), start.itemIndex, start.positionMs)
            controller.setPlaybackSpeed(book.speed)
            controller.prepare()
        } else if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        controller.play()
    }
}

@UnstableApi
class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as SottovoceApp
        app.scope.launch {
            runCatching { app.library.load(); WidgetUpdater.update(app) }
            pending.finish()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in setOf(ExternalPlaybackActions.TOGGLE, ExternalPlaybackActions.BACK, ExternalPlaybackActions.TIMER)) return
        val pending = goAsync()
        ExternalPlaybackActions.run(context, requireNotNull(intent.action)) { pending.finish() }
    }
}

@UnstableApi
object WidgetUpdater {
    fun update(context: Context, bookId: String? = null, trackIndex: Int? = null, position: Long? = null, playing: Boolean = false) {
        val app = context.applicationContext as SottovoceApp
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, PlaybackWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val source = app.library.books.value.firstOrNull { it.id == bookId }
            ?: app.library.books.value.firstOrNull { it.lastPlayedAt > 0 } ?: app.library.books.value.firstOrNull()
        val book = source?.copy(trackIndex = trackIndex ?: source.trackIndex, positionMs = position ?: source.positionMs)
        ids.forEach { manager.updateAppWidget(it, views(app, book, playing)) }
    }

    private fun views(context: Context, book: Book?, playing: Boolean): RemoteViews = RemoteViews(context.packageName, R.layout.widget_listening).apply {
        val theme = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getString("theme", "system")
        val dark = theme == "dark" || theme == "system" && (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground = android.graphics.Color.parseColor(if (dark) "#F3EFE5" else "#17231C")
        setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.parseColor(if (dark) "#292E29" else "#E7EDE2"))
        setTextColor(R.id.widget_title, foreground)
        setTextColor(R.id.widget_chapter, foreground)
        listOf(R.id.widget_back, R.id.widget_play, R.id.widget_timer).forEach { setInt(it, "setColorFilter", foreground) }
        val chapter = book?.currentChapter()
        setTextViewText(R.id.widget_title, book?.title ?: "Sottovoce")
        setTextViewText(R.id.widget_chapter, chapter?.title ?: if (book == null) "Apri la libreria" else "Pronto per l'ascolto")
        val progress = if (book == null) 0 else ((chapter?.progress(book.positionMs) ?: book.progress) * 1000).toInt()
        setProgressBar(R.id.widget_progress, 1000, progress, false)
        setImageViewResource(R.id.widget_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        val cover = book?.coverPath?.let(::widgetCover)
        if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher)
        setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        setOnClickPendingIntent(R.id.widget_back, actionIntent(context, ExternalPlaybackActions.BACK, 21))
        setOnClickPendingIntent(R.id.widget_play, actionIntent(context, ExternalPlaybackActions.TOGGLE, 22))
        setOnClickPendingIntent(R.id.widget_timer, actionIntent(context, ExternalPlaybackActions.TIMER, 23))
    }

    private fun actionIntent(context: Context, action: String, request: Int) = PendingIntent.getBroadcast(
        context, request, Intent(context, PlaybackWidgetProvider::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun widgetCover(path: String): Bitmap? = runCatching {
        if (!File(path).isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 600) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        Bitmap.createScaledBitmap(decoded, 124, 164, true).also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()
}

@UnstableApi
class PlaybackTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        ExternalPlaybackActions.run(this, ExternalPlaybackActions.STATUS, ::showState)
    }
    override fun onClick() {
        super.onClick()
        ExternalPlaybackActions.run(this, ExternalPlaybackActions.TOGGLE, ::showState)
    }
    private fun showState(playing: Boolean) {
        qsTile?.apply {
            state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (playing) "Sottovoce · Pausa" else "Sottovoce · Riproduci"
            updateTile()
        }
    }
}
