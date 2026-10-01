package it.sottovoce.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    val parts = remember(book.tracks, timeline) { book.chapterParts(timeline) }
    val long = timeline.size > LONG_BOOK_CHAPTERS
    val chapter = live.currentChapter()
    val currentIndex = if (live.completed) -1 else (chapter?.ordinal ?: 1) - 1
    val marked = remember(bookmarks, timeline) {
        bookmarks.mapNotNull { m -> timeline.lastOrNull { it.trackIndex == m.trackIndex && it.startMs <= m.positionMs }?.ordinal?.minus(1) }.toSet()
    }
    val missing = book.needsRelink || book.tracks.any { !vm.library.isSafeAudioUri(it.uri) }
    val listState = rememberLazyListState()
    val scrolledDp by remember { derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) 1000f else listState.firstVisibleItemScrollOffset / density.density
    } }
    val heroItemHeight = remember { mutableIntStateOf(0) }
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
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
    // Blocks below the cover rise into place, staggered; the cover itself never moves
    // so the flying cover lands exactly where it stays.
    fun rise(index: Int) = Modifier.graphicsLayer {
        val t = ((riseClock() - 180f - 55f * index) / 620f).coerceIn(0f, 1f)
        val e = SvMotion.Emphasized.transform(t)
        alpha = e
        translationY = (1f - e) * 36.dp.toPx()
    }

    Box(Modifier.fillMaxSize().background(c1)) {
        // Soft c3 glow behind the cover. It belongs to the scrolling content, so it
        // scrolls away with the cover instead of staying pinned behind the list.
        Canvas(Modifier.fillMaxSize()) {
            val scroll = when (listState.firstVisibleItemIndex) {
                0 -> listState.firstVisibleItemScrollOffset.toFloat()
                1 -> heroItemHeight.intValue + listState.firstVisibleItemScrollOffset.toFloat()
                else -> return@Canvas
            }
            val center = Offset(size.width / 2f, topPadding.toPx() - scroll + 40.dp.toPx())
            val radius = 400.dp.toPx()
            if (center.y + radius <= 0f) return@Canvas
            drawCircle(Brush.radialGradient(
                0f to c3.copy(alpha = .3f), .5f to c3.copy(alpha = .29f), .625f to c3.copy(alpha = .24f),
                .75f to c3.copy(alpha = .15f), .875f to c3.copy(alpha = .06f), 1f to c3.copy(alpha = 0f),
                center = center, radius = radius), radius, center)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("book_detail"), state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp, top = topPadding)) {
            item(key = "hero") {
                BoxWithConstraints(Modifier.fillMaxWidth().onSizeChanged { heroItemHeight.intValue = it.height },
                    contentAlignment = Alignment.Center) {
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
                Row(Modifier.padding(top = 22.dp).fillMaxWidth().clip(svRounded(22.dp)).background(Color.Black.copy(alpha = .24f))
                    .padding(16.dp).then(rise(0)), verticalAlignment = Alignment.CenterVertically) {
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
                Row(Modifier.padding(top = 28.dp).fillMaxWidth().then(rise(1)), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = SvType.display(46.sp, .88f), color = c2)
                        Spacer(Modifier.height(8.dp))
                        Text(buildString {
                            append(book.author.ifBlank { "Autore non indicato" })
                            if (book.narrator.isNotBlank()) append(" · letto da ").append(book.narrator)
                            if (book.series.isNotBlank()) append(" · ").append(book.series).append(book.seriesPosition?.let { " $it" } ?: "")
                        }, style = SvType.BodySmall, color = c2.copy(alpha = .72f))
                    }
                    Spacer(Modifier.width(14.dp))
                    IconCircle(Icons.Rounded.BookmarkBorder, "Aggiungi segnalibro", 52.dp, c2, border = c2.copy(alpha = .32f),
                        enabled = !missing) { onSheet(Sheet.MARK) }
                }
            }
            item(key = "scrubber") {
                Column(Modifier.padding(top = 28.dp).then(rise(2))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(chapter?.label() ?: "Audiolibro", c2, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Eyebrow("di ${timeline.size}", c2.copy(alpha = .6f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Scrubber(live, chapter, c2, playing, enabled = !missing) { track, position -> seekTo(track, position) }
                }
            }
            item(key = "transport") {
                Row(Modifier.padding(top = 18.dp).fillMaxWidth().then(rise(3)), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Rounded.SkipPrevious, "Capitolo precedente", 50.dp, c2, enabled = !missing) {
                        val c = chapter ?: return@IconCircle
                        val target = if (c.elapsedMs(live.positionMs) > 5_000) c else timeline.getOrNull(c.ordinal - 2) ?: c
                        seekTo(target.trackIndex, target.startMs)
                    }
                    IconCircle(skipBackIcon(vm.skipBack), "Indietro ${vm.skipBack} secondi", 58.dp, c2, border = c2.copy(alpha = .32f),
                        pressedRotation = -45f, enabled = !missing) { skip(-vm.skipBack) }
                    PlayMorphButton(playing, 92.dp, c2, c1, playingRadius = 30.dp, rotate = true, modifier = Modifier.testTag("play_pause")) {
                        when { missing -> onRelink(); isActive -> vm.togglePlay(); else -> onPlay(null, null) }
                    }
                    IconCircle(skipForwardIcon(vm.skipForward), "Avanti ${vm.skipForward} secondi", 58.dp, c2, border = c2.copy(alpha = .32f),
                        pressedRotation = 45f, enabled = !missing) { skip(vm.skipForward) }
                    IconCircle(Icons.Rounded.SkipNext, "Capitolo successivo", 50.dp, c2,
                        enabled = !missing && chapter != null && chapter.ordinal < timeline.size) {
                        val next = chapter?.let { timeline.getOrNull(it.ordinal) } ?: return@IconCircle
                        seekTo(next.trackIndex, next.startMs)
                    }
                }
            }
            item(key = "pills") {
                Row(Modifier.padding(top = 24.dp).fillMaxWidth().then(rise(4)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("${formatSpeed(live.speed)} velocità", c2.copy(alpha = .12f), c2, Modifier.weight(1f).testTag("speed_button"),
                        height = 54.dp, icon = Icons.Rounded.Speed) { onSheet(Sheet.SPEED) }
                    PillButton(if (timerOn) timerLabel else "Timer", if (timerOn) c2 else c2.copy(alpha = .12f), if (timerOn) c1 else c2,
                        Modifier.weight(1f).testTag("timer_button"), height = 54.dp, icon = Icons.Rounded.Bedtime) { onSheet(Sheet.SLEEP) }
                }
            }
            item(key = "map") {
                Column(Modifier.padding(top = 34.dp).then(rise(5))) {
                    // Long books show one segment per part rather than hundreds of slivers.
                    val (durations, fills) = if (long) parts.map { p ->
                        val slice = timeline.subList(p.first, p.last + 1)
                        val total = slice.sumOf { it.durationMs }
                        val played = slice.sumOf { c -> when (live.chapterStatus(c)) {
                            ChapterStatus.COMPLETED -> c.durationMs; ChapterStatus.CURRENT -> c.elapsedMs(live.positionMs); ChapterStatus.UPCOMING -> 0L } }
                        total to if (total > 0) played.toFloat() / total else 0f
                    }.unzip() else timeline.map { it.durationMs } to timeline.map { c -> when (live.chapterStatus(c)) {
                        ChapterStatus.COMPLETED -> 1f; ChapterStatus.CURRENT -> c.progress(live.positionMs); ChapterStatus.UPCOMING -> 0f } }
                    ChapterMap(durations, fills, c2, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("${(live.progress * 100).toInt()}% del libro", style = SvType.MetaSmall, color = c2.copy(alpha = .72f))
                        Spacer(Modifier.weight(1f))
                        Text("${dur(listeningTime(book.durationMs - live.playedMs, live.speed))} rimasti", style = SvType.MetaSmall, color = c2.copy(alpha = .72f))
                    }
                }
            }
            item(key = "tabs") {
                SegmentedTabs(tab, timeline.size, bookmarks.size, c2, Modifier.padding(top = 28.dp).then(rise(6)), onTab)
            }
            if (tab == "chapters") {
                if (book.tracks.size == 1 && book.tracks.first().chapters.isEmpty()) item(key = "no_chapters") {
                    Text("Nessun capitolo incorporato riconosciuto: ascolto come traccia unica.", Modifier.padding(top = 12.dp),
                        style = SvType.MetaSmall, color = c2.copy(alpha = .7f))
                }
                if (long) {
                    item(key = "around") {
                        SectionLine("Intorno a te", "${if (live.completed) timeline.size else currentIndex} di ${timeline.size} ascoltati",
                            c2, Modifier.padding(top = 24.dp, bottom = 12.dp), mono = true)
                    }
                    val from = (currentIndex - 1).coerceAtMost(timeline.size - 5).coerceAtLeast(0)
                    items(timeline.subList(from, minOf(from + 5, timeline.size)), key = { "chapter:${it.ordinal}" }) { c ->
                        ChapterRow(live, c, playing && live.chapterStatus(c) == ChapterStatus.CURRENT, c2, digits = 3, enabled = !missing) {
                            seekTo(c.trackIndex, c.startMs)
                        }
                    }
                    item(key = "chapter_map") {
                        Column(Modifier.padding(top = 30.dp)) {
                            SectionLine("Mappa dei capitoli", "Tocca o trascina", c2)
                            Spacer(Modifier.height(12.dp))
                            ChapterGridCard(live, timeline, parts, currentIndex, marked, playing, c1, c2, c3, enabled = !missing) { c ->
                                seekTo(c.trackIndex, c.startMs)
                                vm.message = "Riproduco il capitolo ${c.ordinal}"
                            }
                            Spacer(Modifier.height(10.dp))
                            PillButton("Cerca tra ${timeline.size} capitoli", Color.Transparent, c2, Modifier.fillMaxWidth().testTag("find_chapters"),
                                height = 56.dp, border = c2.copy(alpha = .28f), icon = Icons.Rounded.Search,
                                style = SvType.Label.copy(fontWeight = FontWeight.Medium)) { onSheet(Sheet.CHAPTERS) }
                        }
                    }
                } else {
                    item(key = "chapters_gap") { Spacer(Modifier.height(12.dp)) }
                    items(timeline, key = { "chapter:${it.ordinal}" }) { c ->
                        ChapterRow(live, c, playing && live.chapterStatus(c) == ChapterStatus.CURRENT, c2, digits = 2, enabled = !missing) {
                            seekTo(c.trackIndex, c.startMs)
                        }
                    }
                }
            } else if (bookmarks.isEmpty()) item(key = "no_marks") {
                Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ancora nessun segnalibro", style = SvType.display(24.sp), color = c2)
                    Spacer(Modifier.height(8.dp))
                    Text("Tocca il segnalibro accanto al titolo per salvare questo punto.", style = SvType.Meta, color = c2.copy(alpha = .7f))
                }
            } else {
                item(key = "marks_gap") { Spacer(Modifier.height(12.dp)) }
                items(bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { mark ->
                    BookmarkCard(book, mark, c2, enabled = !missing, onOpen = {
                        seekTo(mark.trackIndex, mark.positionMs); onTab("chapters"); vm.message = "Salto al segnalibro."
                    }, onDelete = { vm.removeBookmark(mark.id) })
                }
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
private fun SectionLine(title: String, detail: String, c2: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Bottom) {
        Eyebrow(title, c2.copy(alpha = .7f), Modifier.weight(1f))
        Text(detail, style = if (mono) SvType.MonoSmall else SvType.MetaSmall, color = c2.copy(alpha = .65f), maxLines = 1)
    }
}

@Composable
private fun Scrubber(book: Book, chapter: BookChapter?, ink: Color, playing: Boolean, enabled: Boolean, onSeek: (Int, Long) -> Unit) {
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
            Waveform(48, (chapter?.ordinal ?: 0) + book.id.length, progress, ink, Modifier.fillMaxSize(), gap = 3.dp, radius = 3.dp,
                scaleY = scale, playing = playing && drag == null)
            Box(Modifier.offset(x = maxWidth * progress - 1.dp).width(2.dp).fillMaxHeight().graphicsLayer { alpha = lineAlpha }.background(ink))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(timeLabel(elapsed), style = SvType.Mono, color = ink.copy(alpha = .72f))
            Spacer(Modifier.weight(1f))
            Text("−${timeLabel(duration - elapsed)}", style = SvType.Mono, color = ink.copy(alpha = .72f))
        }
    }
}

/** Chapters / bookmarks switch: a tinted track with a sliding tinted pill. */
@Composable
private fun SegmentedTabs(tab: String, chapters: Int, marks: Int, c2: Color, modifier: Modifier, onTab: (String) -> Unit) {
    val policy = LocalMotionPolicy.current
    BoxWithConstraints(modifier.fillMaxWidth().height(50.dp).clip(svRounded(26.dp)).background(c2.copy(alpha = .08f)).padding(4.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (tab == "chapters") 0.dp else half, policy.emphasized(SvMotion.DurationIndicator), label = "schede")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(svRounded(22.dp)).background(c2.copy(alpha = .16f)))
        Row(Modifier.fillMaxSize()) {
            listOf(Triple("chapters", "Capitoli", chapters), Triple("bookmarks", "Segnalibri", marks)).forEach { (key, label, count) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(svRounded(22.dp))
                    .motionClickable(pressedScale = .97f, onClickLabel = "$label $count", role = Role.Tab) { onTab(key) }
                    .semantics { selected = tab == key },
                    contentAlignment = Alignment.Center) {
                    Text(buildAnnotatedString {
                        append(label); append(" ")
                        withStyle(SpanStyle(color = c2.copy(alpha = .6f))) { append(count.toString()) }
                    }, style = SvType.Label.copy(fontWeight = FontWeight.Medium), color = c2)
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(book: Book, chapter: BookChapter, playing: Boolean, c2: Color, digits: Int, enabled: Boolean, onPlay: () -> Unit) {
    val status = book.chapterStatus(chapter)
    val current = status == ChapterStatus.CURRENT && !book.completed
    val done = status == ChapterStatus.COMPLETED
    val progress = if (current) chapter.progress(book.positionMs) else 0f
    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp).heightIn(min = if (current) 68.dp else 52.dp).clip(svRounded(20.dp))
        .background(if (current) c2.copy(alpha = .12f) else Color.Transparent).testTag("chapter_${chapter.ordinal}")
        .motionClickable(enabled = enabled, pressedScale = .98f, onClickLabel = "Riproduci ${chapter.title}", onClick = onPlay)
        .graphicsLayer { alpha = if (done) .5f else 1f }) {
        if (current) Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(c2.copy(alpha = .10f)))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Text(chapter.ordinal.toString().padStart(digits, '0'), Modifier.width(if (digits > 2) 36.dp else 30.dp), style = SvType.Mono, color = c2)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(chapter.title, style = SvType.Body.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                    color = c2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current) Text("${timeLabel(chapter.elapsedMs(book.positionMs))} di ${timeLabel(chapter.durationMs)} · " +
                    "${dur(listeningTime(chapter.remainingMs(book.positionMs), book.speed))} rimasti", style = SvType.MonoSmall, color = c2.copy(alpha = .65f))
            }
            Spacer(Modifier.width(14.dp))
            when {
                current -> Equalizer(playing, c2, Modifier.size(14.dp))
                done -> Icon(Icons.Rounded.Check, "Capitolo completato", Modifier.size(16.dp), tint = c2.copy(alpha = .65f))
                else -> Text(dur(chapter.durationMs), style = SvType.Mono, color = c2.copy(alpha = .65f))
            }
        }
    }
}

