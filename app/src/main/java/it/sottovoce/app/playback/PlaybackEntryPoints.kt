package it.sottovoce.app.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.toArgb
import it.sottovoce.app.data.AppTheme
import it.sottovoce.app.data.Book
import it.sottovoce.app.data.BookChapter
import it.sottovoce.app.data.label
import it.sottovoce.app.data.listeningTime
import it.sottovoce.app.data.timeLabel
import it.sottovoce.app.ui.dur
import it.sottovoce.app.ui.formatSpeed
import it.sottovoce.app.ui.svPalette
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import it.sottovoce.app.data.ThemeSettings
import it.sottovoce.app.data.chapterPlaybackStart
import it.sottovoce.app.data.currentChapter
import kotlinx.coroutines.launch

@UnstableApi
private object ExternalPlaybackActions {
    const val TOGGLE = "it.sottovoce.widget.TOGGLE"
    const val BACK = "it.sottovoce.widget.BACK"
    const val FORWARD = "it.sottovoce.widget.FORWARD"
    const val TIMER = "it.sottovoce.widget.TIMER"
    const val STATUS = "it.sottovoce.widget.STATUS"
    val ALL = setOf(TOGGLE, BACK, FORWARD, TIMER)

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
                        BACK -> seekBy(controller, -app.getSharedPreferences("preferences", Context.MODE_PRIVATE).getInt("skipBack", 15) * 1000L)
                        FORWARD -> seekBy(controller, app.getSharedPreferences("preferences", Context.MODE_PRIVATE).getInt("skipForward", 30) * 1000L)
                        TIMER -> controller.sendCustomCommand(SessionCommand(PlaybackSignals.TOGGLE_TIMER_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
                    }
                }
                if (action == TOGGLE && controller.playWhenReady && !controller.isPlaying) {
                    kotlinx.coroutines.withTimeoutOrNull(3000) {
                        while (!controller.isPlaying && controller.playWhenReady && controller.playerError == null) kotlinx.coroutines.delay(50)
                    }
                }
                val playing = controller.isPlaying
                val extras = controller.currentMediaItem?.mediaMetadata?.extras
                WidgetUpdater.update(app, extras?.getString("bookId"), extras?.getInt("trackIndex"),
                    extras?.let { (it.getLong("chapterStartMs", 0) + controller.currentPosition).coerceAtLeast(0) }, playing)
                finished(playing)
                MediaController.releaseFuture(future)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    /** Skips within the playlist of chapter clips, crossing into the neighbouring chapter when needed. */
    private fun seekBy(controller: MediaController, deltaMs: Long) {
        if (controller.currentMediaItem == null) return
        val target = controller.currentPosition + deltaMs
        val duration = controller.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: Long.MAX_VALUE
        when {
            target < 0 && controller.hasPreviousMediaItem() -> {
                val previous = controller.previousMediaItemIndex
                val length = controller.currentTimeline.getWindow(previous, androidx.media3.common.Timeline.Window()).durationMs
                controller.seekTo(previous, if (length > 0) (length + target).coerceAtLeast(0) else 0)
            }
            target >= duration && controller.hasNextMediaItem() -> controller.seekTo(controller.nextMediaItemIndex, target - duration)
            else -> controller.seekTo(target.coerceIn(0, duration))
        }
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

/** The 5×2 widget. Its class name predates the other formats and stays, so placed widgets keep working. */
@UnstableApi
open class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as SottovoceApp
        app.scope.launch {
            runCatching { app.library.load() }
            WidgetUpdater.update(app, force = true) { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.update(context, force = true)
    }

    override fun onDeleted(context: Context, ids: IntArray) = WidgetUpdater.forget(ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in ExternalPlaybackActions.ALL) return
        val pending = goAsync()
        ExternalPlaybackActions.run(context, requireNotNull(intent.action)) { pending.finish() }
    }
}

/** The 2×3 vertical mini player. */
@UnstableApi
class PlaybackWidgetTallProvider : PlaybackWidgetProvider()

/** The 2×2: the cover is the widget. */
@UnstableApi
class PlaybackWidgetSquareProvider : PlaybackWidgetProvider()

@UnstableApi
internal enum class WidgetFormat(val provider: Class<out AppWidgetProvider>, val layout: Int, val width: Float, val height: Float) {
    WIDE(PlaybackWidgetProvider::class.java, R.layout.widget_wide, 408f, 156f),
    TALL(PlaybackWidgetTallProvider::class.java, R.layout.widget_tall, 156f, 240f),
    SQUARE(PlaybackWidgetSquareProvider::class.java, R.layout.widget_square, 156f, 156f),
}

/** Everything a widget shows, computed once per update. */
internal data class WidgetModel(
    val book: Book, val empty: Boolean, val playing: Boolean, val theme: AppTheme, val chapter: BookChapter?,
    val chapterProgress: Float, val chapterLeftMs: Long, val chapterElapsedMs: Long, val bookLeftMs: Long,
    val skipBack: Int, val skipForward: Int,
) {
    val speedLabel: String get() = formatSpeed(book.speed)
    val leftShort: String get() = "−${maxOf(1L, Math.round(chapterLeftMs / 60_000.0))} min"
}

@UnstableApi
object WidgetUpdater {
    private class Request(val bookId: String?, val trackIndex: Int?, val position: Long?, val playing: Boolean, val force: Boolean,
        val done: (() -> Unit)?)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sottovoce-widget").apply { isDaemon = true } }
    private val pending = AtomicReference<Request?>(null)
    /** Per widget: what the last full update drew, and what the last update of any kind showed. */
    private val drawn = ConcurrentHashMap<Int, String>()
    private val shown = ConcurrentHashMap<Int, String>()

