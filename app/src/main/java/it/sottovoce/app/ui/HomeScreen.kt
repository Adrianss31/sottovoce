package it.sottovoce.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.NowPlaying
import it.sottovoce.app.data.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class BookState { NEW, PROGRESS, DONE }

internal fun Book.state(): BookState = when {
    completed -> BookState.DONE
    lastPlayedAt == 0L && playedMs == 0L -> BookState.NEW
    else -> BookState.PROGRESS
}

/** The book as it is now: the live player position when it is the active one. */
internal fun Book.live(now: NowPlaying): Book =
    if (now.bookId == id) copy(trackIndex = now.trackIndex, positionMs = now.position, speed = now.speed) else this

internal fun skipBackIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Replay5; 10 -> Icons.Rounded.Replay10; 30 -> Icons.Rounded.Replay30; else -> Icons.Rounded.Replay
}
internal fun skipForwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Forward5; 10 -> Icons.Rounded.Forward10; 30 -> Icons.Rounded.Forward30; else -> Icons.Rounded.FastForward
}

private val FilterKeys = listOf("all" to "Tutti", "prog" to "In corso", "new" to "Da iniziare", "done" to "Finiti")
private val SortLabels = listOf("Recenti", "Titolo", "Autore")

@UnstableApi
@Composable
internal fun HomeScreen(vm: LibraryViewModel, books: List<Book>, current: Book?, active: Book?, timerLabel: String,
    listState: LazyListState, onOpenBook: (Book, String) -> Unit, onPlay: (Book) -> Unit, onOpenSub: (String) -> Unit,
    onUpdate: () -> Unit, onImportFiles: () -> Unit) {
    val sv = LocalSv.current
    val density = LocalDensity.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var updateDismissed by rememberSaveable { mutableStateOf(false) }
    val q = query.trim()
    val matches: (Book) -> Boolean = { b -> q.isEmpty() || (b.title + " " + b.author + " " + b.narrator + " " + b.series).contains(q, ignoreCase = true) }
    val okFilter: (Book) -> Boolean = { b -> when (filter) {
        "prog" -> b.state() == BookState.PROGRESS; "new" -> b.state() == BookState.NEW; "done" -> b.state() == BookState.DONE; else -> true } }
    val filtered = books.filter { matches(it) && okFilter(it) }.let { list ->
        when (sort) {
            1 -> list.sortedWith { a, b -> NaturalOrder.compare(a.title.removePrefix("Il ").removePrefix("La ").removePrefix("The "), b.title.removePrefix("Il ").removePrefix("La ").removePrefix("The ")) }
            2 -> list.sortedBy { it.author.trim().substringAfterLast(' ').lowercase() }
            else -> list.sortedByDescending { it.lastPlayedAt.coerceAtLeast(it.createdAt) }
        }
    }
    val entries = if (q.isNotEmpty()) filtered.map { LibraryEntry.Single(it) } else groupForLibrary(filtered, books)
    val keepGoing = books.filter { it.id != current?.id && it.state() == BookState.PROGRESS && it.series.isBlank() }
        .sortedByDescending { it.lastPlayedAt }
    val showBanner = vm.release != null && (!updateDismissed || vm.updatePhase != "idle")
    val stats = vm.stats
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(sv.bg)) {
        LazyColumn(Modifier.fillMaxSize().testTag("library"), state = listState,
            contentPadding = PaddingValues(top = topInset + 64.dp, bottom = 140.dp)) {
            item(key = "update") {
                AnimatedVisibility(showBanner, enter = fadeIn(tween(300)) + expandVertically(tween(450, easing = SvMotion.Emphasized)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(350, easing = SvMotion.Emphasized))) {
                    vm.release?.let { r -> UpdateBanner(vm, r.versionName, r.size, onLater = { updateDismissed = true }, onUpdate = onUpdate) }
                }
            }
            if (current != null) item(key = "now_playing") {
                val cardIndex = 1
                Box(Modifier.graphicsLayer {
                    val scrolled = when {
                        listState.firstVisibleItemIndex > cardIndex -> 420f
                        listState.firstVisibleItemIndex == cardIndex -> listState.firstVisibleItemScrollOffset / this.density
                        else -> 0f
                    }
                    val s = 1f - (scrolled / 420f).coerceIn(0f, 1f) * .08f
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(.5f, 0f)
                }) {
                    NowPlayingCard(vm, current, vm.now.takeIf { it.bookId == current.id }, timerLabel,
                        onOpen = { onOpenBook(current, "panel") },
                        onToggle = { if (active?.id == current.id) vm.togglePlay() else onPlay(current) },
                        onBack = { if (active?.id == current.id) vm.skip(-vm.skipBack) else onPlay(current) })
                }
            }
            if (books.isNotEmpty()) item(key = "stat_tiles") {
                StatTiles(stats, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { onOpenSub("stats") }
            }
            if (keepGoing.isNotEmpty() && q.isEmpty() && filter == "all") item(key = "keep_going") {
                KeepGoing(keepGoing) { b -> onOpenBook(b, "c-${b.id}") }
            }
            if (books.isEmpty()) item(key = "empty") {
                EmptyLibrary(onImport = { onOpenSub("import") }, onRestore = { onOpenSub("settings") })
            } else {
                item(key = "library_header") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 30.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Libreria", style = SvType.display(50.sp), color = sv.ink)
                        Spacer(Modifier.width(10.dp))
                        Text(filtered.size.toString().padStart(2, '0'), style = SvType.Mono, color = sv.ink2, modifier = Modifier.padding(bottom = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.clip(SvCircle).motionClickable(onClickLabel = "Cambia ordinamento") { sort = (sort + 1) % 3 }
                            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SwapVert, null, Modifier.size(16.dp), tint = sv.ink2)
                            Spacer(Modifier.width(4.dp))
                            CrossfadeText(SortLabels[sort], SvType.Meta.copy(fontWeight = FontWeight.Medium), sv.ink)
                        }
                    }
                }
                item(key = "filters") { FilterTabs(filter) { filter = it } }
                if (entries.isEmpty()) item(key = "no_results") {
                    NoResults(q) { query = ""; filter = "all" }
                } else entries.forEach { entry ->
                    when (entry) {
                        is LibraryEntry.Single -> item(key = entry.book.id) {
                            BookRow(entry.book, vm.now) { onOpenBook(entry.book, "g-${entry.book.id}") }
                        }
                        is LibraryEntry.SeriesGroup -> item(key = "series:${entry.key}") {
                            SeriesRow(entry.name, entry.books) { vm.openSeries(entry.key); onOpenSub("series") }
                        }
                    }
                }
            }
        }
        TopBar(searchOpen, query, onQuery = { query = it },
            onOpenSearch = { searchOpen = true }, onCloseSearch = { searchOpen = false; query = "" },
            onImport = { onOpenSub("import") }, onSettings = { onOpenSub("settings") })
    }
}