/**
 * Long books: every chapter as a cell of a 20-column grid, grouped by part. Tap or
 * drag across the grid to inspect a chapter; the header shows where it sits and
 * offers to play it.
 */
@Composable
private fun ChapterGridCard(book: Book, timeline: List<BookChapter>, parts: List<ChapterPart>, current: Int, marked: Set<Int>,
    playing: Boolean, c1: Color, c2: Color, c3: Color, enabled: Boolean, onPlay: (BookChapter) -> Unit) {
    val policy = LocalMotionPolicy.current
    val density = LocalDensity.current
    var picked by remember(book.id) { mutableStateOf<Int?>(null) }
    val sel = (picked ?: current.coerceAtLeast(0)).coerceIn(0, timeline.lastIndex)
    val chapter = timeline[sel]
    val isCurrent = sel == current
    val done = current < 0 || sel < current
    val part = parts.firstOrNull { sel in it.first..it.last }
    val startAt = remember(timeline, sel) { timeline.subList(0, sel).sumOf { it.durationMs } }
    val status = when {
        isCurrent -> "${if (playing) "In riproduzione" else "Sei qui"} · ${dur(listeningTime(chapter.remainingMs(book.positionMs), book.speed))} rimasti"
        done -> "Ascoltato · ${dur(chapter.durationMs)}"
        else -> "Da ascoltare · ${dur(chapter.durationMs)}"
    }
    val selScale = remember { Animatable(1.3f) }
    LaunchedEffect(sel) { selScale.snapTo(1f); selScale.animateTo(1.3f, policy.overshoot(300)) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(playing && policy.animationsEnabled) {
        if (playing && policy.animationsEnabled) while (true) {
            pulse.animateTo(.4f, tween(700, easing = FastOutSlowInEasing)); pulse.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        } else pulse.snapTo(1f)
    }
    val button by animateFloatAsState(if (isCurrent) 0f else 1f, policy.overshoot(400), label = "ascolta capitolo")

    Column(Modifier.fillMaxWidth().clip(svRounded(28.dp)).background(c2.copy(alpha = .08f)).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${sel + 1}", Modifier.widthIn(min = 78.dp), style = SvType.display(62.sp, .82f), color = c2, maxLines = 1)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(part?.label ?: "", c2.copy(alpha = .65f), small = true)
                Text(status, style = SvType.Label, color = c2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Inizia dopo ${dur(startAt)}" + if (sel in marked) " · con segnalibro" else "", style = SvType.MonoSmall,
                    color = c2.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            PillButton("Ascolta", c2, c1, Modifier.graphicsLayer { alpha = button; val s = .6f + .4f * button; scaleX = s; scaleY = s },
                height = 44.dp, icon = Icons.Rounded.PlayArrow, enabled = enabled && !isCurrent) { onPlay(chapter); picked = null }
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = SvType.MetaSmall.copy(fontSize = 11.sp, color = c2.copy(alpha = .7f))
        val countStyle = SvType.MonoSmall.copy(color = c2.copy(alpha = .7f))
        val sharp = SvLook.style.sharp
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val layout = remember(maxWidth, parts, density) {
                with(density) { GridLayout(maxWidth.toPx(), parts, 3.dp.toPx(), 16.dp.toPx(), 7.dp.toPx(), 14.dp.toPx()) }
            }
            Canvas(Modifier.fillMaxWidth().height(with(density) { layout.height.toDp() }).testTag("chapter_grid")
                .semantics { contentDescription = "Mappa dei capitoli: ${timeline.size} capitoli, selezionato ${sel + 1}" }
                .pointerInput(layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        layout.cellAt(down.position)?.let { picked = it }
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            layout.cellAt(change.position)?.let { picked = it }
                            change.consume()
                        }
                    }
                }) {
                val radius = CornerRadius(if (sharp) 0f else 4.dp.toPx())
                val cell = Size(layout.cell, layout.cell)
                parts.forEachIndexed { p, part ->
                    val top = layout.tops[p]
                    drawText(measurer, part.label, Offset(0f, top), labelStyle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, size = Size(size.width * .8f, layout.label))
                    val listened = (part.first..part.last).count { current < 0 || it < current }
                    val count = measurer.measure("$listened/${part.count}", countStyle)
                    drawText(count, topLeft = Offset(size.width - count.size.width, top))
                }
                fun cellColor(i: Int) = when {
                    i == current -> c2.copy(alpha = pulse.value)
                    current < 0 || i < current -> c2.copy(alpha = .48f)
                    else -> c2.copy(alpha = .15f)
                }
                for (i in timeline.indices) {
                    if (i == sel) continue
                    val o = layout.origin(i)
                    drawRoundRect(cellColor(i), o, cell, radius)
                    if (i in marked) drawCircle(if (i == current) c1 else c3, 2.5.dp.toPx(), o + Offset(layout.cell / 2, layout.cell / 2))
                }
                // The selected cell grows above its neighbours with a two-tone ring.
                val s = selScale.value
                val centre = layout.origin(sel) + Offset(layout.cell / 2, layout.cell / 2)
                fun square(extra: Float, color: Color) {
                    val half = layout.cell * s / 2 + extra
                    drawRoundRect(color, centre - Offset(half, half), Size(half * 2, half * 2),
                        if (sharp) CornerRadius.Zero else CornerRadius(radius.x * s + extra))
                }
                square(3.5.dp.toPx() * s, c2)
                square(2.dp.toPx() * s, c1)
                square(0f, cellColor(sel))
                if (sel in marked) drawCircle(if (sel == current) c1 else c3, 2.5.dp.toPx() * s, centre)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(c2.copy(alpha = .48f), "Ascoltati", c2)
            Legend(c2, "Ora", c2)
            Legend(c2.copy(alpha = .15f), "Da ascoltare", c2)
            Legend(c3, "Segnalibro", c2, dot = true)
        }
    }
}