    /**
     * Redraws every placed widget off the main thread; when calls pile up only the
     * latest is drawn. Progress-only changes are sent as partial updates without the cover.
     */
    fun update(context: Context, bookId: String? = null, trackIndex: Int? = null, position: Long? = null, playing: Boolean = false,
        force: Boolean = false, done: (() -> Unit)? = null) {
        val app = context.applicationContext as SottovoceApp
        pending.getAndSet(Request(bookId, trackIndex, position, playing, force, done))?.done?.invoke()
        worker.execute {
            val request = pending.getAndSet(null) ?: return@execute
            try { runCatching { render(app, request) }.onFailure { android.util.Log.w("Sottovoce", "Widget non aggiornato", it) } }
            finally { request.done?.invoke() }
        }
    }

    fun forget(ids: IntArray) = ids.forEach { drawn.remove(it); shown.remove(it) }

    private fun render(app: SottovoceApp, request: Request) {
        val manager = AppWidgetManager.getInstance(app)
        val targets = WidgetFormat.entries.flatMap { f -> manager.getAppWidgetIds(ComponentName(app, f.provider)).map { f to it } }
        if (targets.isEmpty()) return
        val model = model(app, request.bookId, request.trackIndex, request.position, request.playing)
        val art = WidgetArt(app)
        val phase = (System.currentTimeMillis() / 1000).toInt()
        targets.forEach { (format, id) ->
            val options = manager.getAppWidgetOptions(id)
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: format.width
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: format.height
            val still = listOf(format, w, h, model.theme, model.book.id, model.book.title, model.book.author, model.book.coverPath,
                model.book.speed, model.skipBack, model.skipForward, model.empty).joinToString("|")
            val moving = listOf(model.playing, model.chapter?.ordinal, (model.chapterProgress * 200).toInt(), model.chapterLeftMs / 1000,
                model.chapterElapsedMs / 1000).joinToString("|")
            if (!request.force && shown[id] == "$still#$moving") return@forEach
            val full = request.force || drawn[id] != still
            val views = draw(app, art, format, model, w, h, full, phase)
            if (full) { manager.updateAppWidget(id, views); drawn[id] = still } else manager.partiallyUpdateAppWidget(id, views)
            shown[id] = "$still#$moving"
        }
    }

    /** The three widgets at their design sizes, for tests and screenshots. */
    internal fun previews(context: Context, bookId: String?, playing: Boolean): List<Pair<String, RemoteViews>> {
        val app = context.applicationContext as SottovoceApp
        val model = model(app, bookId, null, null, playing)
        val art = WidgetArt(app)
        return WidgetFormat.entries.map { f -> f.name.lowercase() to draw(app, art, f, model, f.width, f.height, true, 3) }
    }

