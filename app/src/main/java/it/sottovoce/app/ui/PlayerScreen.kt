@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package it.sottovoce.app.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.data.*

@UnstableApi
@Composable
internal fun PlayerScreen(vm: LibraryViewModel, book: Book, bookmarks: List<Bookmark>, isActive: Boolean,
    timerLabel: String, timerRemaining: Long, riseClock: () -> Float, heroHidden: Boolean,
    tab: String, onTab: (String) -> Unit, onClose: () -> Unit, onPlay: (Int?, Long?) -> Unit,
    onSheet: (Sheet) -> Unit, onRelink: () -> Unit) {
    val target = rememberBookColors(book)
    val c1 by animateColorAsState(target.c1, tween(SvMotion.DurationColor), label = "c1")
    val c2 by animateColorAsState(target.c2, tween(SvMotion.DurationColor), label = "c2")
    val c3 by animateColorAsState(target.c3, tween(SvMotion.DurationColor), label = "c3")
    val colors = target.copy(c1 = c1, c2 = c2, c3 = c3)
    val density = LocalDensity.current
    val origins = LocalCoverOrigins.current
    val now = vm.now
    val playing = isActive && now.playing
    val live = if (isActive) book.live(now) else book
    val timeline = remember(book.tracks) { book.chapterTimeline() }
    val chapter = live.currentChapter()
    val missing = book.needsRelink || book.tracks.any { !vm.library.isSafeAudioUri(it.uri) }
    val listState = rememberLazyListState()
    val scrolledDp by remember { derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) 1000f else listState.firstVisibleItemScrollOffset / density.density
    } }
    val timerOn = isActive && timerLabel.isNotEmpty()

    fun seekTo(track: Int, position: Long) {
        if (isActive) vm.playBook(book, track, position) else onPlay(track, position)
    }
    fun skip(seconds: Int) {
        if (isActive) vm.skip(seconds) else {
            val (t, p) = live.positionAfterSkip(live.trackIndex, live.positionMs, seconds * 1000L)
            onPlay(t, p)
        }
    }
    fun rise(index: Int) = Modifier.graphicsLayer {
        val t = ((riseClock() - 180f - 55f * index) / 620f).coerceIn(0f, 1f)
        val e = SvMotion.Emphasized.transform(t)
        alpha = e
        translationY = (1f - e) * 36.dp.toPx()
    }

    Box(Modifier.fillMaxSize().background(c1)) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-260).dp).size(600.dp)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(50.dp) else Modifier)
            .clip(CircleShape).background(c3.copy(alpha = .3f)))
        LazyColumn(Modifier.fillMaxSize().testTag("book_detail"), state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp)) {
            item(key = "hero") {
                BoxWithConstraints(Modifier.fillMaxWidth().then(rise(0)), contentAlignment = Alignment.Center) {
                    val size = minOf(300.dp, maxWidth)
                    BookCover(book, colors, Modifier.size(size).testTag("detail_cover")
                        .graphicsLayer { alpha = if (heroHidden) 0f else 1f }
                        .coverShadow(26.dp, 24.dp)
                        .onGloballyPositioned { origins.bounds[HeroKey] = Rect(it.positionInRoot(), it.size.toSize()) },
                        radius = 26.dp)
                    DisposableEffect(Unit) { onDispose { origins.bounds.remove(HeroKey) } }
                }
            }
            if (missing) item(key = "missing") {
                Row(Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .24f))
                    .padding(16.dp).then(rise(1)), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(24.dp), tint = c2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("File originali non trovati", style = SvType.Label, color = c2)
                        Text("Spostati o eliminati. Ricollegali per non perdere il punto.", style = SvType.MetaSmall, color = c2.copy(alpha = .75f))
                    }
                    Spacer(Modifier.width(10.dp))
                    PillButton("Ricollega", c2, c1, height = 40.dp, onClick = onRelink)
                }
            }
            item(key = "title") {
                Row(Modifier.padding(top = 26.dp).fillMaxWidth().then(rise(2)), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = SvType.display(46.sp, .88f), color = c2)
                        Spacer(Modifier.height(10.dp))
                        Text(buildString {
                            append(book.author.ifBlank { "Autore non indicato" })
                            if (book.narrator.isNotBlank()) append(" · letto da ").append(book.narrator)
                            if (book.series.isNotBlank()) append(" · ").append(book.series).append(book.seriesPosition?.let { " $it" } ?: "")
                        }, style = SvType.BodySmall, color = c2.copy(alpha = .72f))
                    }
                    Spacer(Modifier.width(12.dp))
                    IconCircle(Icons.Rounded.BookmarkBorder, "Aggiungi segnalibro", 52.dp, c2, border = c2.copy(alpha = .35f),
                        enabled = !missing) { onSheet(Sheet.MARK) }
                }
            }
            item(key = "scrubber") {
                Column(Modifier.padding(top = 26.dp).then(rise(3))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(chapter?.let { "${it.ordinal}. ${it.title}" } ?: "Audiolibro", c2, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Eyebrow("di ${timeline.size}", c2.copy(alpha = .6f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Scrubber(live, chapter, c2, enabled = !missing) { track, position -> seekTo(track, position) }
                }
            }
            item(key = "transport") {
                Row(Modifier.padding(top = 18.dp).fillMaxWidth().then(rise(4)), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Rounded.SkipPrevious, "Capitolo precedente", 50.dp, c2, enabled = !missing) {
                        val c = chapter ?: return@IconCircle
                        val target = if (c.elapsedMs(live.positionMs) > 5_000) c else timeline.getOrNull(c.ordinal - 2) ?: c
                        seekTo(target.trackIndex, target.startMs)
                    }
                    IconCircle(skipBackIcon(vm.skipBack), "Indietro ${vm.skipBack} secondi", 58.dp, c2, border = c2.copy(alpha = .35f),
                        pressedRotation = -45f, enabled = !missing) { skip(-vm.skipBack) }
                    PlayMorphButton(playing, 92.dp, c2, c1, playingRadius = 30.dp, rotate = true, modifier = Modifier.testTag("play_pause")) {
                        when { missing -> onRelink(); isActive -> vm.togglePlay(); else -> onPlay(null, null) }
                    }
                    IconCircle(skipForwardIcon(vm.skipForward), "Avanti ${vm.skipForward} secondi", 58.dp, c2, border = c2.copy(alpha = .35f),
                        pressedRotation = 45f, enabled = !missing) { skip(vm.skipForward) }
                    IconCircle(Icons.Rounded.SkipNext, "Capitolo successivo", 50.dp, c2,
                        enabled = !missing && chapter != null && chapter.ordinal < timeline.size) {
                        val next = chapter?.let { timeline.getOrNull(it.ordinal) } ?: return@IconCircle
                        seekTo(next.trackIndex, next.startMs)
                    }
                }
            }
            item(key = "pills") {
                Row(Modifier.padding(top = 22.dp).fillMaxWidth().then(rise(5)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("${formatSpeed(live.speed)} velocità", c2.copy(alpha = .12f), c2, Modifier.weight(1f).testTag("speed_button"),
                        height = 54.dp, icon = Icons.Rounded.Speed) { onSheet(Sheet.SPEED) }
                    PillButton(if (timerOn) timerLabel else "Timer", if (timerOn) c2 else c2.copy(alpha = .12f), if (timerOn) c1 else c2,
                        Modifier.weight(1f).testTag("timer_button"), height = 54.dp, icon = Icons.Rounded.Bedtime) { onSheet(Sheet.SLEEP) }
                }
            }
            item(key = "map") {
                Column(Modifier.padding(top = 26.dp).then(rise(6))) {
                    val fills = timeline.map { c -> when (live.chapterStatus(c)) {
                        ChapterStatus.COMPLETED -> 1f; ChapterStatus.CURRENT -> c.progress(live.positionMs); ChapterStatus.UPCOMING -> 0f } }
                    ChapterMap(timeline.map { it.durationMs }, fills, c2, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("${(live.progress * 100).toInt()}% del libro", style = SvType.MetaSmall, color = c2.copy(alpha = .75f))
                        Spacer(Modifier.weight(1f))
                        Text("${dur(listeningTime(book.durationMs - live.playedMs, live.speed))} rimasti", style = SvType.MetaSmall, color = c2.copy(alpha = .75f))
                    }
                }
            }
            item(key = "tabs") {
                SegmentedTabs(tab, timeline.size, bookmarks.size, c1, c2, Modifier.padding(top = 26.dp, bottom = 12.dp).then(rise(7)), onTab)
            }
            if (tab == "chapters") {
                if (book.tracks.size == 1 && book.tracks.first().chapters.isEmpty()) item(key = "no_chapters") {
                    Text("Nessun capitolo incorporato riconosciuto: ascolto come traccia unica.", Modifier.padding(bottom = 8.dp),
                        style = SvType.MetaSmall, color = c2.copy(alpha = .7f))
                }
                items(timeline, key = { "chapter:${it.ordinal}" }) { c ->
                    ChapterRow(live, c, playing && live.chapterStatus(c) == ChapterStatus.CURRENT, c1, c2, enabled = !missing) {
                        seekTo(c.trackIndex, c.startMs)
                    }
                }
            } else if (bookmarks.isEmpty()) item(key = "no_marks") {
                Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ancora nessun segnalibro", style = SvType.display(28.sp), color = c2)
                    Spacer(Modifier.height(8.dp))
                    Text("Tocca il segnalibro accanto al titolo per salvare questo punto.", style = SvType.Meta, color = c2.copy(alpha = .7f))
                }
            } else items(bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { mark ->
                BookmarkCard(book, mark, c2, enabled = !missing, onOpen = {
                    seekTo(mark.trackIndex, mark.positionMs); onTab("chapters"); vm.message = "Salto al segnalibro."
                }, onDelete = { vm.removeBookmark(mark.id) })
            }
        }
        // Top bar: collapse, mini player once the transport has scrolled away, management.
        val barAlpha = ((scrolledDp - 40f) / 80f).coerceIn(0f, 1f)
        val mini = scrolledDp > 330f
        val miniT by animateFloatAsState(if (mini) 1f else 0f, LocalMotionPolicy.current.emphasized(300), label = "mini lettore")
        Box(Modifier.fillMaxWidth().background(c1.copy(alpha = barAlpha)).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp)) {
            IconCircle(Icons.Rounded.ExpandMore, "Torna indietro", 44.dp, c2, Modifier.align(Alignment.CenterStart), onClick = onClose)
            if (miniT > 0f) Row(Modifier.align(Alignment.Center).padding(horizontal = 52.dp)
                .graphicsLayer { alpha = miniT; translationY = (1f - miniT) * 10.dp.toPx(); val s = .6f + .4f * miniT; scaleX = s; scaleY = s },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = SvType.Label, color = c2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter?.let { "Cap. ${it.ordinal} · −${timeLabel(it.remainingMs(live.positionMs))}" } ?: "", style = SvType.MonoSmall,
                        color = c2.copy(alpha = .7f), maxLines = 1)
                }
                PlayMorphButton(playing, 42.dp, c2, c1, playingRadius = 14.dp) {
                    when { missing -> onRelink(); isActive -> vm.togglePlay(); else -> onPlay(null, null) }
                }
            }
            IconCircle(Icons.Rounded.MoreHoriz, "Gestione del libro", 44.dp, c2, Modifier.align(Alignment.CenterEnd)) { onSheet(Sheet.MANAGE) }
        }
    }
}