/** Geometry of the chapter grid, shared by drawing and hit-testing. */
private class GridLayout(width: Float, private val parts: List<ChapterPart>, private val gap: Float, val label: Float,
    labelGap: Float, partGap: Float) {
    val cell = (width - gap * (COLUMNS - 1)) / COLUMNS
    private val gridTop = label + labelGap
    val tops: List<Float>
    val height: Float
    init {
        var y = 0f
        tops = parts.map { p -> y.also { y += gridTop + rows(p) * cell + (rows(p) - 1) * gap + partGap } }
        height = (y - partGap).coerceAtLeast(0f)
    }
    private fun rows(p: ChapterPart) = (p.count + COLUMNS - 1) / COLUMNS
    fun origin(index: Int): Offset {
        val p = parts.indexOfFirst { index in it.first..it.last }.coerceAtLeast(0)
        val k = index - parts[p].first
        return Offset((k % COLUMNS) * (cell + gap), tops[p] + gridTop + (k / COLUMNS) * (cell + gap))
    }
    fun cellAt(position: Offset): Int? {
        val p = tops.indexOfLast { it <= position.y }.takeIf { it >= 0 } ?: return null
        val part = parts[p]
        val col = (position.x / (cell + gap)).toInt().coerceIn(0, COLUMNS - 1)
        val row = ((position.y - tops[p] - gridTop) / (cell + gap)).toInt()
        if (position.y < tops[p] + gridTop || row >= rows(part)) return null
        return (part.first + row * COLUMNS + col).takeIf { it <= part.last }
    }
    companion object { const val COLUMNS = 20 }
}