    private fun model(app: SottovoceApp, bookId: String?, trackIndex: Int?, position: Long?, playing: Boolean): WidgetModel {
        val prefs = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        val systemDark = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val books = app.library.books.value
        val source = books.firstOrNull { it.id == bookId } ?: books.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }
            ?: books.firstOrNull()
        val book = source?.copy(trackIndex = trackIndex ?: source.trackIndex, positionMs = position ?: source.positionMs)
        val chapter = book?.currentChapter()
        return WidgetModel(
            book = book ?: Book(id = "sottovoce", title = "Sottovoce", tracks = emptyList()), empty = book == null,
            playing = playing, theme = ThemeSettings.read(prefs).resolve(systemDark), chapter = chapter,
            chapterProgress = when { book == null -> 0f; book.completed -> 1f; else -> chapter?.progress(book.positionMs) ?: book.progress },
            chapterLeftMs = if (book == null) 0 else listeningTime(chapter?.remainingMs(book.positionMs) ?: (book.durationMs - book.playedMs), book.speed),
            chapterElapsedMs = if (book == null) 0 else chapter?.elapsedMs(book.positionMs) ?: book.playedMs,
            bookLeftMs = if (book == null) 0 else listeningTime(book.durationMs - book.playedMs, book.speed),
            skipBack = prefs.getInt("skipBack", 15), skipForward = prefs.getInt("skipForward", 30),
        )
    }

    private fun draw(context: Context, art: WidgetArt, format: WidgetFormat, model: WidgetModel, w: Float, h: Float, full: Boolean,
        phase: Int): RemoteViews = RemoteViews(context.packageName, format.layout).also { views ->
        when (format) {
            WidgetFormat.WIDE -> wide(context, art, views, model, w, h, full, phase)
            WidgetFormat.TALL -> tall(context, art, views, model, w, h, full, phase)
            WidgetFormat.SQUARE -> square(context, art, views, model, w, h, full, phase)
        }
    }

    private fun common(context: Context, art: WidgetArt, views: RemoteViews, m: WidgetModel, playColors: Pair<Int, Int>, playSize: Float,
        iconSize: Float, skipSize: Float?, full: Boolean, ring: Boolean = false) {
        views.setImageViewBitmap(R.id.widget_play, art.playButton(playSize, playColors.first, playColors.second, m.playing, iconSize,
            if (ring) m.chapterProgress else null))
        views.setContentDescription(R.id.widget_play, if (m.playing) "Pausa" else "Riproduci")
        if (!full) return
        val ink = svPalette(m.theme).ink.toArgb()
        if (skipSize != null) {
            views.setImageViewBitmap(R.id.widget_back, art.skipIcon(m.skipBack, false, ink, skipSize))
            views.setImageViewBitmap(R.id.widget_forward, art.skipIcon(m.skipForward, true, ink, skipSize))
            views.setContentDescription(R.id.widget_back, "Indietro di ${m.skipBack} secondi")
            views.setContentDescription(R.id.widget_forward, "Avanti di ${m.skipForward} secondi")
            views.setOnClickPendingIntent(R.id.widget_back, actionIntent(context, ExternalPlaybackActions.BACK, 21))
            views.setOnClickPendingIntent(R.id.widget_forward, actionIntent(context, ExternalPlaybackActions.FORWARD, 24))
        }
        views.setContentDescription(R.id.widget_cover, "Copertina di ${m.book.title}")
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        views.setOnClickPendingIntent(R.id.widget_play, actionIntent(context, ExternalPlaybackActions.TOGGLE, 22))
    }