@Composable
private fun TopBar(searchOpen: Boolean, query: String, onQuery: (String) -> Unit, onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val t by animateFloatAsState(if (searchOpen) 1f else 0f, policy.emphasized(SvMotion.DurationIndicator), label = "ricerca")
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) { delay(250); runCatching { focus.requestFocus() } } }
    Box(Modifier.fillMaxWidth().background(sv.bg).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp)) {
        Text(buildAnnotatedString {
            append("sottovoce")
            withStyle(SpanStyle(color = sv.accent)) { append(".") }
        }, Modifier.align(Alignment.CenterStart).padding(start = 12.dp).graphicsLayer { alpha = 1f - t },
            style = SvType.Wordmark, color = sv.ink)
        Row(Modifier.align(Alignment.CenterEnd).graphicsLayer { alpha = 1f - t }, verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Rounded.Search, "Cerca libri", 44.dp, sv.ink, enabled = !searchOpen, onClick = onOpenSearch)
            IconCircle(Icons.Rounded.Add, "Importa audiolibri", 44.dp, sv.ink, enabled = !searchOpen, onClick = onImport)
            IconCircle(Icons.Rounded.Tune, "Impostazioni", 44.dp, sv.ink, enabled = !searchOpen, onClick = onSettings)
        }
        if (t > 0f) Row(Modifier.align(Alignment.Center).fillMaxWidth().height(46.dp)
            .graphicsLayer { alpha = t; scaleX = .2f + .8f * t; transformOrigin = TransformOrigin(.85f, .5f) }
            .clip(SvCircle).background(sv.surface).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = sv.ink2)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Titolo, autore, narratore o serie", style = SvType.Body, color = sv.ink2, maxLines = 1)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).testTag("library_search"),
                    singleLine = true, textStyle = SvType.Body.copy(color = sv.ink), cursorBrush = SolidColor(sv.accent))
            }
            IconCircle(Icons.Rounded.Close, "Chiudi ricerca", 40.dp, sv.ink, onClick = onCloseSearch)
        }
    }
}