@Composable
private fun Legend(swatch: Color, label: String, c2: Color, dot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The bookmark dot stays round in every theme, like the cells' markers.
        Box(Modifier.size(if (dot) 6.dp else 9.dp).clip(if (dot) CircleShape else svRounded(2.dp)).background(swatch))
        Spacer(Modifier.width(6.dp))
        Text(label, style = SvType.MetaSmall.copy(fontSize = 11.sp), color = c2.copy(alpha = .75f), maxLines = 1)
    }
}

@Composable
private fun BookmarkCard(book: Book, mark: Bookmark, c2: Color, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val chapter = book.currentChapter(mark.trackIndex, mark.positionMs)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(svRounded(18.dp)).background(c2.copy(alpha = .10f))
        .motionClickable(enabled = enabled, pressedScale = .98f, onClickLabel = "Riproduci dal segnalibro", onClick = onOpen)
        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Cap. ${chapter?.ordinal ?: (mark.trackIndex + 1)} · ${timeLabel((mark.positionMs - (chapter?.startMs ?: 0)).coerceAtLeast(0))}",
                style = SvType.MonoSmall, color = c2.copy(alpha = .65f))
            Spacer(Modifier.height(4.dp))
            Text(mark.note.ifBlank { "Segnalibro senza nota" }, style = SvType.BodySmall, color = c2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        IconCircle(Icons.Rounded.Close, "Elimina segnalibro", 40.dp, c2.copy(alpha = .7f), onClick = onDelete)
    }
}