    private fun wide(context: Context, art: WidgetArt, views: RemoteViews, m: WidgetModel, w: Float, h: Float, full: Boolean, phase: Int) {
        val p = svPalette(m.theme)
        val ink = p.ink.toArgb(); val ink2 = p.ink2.toArgb(); val accent = p.accent.toArgb()
        val cover = (h - 24f).coerceIn(60f, 200f)
        val column = (w - 24f - cover - 14f).coerceAtLeast(80f)
        if (full) {
            views.setInt(R.id.widget_bg, "setColorFilter", p.bg.toArgb())
            views.setImageViewBitmap(R.id.widget_cover, art.cover(m.book, art.colors(m.book, m.theme), m.theme, cover, cover, 14f,
                8f, 22f * cover / 132f))
            listOf(R.id.widget_eyebrow, R.id.widget_chapter, R.id.widget_elapsed, R.id.widget_left).forEach { views.setTextColor(it, ink2) }
        }
        views.setImageViewBitmap(R.id.widget_eq, art.equalizer(accent, m.playing, phase, 9f))
        views.setTextViewText(R.id.widget_eyebrow, if (m.empty) "Sottovoce" else "${if (m.playing) "In ascolto" else "In pausa"} · ${m.speedLabel}")
        views.setImageViewBitmap(R.id.widget_title, art.displayLine(m.chapter?.title ?: if (m.empty) "Nessun libro" else m.book.title,
            m.theme, 21f, ink, column - 56f, 24f))
        views.setTextViewText(R.id.widget_chapter, when {
            m.empty -> "Apri la libreria per iniziare"
            m.chapter != null -> "Capitolo ${m.chapter.ordinal} di ${m.chapter.total} · ${dur(m.bookLeftMs)} rimasti nel libro"
            else -> "${dur(m.bookLeftMs)} rimasti nel libro"
        })
        views.setImageViewBitmap(R.id.widget_wave, art.waveform(40, (m.chapter?.ordinal ?: 0) + m.book.id.length, m.chapterProgress,
            ink, accent, column, 26f, if (m.playing) phase else 0))
        views.setTextViewText(R.id.widget_elapsed, timeLabel(m.chapterElapsedMs))
        views.setTextViewText(R.id.widget_left, "−" + timeLabel(m.chapterLeftMs))
        common(context, art, views, m, accent to p.onAccent.toArgb(), 46f, 16f, 20f, full)
    }

    private fun tall(context: Context, art: WidgetArt, views: RemoteViews, m: WidgetModel, w: Float, h: Float, full: Boolean, phase: Int) {
        val p = svPalette(m.theme)
        val ink = p.ink.toArgb(); val accent = p.accent.toArgb()
        val inner = (w - 24f).coerceAtLeast(60f)
        if (full) {
            views.setInt(R.id.widget_bg, "setColorFilter", p.bg.toArgb())
            views.setImageViewBitmap(R.id.widget_cover, art.cover(m.book, art.colors(m.book, m.theme), m.theme, inner,
                (h - 135f).coerceIn(48f, 260f), 14f, 8f, 22f, topRight = if (m.empty) null else m.speedLabel))
            views.setTextColor(R.id.widget_chapter, ink)
            views.setTextColor(R.id.widget_left, p.ink2.toArgb())
        }
        views.setTextViewText(R.id.widget_chapter, when {
            m.empty -> "Apri la libreria"
            m.chapter == null -> m.book.title
            m.chapter.label() == m.chapter.title -> m.chapter.title
            else -> "${m.chapter.ordinal} · ${m.chapter.title}"
        })
        views.setTextViewText(R.id.widget_left, if (m.empty) "" else m.leftShort)
        views.setImageViewBitmap(R.id.widget_wave, art.waveform(26, (m.chapter?.ordinal ?: 0) + m.book.id.length, m.chapterProgress,
            ink, accent, inner, 24f, if (m.playing) phase else 0))
        common(context, art, views, m, accent to p.onAccent.toArgb(), 48f, 16f, 22f, full)
    }

    private fun square(context: Context, art: WidgetArt, views: RemoteViews, m: WidgetModel, w: Float, h: Float, full: Boolean, phase: Int) {
        val colors = art.colors(m.book, m.theme)
        val c1 = colors.c1.toArgb(); val c2 = colors.c2.toArgb()
        if (full) {
            views.setImageViewBitmap(R.id.widget_cover, art.square(m.book, colors, m.theme, w, h))
            views.setTextColor(R.id.widget_chapter, c2)
            views.setTextColor(R.id.widget_left, c2)
        }
        views.setImageViewBitmap(R.id.widget_eq, art.equalizer(c2, m.playing, phase, 10f))
        views.setTextViewText(R.id.widget_chapter, m.chapter?.let { "Cap. ${it.ordinal}" } ?: if (m.empty) "Apri la libreria" else "")
        views.setTextViewText(R.id.widget_left, if (m.empty) "" else m.leftShort)
        common(context, art, views, m, c2 to c1, 44f, 14f, null, full, ring = true)
    }

    private fun actionIntent(context: Context, action: String, request: Int) = PendingIntent.getBroadcast(
        context, request, Intent(context, PlaybackWidgetProvider::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