@UnstableApi
@Composable
private fun UpdateBanner(vm: LibraryViewModel, version: String, size: Long, onLater: () -> Unit, onUpdate: () -> Unit) {
    val sv = LocalSv.current
    val phase = vm.updatePhase
    val progress by animateFloatAsState(if (phase == "download") vm.updateProgress else if (phase == "idle") 0f else 1f,
        tween(300, easing = LinearEasing), label = "download aggiornamento")
    val title = when (phase) {
        "download" -> "Download… ${(vm.updateProgress * 100).roundToInt()}%"
        "verify" -> "Verifica della firma…"
        "open" -> "Verificato. Apro l’installazione"
        else -> "Sottovoce $version è pronta"
    }
    val sub = when (phase) {
        "idle" -> "Un nuovo lettore e transizioni più fluide"
        "open" -> "La tua libreria resta esattamente com’è"
        else -> "${"%.1f".format(size / 1_048_576.0)} MB · versione firmata"
    }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(svRounded(26.dp)).background(sv.ink)
        .testTag("update_banner")) {
        Row(Modifier.padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrossfadeText(title, SvType.Label, sv.bg)
                CrossfadeText(sub, SvType.MetaSmall, sv.bg.copy(alpha = .7f))
            }
            if (phase == "idle") {
                Text("Più tardi", Modifier.clip(SvCircle).motionClickable(onClick = onLater).padding(horizontal = 12.dp, vertical = 10.dp),
                    style = SvType.Label, color = sv.bg.copy(alpha = .75f))
                val compatible = (vm.release?.minSdk ?: 0) <= Build.VERSION.SDK_INT
                PillButton("Aggiorna", sv.accent, sv.onAccent, height = 40.dp, enabled = compatible && !vm.updateInProgress, onClick = onUpdate)
            }
        }
        if (phase != "idle") Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(sv.accent))
    }
}