@Composable
private fun Scrubber(book: Book, chapter: BookChapter?, ink: Color, enabled: Boolean, onSeek: (Int, Long) -> Unit) {
    val policy = LocalMotionPolicy.current
    var drag by remember(book.id, chapter?.ordinal) { mutableStateOf<Float?>(null) }
    val duration = chapter?.durationMs ?: 0L
    val start = chapter?.startMs ?: 0L
    val progress = drag ?: chapter?.progress(book.positionMs) ?: 0f
    val scale by animateFloatAsState(if (drag != null) 1.12f else 1f, policy.overshoot(420), label = "onda")
    val lineAlpha by animateFloatAsState(if (drag != null) .9f else 0f, tween(200), label = "testina")
    val elapsed = (duration * progress).toLong()
    fun commit(f: Float) { chapter?.let { onSeek(it.trackIndex, start + (duration * f.coerceIn(0f, 1f)).toLong().coerceAtMost((duration - 500).coerceAtLeast(0))) } }
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp).testTag("seek_slider")
            .semantics {
                stateDescription = "${timeLabel(elapsed)} di ${timeLabel(duration)}"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (enabled) setProgress { v -> commit(v); true }
            }
            .then(if (enabled && duration > 0) Modifier
                .pointerInput(chapter?.ordinal) { detectTapGestures { o -> commit(o.x / size.width) } }
                .pointerInput(chapter?.ordinal) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let(::commit); drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                } else Modifier)) {
            Waveform(48, (chapter?.ordinal ?: 0) + book.id.length, progress, ink, Modifier.fillMaxSize(), gap = 3.dp, radius = 3.dp, scaleY = scale)
            Box(Modifier.offset(x = maxWidth * progress - 1.dp).width(2.dp).fillMaxHeight().graphicsLayer { alpha = lineAlpha }.background(ink))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(timeLabel(elapsed), style = SvType.Mono, color = ink.copy(alpha = .8f))
            Spacer(Modifier.weight(1f))
            Text("−${timeLabel(duration - elapsed)}", style = SvType.Mono, color = ink.copy(alpha = .8f))
        }
    }
}