@UnstableApi
@Composable
private fun NowPlayingCard(vm: LibraryViewModel, book: Book, now: NowPlaying?, timerLabel: String,
    onOpen: () -> Unit, onToggle: () -> Unit, onBack: () -> Unit) {
    val colors = rememberBookColors(book)
    val playing = now?.playing == true
    val live = if (now != null) book.live(now) else book
    val chapter = live.currentChapter()
    val chapterProgress = if (live.completed) 1f else chapter?.progress(live.positionMs) ?: live.progress
    val left = chapter?.let { listeningTime(it.remainingMs(live.positionMs), live.speed) } ?: listeningTime(book.durationMs - live.playedMs, live.speed)
    val speedLabel = formatSpeed(live.speed)
    Box(Modifier.padding(12.dp).fillMaxWidth().clip(svRounded(38.dp)).background(colors.c1)
        .testTag("now_playing").motionClickable(pressedScale = .985f, onClickLabel = "Apri ${book.title}", onClick = onOpen)) {
        Box(Modifier.align(Alignment.TopEnd).offset(70.dp, (-70).dp).size(240.dp).clip(SvCircle).background(colors.c3.copy(alpha = .3f)))
        Column(Modifier.padding(20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val coverSize = minOf(172.dp, maxWidth * .5f)
                Row(Modifier.fillMaxWidth()) {
                    BookCover(book, colors, Modifier.size(coverSize).coverShadow(18.dp).coverOrigin("panel", 18.dp), radius = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).height(coverSize), horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Equalizer(playing, colors.c2, Modifier.size(11.dp))
                            Spacer(Modifier.width(6.dp))
                            Eyebrow((if (playing) "In ascolto" else "In pausa") + " · " + speedLabel +
                                (if (timerLabel.isNotEmpty() && now != null) " · $timerLabel" else ""), colors.c2, small = true)
                        }
                        Spacer(Modifier.weight(1f))
                        val big = dur(left)
                        Text(big, style = SvType.display(if (big.length > 4) 46.sp else 66.sp, .84f), color = colors.c2, maxLines = 1, softWrap = false)
                        Text("rimasti nel capitolo", style = SvType.Meta, color = colors.c2.copy(alpha = .75f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(book.title, style = SvType.display(44.sp, .88f), color = colors.c2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(chapter?.label() ?: book.author, style = SvType.BodySmall, color = colors.c2.copy(alpha = .75f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform(34, (chapter?.ordinal ?: 0) + 3 + book.id.length, chapterProgress, colors.c2, Modifier.weight(1f).height(40.dp),
                    playing = playing)
                Spacer(Modifier.width(12.dp))
                IconCircle(skipBackIcon(vm.skipBack), "Indietro ${vm.skipBack} secondi", 48.dp, colors.c2, border = colors.c2.copy(alpha = .35f),
                    pressedRotation = -45f, onClick = onBack)
                Spacer(Modifier.width(10.dp))
                PlayMorphButton(playing, 66.dp, colors.c2, colors.c1, playingRadius = 22.dp, onClick = onToggle)
            }
        }
    }
}

internal fun formatSpeed(speed: Float): String {
    val r = Math.round(speed * 100) / 100f
    return (if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')) + "×"
}

@Composable
private fun StatTiles(stats: ListeningStats?, modifier: Modifier, onOpen: () -> Unit) {
    val sv = LocalSv.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().clip(svRounded(28.dp)).background(sv.surface)
            .motionClickable(pressedScale = .97f, onClickLabel = "Apri le statistiche", onClick = onOpen).padding(18.dp)) {
            Eyebrow("Questa settimana", sv.ink2, small = true)
            Spacer(Modifier.height(8.dp))
            Text(dur(stats?.weekMs ?: 0), style = SvType.display(36.sp), color = sv.ink, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            val days = stats?.days.orEmpty()
            val max = days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1
            Row(Modifier.height(26.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                (if (days.isEmpty()) List(7) { null } else days).forEachIndexed { i, d ->
                    val h = ((d?.durationMs ?: 0).toFloat() / max).coerceIn(.08f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(h).clip(svRounded(3.dp))
                        .background(if (i == 6) sv.accent else sv.ink2.copy(alpha = .55f)))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().clip(svRounded(28.dp)).background(sv.surface)
            .motionClickable(pressedScale = .97f, onClickLabel = "Apri le statistiche", onClick = onOpen).padding(18.dp)) {
            Eyebrow("Serie di giorni", sv.ink2, small = true)
            Spacer(Modifier.height(8.dp))
            val streak = stats?.currentStreak ?: 0
            Text(if (streak == 1) "1 giorno" else "$streak giorni", style = SvType.display(36.sp), color = sv.ink, maxLines = 1)
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val days = stats?.days.orEmpty()
                repeat(7) { i ->
                    val on = (days.getOrNull(i)?.durationMs ?: 0) > 0
                    Box(Modifier.size(12.dp).clip(SvCircle).background(if (on) (if (i == 6) sv.accent else sv.ink) else sv.ink2.copy(alpha = .25f)))
                }
            }
        }
    }
}

@Composable
private fun KeepGoing(books: List<Book>, onOpen: (Book) -> Unit) {
    val sv = LocalSv.current
    Column(Modifier.padding(top = 26.dp)) {
        Text("Continua", Modifier.padding(horizontal = 20.dp), style = SvType.display(28.sp), color = sv.ink)
        Spacer(Modifier.height(12.dp))
        val state = rememberLazyListState()
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { "c-${it.id}" }) { b ->
                val colors = rememberBookColors(b)
                Column(Modifier.width(164.dp).motionClickable(pressedScale = .97f, onClickLabel = "Apri ${b.title}") { onOpen(b) }) {
                    BookCover(b, colors, Modifier.size(164.dp).coverOrigin("c-${b.id}", 22.dp), radius = 22.dp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(SvCircle).background(sv.line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(b.progress).background(sv.ink))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(b.title, style = SvType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = sv.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${dur(listeningTime(b.durationMs - b.playedMs, b.speed))} rimasti", style = SvType.MetaSmall, color = sv.ink2)
                }
            }
        }
    }
}

@Composable
private fun FilterTabs(selected: String, onSelect: (String) -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            FilterKeys.forEach { (key, label) ->
                val on = key == selected
                val u by animateFloatAsState(if (on) 1f else 0f, policy.emphasized(SvMotion.DurationIndicator), label = "filtro $key")
                Column(Modifier.width(IntrinsicSize.Max)
                    .motionClickable(pressedScale = .95f, onClickLabel = label, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(key) }
                    .padding(top = 10.dp)) {
                    Text(label, style = SvType.Label, color = if (on) sv.ink else sv.ink2, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.height(2.dp).fillMaxWidth().graphicsLayer { scaleX = u }.background(sv.ink))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(sv.line))
    }
}

@Composable
internal fun BookRow(book: Book, now: NowPlaying, onOpen: () -> Unit) {
    val sv = LocalSv.current
    val colors = rememberBookColors(book)
    val live = book.live(now)
    val state = live.state()
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(svRounded(26.dp)).testTag("book_${book.id}")
        .motionClickable(pressedScale = .98f, onClickLabel = "Apri ${book.title}", onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            BookCover(book, colors, Modifier.size(76.dp).coverOrigin("g-${book.id}", 16.dp), radius = 16.dp)
            if (book.needsRelink) Box(Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).size(20.dp).clip(SvCircle).background(SvError)
                .semantics { contentDescription = "File da ricollegare" }, contentAlignment = Alignment.Center) {
                Text("!", style = SvType.Label.copy(fontSize = 12.sp), color = Color.White)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = SvType.ListTitle, color = sv.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(book.author.ifBlank { "Autore non indicato" }, style = SvType.Meta, color = sv.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        StatusBadge(state, live.progress)
    }
}

@Composable
private fun StatusBadge(state: BookState, progress: Float) {
    val sv = LocalSv.current
    when (state) {
        BookState.PROGRESS -> ProgressRing(progress, 38.dp, sv.ink, sv.line, label = "${(progress * 100).roundToInt()}")
        BookState.NEW -> Text("NUOVO", Modifier.clip(SvCircle).background(sv.accent).padding(horizontal = 10.dp, vertical = 5.dp),
            style = SvType.EyebrowSmall, color = sv.onAccent)
        BookState.DONE -> Box(Modifier.size(38.dp).clip(SvCircle).background(sv.surface).semantics { contentDescription = "Finito" },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = sv.ink) }
    }
}

@Composable
private fun SeriesRow(name: String, books: List<Book>, onOpen: () -> Unit) {
    val sv = LocalSv.current
    val total = books.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = books.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (total > 0) played.toFloat() / total else if (books.all { it.completed }) 1f else 0f
    val state = when { books.all { it.completed } -> BookState.DONE; books.all { it.state() == BookState.NEW } -> BookState.NEW; else -> BookState.PROGRESS }
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(svRounded(26.dp)).testTag("series_card_$name")
        .motionClickable(pressedScale = .98f, onClickLabel = "Apri la serie $name", onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(76.dp)) {
            val shown = books.take(3).reversed()
            val rotations = listOf(6f, -3f, 0f).takeLast(shown.size)
            val offsets = listOf(.26f, .13f, 0f).takeLast(shown.size)
            shown.forEachIndexed { i, b ->
                val colors = rememberBookColors(b)
                BookCover(b, colors, Modifier.size(56.dp).offset(x = 76.dp * offsets[i] * .8f, y = 76.dp * (.26f - offsets[i]) * .8f)
                    .graphicsLayer { rotationZ = rotations[i] }, radius = 12.dp, showTitle = false)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = SvType.ListTitle, color = sv.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("Serie · ${books.size} ${if (books.size == 1) "libro" else "libri"}", style = SvType.Meta, color = sv.ink2)
        }
        Spacer(Modifier.width(10.dp))
        StatusBadge(state, progress)
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onRestore: () -> Unit) {
    val sv = LocalSv.current
    Column(Modifier.padding(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(svRounded(36.dp)).background(sv.accent).padding(26.dp)) {
            Text("Niente da ascoltare. Per ora.", style = SvType.display(64.sp, .86f), color = sv.onAccent)
            Spacer(Modifier.height(16.dp))
            Text("Importa un MP3, un M4B o una cartella di capitoli. Nessun account, nessun catalogo online: solo i tuoi file.",
                style = SvType.Body, color = sv.onAccent.copy(alpha = .85f))
            Spacer(Modifier.height(24.dp))
            PillButton("Importa file o una cartella", sv.onAccent, sv.accent, Modifier.fillMaxWidth().testTag("import_button"),
                height = 58.dp, icon = Icons.Rounded.Add, onClick = onImport)
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Ripristina un backup", Color.Transparent, sv.ink, Modifier.fillMaxWidth(), height = 54.dp, border = sv.line, onClick = onRestore)
        Spacer(Modifier.height(14.dp))
        Text("MP3 · M4A · M4B · AAC · OGG · OPUS · FLAC · WAV", Modifier.fillMaxWidth(), style = SvType.MonoSmall, color = sv.ink2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun NoResults(query: String, onClear: () -> Unit) {
    val sv = LocalSv.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.Start) {
        Text(if (query.isEmpty()) "Nessun libro qui" else "Nessun risultato per “$query”", style = SvType.display(36.sp), color = sv.ink)
        Spacer(Modifier.height(10.dp))
        Text("Prova con un altro titolo, un autore o un narratore, oppure cambia filtro.", style = SvType.Body, color = sv.ink2)
        Spacer(Modifier.height(18.dp))
        PillButton("Azzera la ricerca", sv.surface, sv.ink, onClick = onClear)
    }
}

/** Floating mini player: springs in once the now-playing card has scrolled away. */
@UnstableApi
@Composable
internal fun FloatingPill(vm: LibraryViewModel, book: Book, playing: Boolean, visible: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit, onBack: () -> Unit) {
    val policy = LocalMotionPolicy.current
    val shown by animateFloatAsState(if (visible) 1f else 0f,
        if (visible) policy.overshoot(SvMotion.DurationPill) else policy.emphasized(400), label = "pillola")
    if (shown <= 0.001f && !visible) return
    val colors = rememberBookColors(book)
    val live = book.live(vm.now)
    val chapter = live.currentChapter()
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, policy.animationsEnabled) {
        // Record-like spin, 9 s per turn; paused with playback. Infinite-animation aware for tests.
        if (playing && policy.animationsEnabled) {
            var last = 0L
            while (true) withInfiniteAnimationFrameMillis { t ->
                if (last != 0L) spin = (spin + (t - last) / 9000f * 360f) % 360f
                last = t
            }
        }
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 22.dp).fillMaxWidth().height(76.dp)
            .graphicsLayer {
                translationY = (1f - shown) * 140.dp.toPx()
                val s = .9f + .1f * shown; scaleX = s; scaleY = s
                alpha = shown.coerceIn(0f, 1f)
            }
            .coverShadow(38.dp, 16.dp).clip(svRounded(38.dp)).background(colors.c1).testTag("floating_pill")
            .motionClickable(enabled = visible, pressedScale = .98f, onClickLabel = "Apri ${book.title}", onClick = onOpen)) {
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, colors, Modifier.size(52.dp).coverOrigin("pill", 26.dp).graphicsLayer { rotationZ = spin },
                    radius = 26.dp, showTitle = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = SvType.Label, color = colors.c2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter?.label() ?: book.author, style = SvType.MetaSmall, color = colors.c2.copy(alpha = .7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconCircle(skipBackIcon(vm.skipBack), "Indietro ${vm.skipBack} secondi", 44.dp, colors.c2, enabled = visible,
                    pressedRotation = -45f, onClick = onBack)
                Spacer(Modifier.width(6.dp))
                PlayMorphButton(playing, 52.dp, colors.c2, colors.c1, playingRadius = 18.dp, onClick = { if (visible) onToggle() })
            }
            val progress = live.currentChapter()?.progress(live.positionMs) ?: live.progress
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 30.dp).fillMaxWidth().height(2.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colors.c2.copy(alpha = .8f)))
            }
        }
    }
}