@Composable
private fun SegmentedTabs(tab: String, chapters: Int, marks: Int, c1: Color, c2: Color, modifier: Modifier, onTab: (String) -> Unit) {
    val policy = LocalMotionPolicy.current
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(c2.copy(alpha = .12f)).padding(4.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (tab == "chapters") 0.dp else half, policy.emphasized(SvMotion.DurationIndicator), label = "schede")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(CircleShape).background(c2))
        Row(Modifier.fillMaxSize()) {
            listOf("chapters" to "Capitoli $chapters", "bookmarks" to "Segnalibri $marks").forEach { (key, label) ->
                val selected by animateColorAsState(if (tab == key) c1 else c2, tween(300), label = "testo scheda")
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .motionClickable(pressedScale = .97f, onClickLabel = label, role = androidx.compose.ui.semantics.Role.Tab) { onTab(key) },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = SvType.Label, color = selected)
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(book: Book, chapter: BookChapter, playing: Boolean, c1: Color, c2: Color, enabled: Boolean, onPlay: () -> Unit) {
    val status = book.chapterStatus(chapter)
    val current = status == ChapterStatus.CURRENT && !book.completed
    val done = status == ChapterStatus.COMPLETED
    val progress = if (current) chapter.progress(book.positionMs) else 0f
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = if (current) 68.dp else 52.dp).clip(RoundedCornerShape(16.dp))
        .background(if (current) c2.copy(alpha = .12f) else Color.Transparent).testTag("chapter_${chapter.ordinal}")
        .motionClickable(enabled = enabled, pressedScale = .98f, onClickLabel = "Riproduci ${chapter.title}", onClick = onPlay)
        .graphicsLayer { alpha = if (done) .5f else 1f }) {
        if (current) Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(c2.copy(alpha = .08f)))
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Text(chapter.ordinal.toString().padStart(2, '0'), Modifier.width(30.dp), style = SvType.Mono, color = c2.copy(alpha = .7f))
            Column(Modifier.weight(1f)) {
                Text(chapter.title, style = SvType.Body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                    color = c2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (current) Text("${timeLabel(chapter.elapsedMs(book.positionMs))} di ${timeLabel(chapter.durationMs)} · " +
                    "${dur(listeningTime(chapter.remainingMs(book.positionMs), book.speed))} rimasti", style = SvType.MonoSmall, color = c2.copy(alpha = .7f))
            }
            Spacer(Modifier.width(10.dp))
            when {
                current -> Equalizer(playing, c2, Modifier.size(14.dp))
                done -> Icon(Icons.Rounded.Check, "Capitolo completato", Modifier.size(18.dp), tint = c2)
                else -> Text(dur(chapter.durationMs), style = SvType.MonoSmall, color = c2.copy(alpha = .7f))
            }
        }
    }
}

@Composable
private fun BookmarkCard(book: Book, mark: Bookmark, c2: Color, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val chapter = book.currentChapter(mark.trackIndex, mark.positionMs)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(c2.copy(alpha = .10f))
        .motionClickable(enabled = enabled, pressedScale = .98f, onClickLabel = "Riproduci dal segnalibro", onClick = onOpen)
        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Cap. ${chapter?.ordinal ?: (mark.trackIndex + 1)} · ${timeLabel((mark.positionMs - (chapter?.startMs ?: 0)).coerceAtLeast(0))}",
                style = SvType.MonoSmall, color = c2.copy(alpha = .7f))
            Spacer(Modifier.height(4.dp))
            Text(mark.note.ifBlank { "Segnalibro senza nota" }, style = SvType.Body, color = c2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        IconCircle(Icons.Rounded.Close, "Elimina segnalibro", 40.dp, c2.copy(alpha = .7f), onClick = onDelete)
    }
}
