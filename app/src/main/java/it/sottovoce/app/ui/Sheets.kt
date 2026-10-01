package it.sottovoce.app.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Bottom sheet: theme surface, 30 dp top radius, grabber, 50% scrim; slides 105% → 0 over 550 ms. */
@Composable
internal fun SheetHost(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val t by animateFloatAsState(if (visible) 1f else 0f, policy.emphasized(SvMotion.DurationSheet), label = "foglio")
    val scrim by animateFloatAsState(if (visible) 1f else 0f, policy.emphasized(SvMotion.DurationScrim), label = "velo")
    if (t == 0f && !visible) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Color.Black.copy(alpha = .5f))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = visible, onClickLabel = "Chiudi", onClick = onDismiss))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .graphicsLayer { translationY = (1f - t) * size.height * 1.05f }
            .clip(svRounded(topStart = 30.dp, topEnd = 30.dp)).background(sv.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .navigationBarsPadding().imePadding()
            .heightIn(max = 760.dp).verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 22.dp)) {
            Box(Modifier.padding(vertical = 12.dp).align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(SvCircle).background(sv.ink2.copy(alpha = .4f)))
            content()
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String?) {
    val sv = LocalSv.current
    Text(title, style = SvType.display(34.sp), color = sv.ink)
    subtitle?.let { Spacer(Modifier.height(6.dp)); Text(it, style = SvType.Meta, color = sv.ink2) }
    Spacer(Modifier.height(20.dp))
}

@UnstableApi
@Composable
internal fun SpeedSheet(vm: LibraryViewModel, book: Book, active: Boolean) {
    val sv = LocalSv.current
    val start = if (active) vm.now.speed else book.speed
    var value by remember(book.id) { mutableFloatStateOf(start) }
    fun snap(v: Float) = ((v * 20f).roundToInt() / 20f).coerceIn(.5f, 3f)
    SheetHeader("Velocità di ascolto", "Salvata per questo libro")
    Text(formatSpeed(value), Modifier.fillMaxWidth(), style = SvType.display(84.sp), color = sv.ink,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).clip(SvCircle).background(sv.bg).testTag("speed_slider")
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, .5f..3f, steps = 49)
            setProgress { v -> value = snap(v); vm.speed(book, value); true }
        }
        .pointerInput(book.id) { detectTapGestures { o -> value = snap(.5f + 2.5f * (o.x / size.width)); vm.speed(book, value) } }
        .pointerInput(book.id) {
            detectHorizontalDragGestures(onDragEnd = { vm.speed(book, value) }) { change, _ ->
                value = snap(.5f + 2.5f * (change.position.x / size.width).coerceIn(0f, 1f)); vm.previewSpeed(book, value)
            }
        }) {
        val f = (value - .5f) / 2.5f
        Box(Modifier.fillMaxHeight().width(44.dp + (maxWidth - 44.dp) * f).clip(SvCircle).background(sv.accent))
        Box(Modifier.offset(x = (maxWidth - 44.dp) * f).size(44.dp).padding(6.dp).clip(SvCircle).background(sv.onAccent))
    }
    Spacer(Modifier.height(8.dp))
    Row { Text("0,5×", style = SvType.MonoSmall, color = sv.ink2); Spacer(Modifier.weight(1f)); Text("3×", style = SvType.MonoSmall, color = sv.ink2) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
            val on = kotlin.math.abs(value - v) < .001f
            Box(Modifier.weight(1f).height(44.dp).clip(SvCircle).background(if (on) sv.ink else sv.bg)
                .motionClickable(pressedScale = .92f, onClickLabel = formatSpeed(v)) { value = v; vm.speed(book, v) },
                contentAlignment = Alignment.Center) {
                Text(formatSpeed(v), style = SvType.Label.copy(fontSize = 13.sp), color = if (on) sv.bg else sv.ink, maxLines = 1)
            }
        }
    }
}

@UnstableApi
@Composable
internal fun SleepSheet(vm: LibraryViewModel, book: Book, timerLabel: String, remaining: Long, total: Long,
    onClose: () -> Unit, play: (Book) -> Unit) {
    val sv = LocalSv.current
    val on = timerLabel.isNotEmpty() && vm.now.bookId == book.id
    if (!on) {
        SheetHeader("Timer di spegnimento", "Avviare un timer avvia anche l’ascolto")
        val options = listOf(15 to "min", 30 to "min", 45 to "min", 60 to "min", 90 to "min", -1 to "capitolo")
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (minutes, unit) ->
                    Column(Modifier.weight(1f).height(84.dp).clip(svRounded(22.dp)).background(sv.bg)
                        .testTag("timer_$minutes")
                        .motionClickable(pressedScale = .95f, onClickLabel = if (minutes < 0) "Fine capitolo" else "$minutes minuti") {
                            vm.startTimer(book, minutes, play)
                            vm.message = if (minutes < 0) "Si ferma alla fine di questo capitolo." else "Timer · $minutes min"
                            onClose()
                        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (minutes < 0) "Fine del" else "$minutes", style = SvType.display(if (minutes < 0) 20.sp else 28.sp), color = sv.ink)
                        Text(unit, style = SvType.MetaSmall, color = sv.ink2)
                    }
                }
            }
        }
    } else {
        SheetHeader("Timer attivo", if (timerLabel == "Fine capitolo") "Si ferma alla fine del capitolo" else "Tempo rimanente")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val fraction = if (total > 0) remaining.toFloat() / total else 1f
            Canvas(Modifier.size(190.dp)) {
                val s = 10.dp.toPx()
                val arc = Size(size.width - s, size.height - s)
                drawArc(sv.line, 0f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
                drawArc(sv.accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timeLabel(remaining), style = SvType.display(44.sp), color = sv.ink)
                Text(if (timerLabel == "Fine capitolo") "fino a fine capitolo" else "rimanenti", style = SvType.MetaSmall, color = sv.ink2)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("+10 min", sv.bg, sv.ink, Modifier.weight(1f), height = 52.dp) { vm.extendTimer() }
            PillButton("Disattiva", sv.ink, sv.bg, Modifier.weight(1f), height = 52.dp) {
                vm.timer(0); vm.message = "Timer disattivato."; onClose()
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    ToggleRow("Dissolvenza finale", "Abbassa il volume nell’ultimo minuto", vm.timerFade) { vm.changeTimerFade(!vm.timerFade) }
    ToggleRow("Dopo le ${"%02d:%02d".format(vm.nightTimerStartMinutes / 60, vm.nightTimerStartMinutes % 60)}",
        "Avvia automaticamente ${vm.nightTimerDuration} minuti ogni notte", vm.nightTimerEnabled) { vm.changeNightTimerEnabled(!vm.nightTimerEnabled) }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String?, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val sv = LocalSv.current
    Row(modifier.fillMaxWidth().clip(svRounded(18.dp))
        .motionClickable(pressedScale = .98f, onClickLabel = title, role = androidx.compose.ui.semantics.Role.Switch, onClick = onToggle)
        .semantics { stateDescription = if (checked) "Attivo" else "Disattivo" }
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SvType.Label, color = sv.ink)
            subtitle?.let { Text(it, style = SvType.MetaSmall, color = sv.ink2) }
        }
        Spacer(Modifier.width(12.dp))
        SvSwitch(checked)
    }
}

@UnstableApi
@Composable
internal fun BookmarkSheet(vm: LibraryViewModel, book: Book, active: Boolean, onSave: (String) -> Unit) {
    val sv = LocalSv.current
    val live = if (active) book.live(vm.now) else book
    val chapter = live.currentChapter()
    var note by remember(book.id) { mutableStateOf("") }
    SheetHeader("Nuovo segnalibro", chapter?.let { "Capitolo ${it.ordinal} · ${timeLabel(it.elapsedMs(live.positionMs))}" } ?: timeLabel(live.positionMs))
    SvTextField(note, { note = it.take(10_000) }, "Nota facoltativa", Modifier.testTag("bookmark_note"), minHeight = 110.dp, singleLine = false)
    Spacer(Modifier.height(16.dp))
    PillButton("Salva segnalibro", sv.accent, sv.onAccent, Modifier.fillMaxWidth().testTag("save_bookmark"), height = 56.dp) { onSave(note.trim()) }
}

@Composable
internal fun SvTextField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 52.dp, singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    val sv = LocalSv.current
    BasicTextField(value, onValue, modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine,
        textStyle = SvType.Body.copy(color = sv.ink), cursorBrush = SolidColor(sv.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(svRounded(18.dp)).background(sv.bg)
                .graphicsLayer { alpha = if (enabled) 1f else .5f }
                .padding(horizontal = 16.dp, vertical = 15.dp)) {
                if (value.isEmpty()) Text(placeholder, style = SvType.Body, color = sv.ink2)
                inner()
            }
        })
}

@Composable
private fun ManageRow(icon: ImageVector, title: String, subtitle: String?, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sv = LocalSv.current
    Row(modifier.fillMaxWidth().clip(svRounded(18.dp)).motionClickable(pressedScale = .98f, onClickLabel = title, onClick = onClick)
        .padding(vertical = 13.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(SvCircle).background(sv.bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SvType.Label, color = color)
            subtitle?.let { Text(it, style = SvType.MetaSmall, color = sv.ink2) }
        }
    }
}

@UnstableApi
@Composable
internal fun ManageSheet(vm: LibraryViewModel, book: Book, onEdit: () -> Unit, onReset: () -> Unit, onRelink: () -> Unit,
    onRemoveCopies: () -> Unit, onRemove: () -> Unit, onComplete: () -> Unit) {
    val sv = LocalSv.current
    SheetHeader(book.title, book.author.ifBlank { null })
    ManageRow(Icons.Rounded.Edit, "Modifica dettagli", "Titolo, autore, narratore e serie", sv.ink, onClick = onEdit)
    ManageRow(if (book.completed) Icons.Rounded.RestartAlt else Icons.Rounded.CheckCircleOutline,
        if (book.completed) "Segna da completare" else "Segna come finito", null, sv.ink, onClick = onComplete)
    ManageRow(Icons.Rounded.Replay, "Segna come non iniziato", "Azzera la posizione, conserva segnalibri, velocità e statistiche", sv.ink,
        Modifier.testTag("reset_book"), onClick = onReset)
    ManageRow(Icons.Rounded.FolderOpen, "Ricollega file", "Scegli di nuovo gli audio mantenendo la posizione", sv.ink, onClick = onRelink)
    if (book.tracks.any { it.owned }) ManageRow(Icons.Rounded.CleaningServices, "Elimina copie audio nell’app",
        "Libera spazio; gli originali restano intatti", sv.ink, onClick = onRemoveCopies)
    ManageRow(Icons.Rounded.DeleteOutline, "Rimuovi dalla libreria", "I file originali sul dispositivo non vengono mai eliminati", SvError, onClick = onRemove)
}

@Composable
internal fun EditSheet(book: Book, onSave: (String, String, String, String, Int?) -> Unit) {
    val sv = LocalSv.current
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var narrator by remember(book.id) { mutableStateOf(book.narrator) }
    var series by remember(book.id) { mutableStateOf(book.series) }
    var position by remember(book.id) { mutableStateOf(book.seriesPosition?.toString().orEmpty()) }
    SheetHeader("Modifica libro", null)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SvTextField(title, { title = it.take(1000) }, "Titolo")
        SvTextField(author, { author = it.take(1000) }, "Autore")
        SvTextField(narrator, { narrator = it.take(1000) }, "Narratore")
        SvTextField(series, { series = it.take(1000) }, "Serie (es. Il Signore degli Anelli)")
        SvTextField(position, { position = it.filter(Char::isDigit).take(3) }, "Numero nella serie", keyboardType = KeyboardType.Number,
            enabled = series.isNotBlank())
    }
    Spacer(Modifier.height(16.dp))
    val valid = title.isNotBlank() && (position.isBlank() || position.toIntOrNull() in 1..999)
    PillButton("Salva", sv.accent, sv.onAccent, Modifier.fillMaxWidth(), height = 56.dp, enabled = valid) {
        onSave(title, author, narrator, series, position.toIntOrNull())
    }
}

@Composable
internal fun ConfirmSheet(title: String, body: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val sv = LocalSv.current
    SheetHeader(title, null)
    Text(body, style = SvType.Body, color = sv.ink2)
    Spacer(Modifier.height(22.dp))
    PillButton(confirm, SvError, Color.White, Modifier.fillMaxWidth(), height = 56.dp, onClick = onConfirm)
    Spacer(Modifier.height(10.dp))
    PillButton("Annulla", Color.Transparent, sv.ink, Modifier.fillMaxWidth(), height = 52.dp, border = sv.line, onClick = onCancel)
}

@UnstableApi
@Composable
internal fun RestoreSheet(vm: LibraryViewModel, onClose: () -> Unit) {
    val sv = LocalSv.current
    val backup = vm.pendingBackup
    SheetHeader(if (backup != null) "Ripristinare ${backup.books.size} libri?" else "Ripristino", null)
    Text("Sostituirà libri e segnalibri. I backup nuovi includono le statistiche. La libreria attuale resta recuperabile dalle impostazioni. " +
        "Le copie audio disponibili saranno ricollegate, gli altri audio andranno scelti di nuovo.", style = SvType.Body, color = sv.ink2)
    Spacer(Modifier.height(22.dp))
    PillButton("Ripristina", sv.accent, sv.onAccent, Modifier.fillMaxWidth(), height = 56.dp, enabled = backup != null) {
        vm.restoreBackup(); onClose()
    }
    Spacer(Modifier.height(10.dp))
    PillButton("Annulla", Color.Transparent, sv.ink, Modifier.fillMaxWidth(), height = 52.dp, border = sv.line) {
        vm.pendingBackup = null; onClose()
    }
}

/** Bottom ink pill that slides up 80 dp and hides after 2.4 s. */
@Composable
internal fun ToastHost(toast: Toast?, onAction: () -> Unit, onDone: () -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    var shown by remember { mutableStateOf<Toast?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            shown = toast; visible = true
            delay(if (toast.action != null) 5000L else 2400L + (toast.text.length * 25L).coerceAtMost(3000L))
            visible = false
            delay(450)
            onDone()
        }
    }
    val t by animateFloatAsState(if (visible) 1f else 0f, policy.emphasized(SvMotion.DurationScrim), label = "avviso")
    val current = shown ?: return
    if (t == 0f && !visible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 108.dp)
            .graphicsLayer { alpha = t; translationY = (1f - t) * 80.dp.toPx() }
            .clip(SvCircle).background(sv.ink).padding(start = 20.dp, end = if (current.action != null) 6.dp else 20.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp).testTag("toast"), verticalAlignment = Alignment.CenterVertically) {
            Text(current.text, Modifier.weight(1f, fill = false).padding(vertical = 8.dp), style = SvType.BodySmall, color = sv.bg,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            current.action?.let { label ->
                Spacer(Modifier.width(8.dp))
                Text(label, Modifier.clip(SvCircle).motionClickable { visible = false; onAction() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = SvType.Label, color = sv.accent)
            }
        }
    }
}

/** Long operations (backup, cleanup, relinking…) show a calm blocking card; quick saves never flash. */
@UnstableApi
@Composable
internal fun BusyOverlay(vm: LibraryViewModel) {
    val sv = LocalSv.current
    val label = vm.busy
    val relevant = label != null && !label.startsWith("Salvataggio") && !(vm.screen == "import" && vm.relinkId == null)
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(relevant, label) { show = false; if (relevant) { delay(450); show = true } }
    if (!show || label == null) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
        .clickable(remember { MutableInteractionSource() }, indication = null) { }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).fillMaxWidth().clip(svRounded(30.dp)).background(sv.surface).padding(24.dp)) {
            Text(label, style = SvType.display(28.sp), color = sv.ink)
            Spacer(Modifier.height(14.dp))
            IndeterminateBar(sv.accent, sv.line)
            Spacer(Modifier.height(12.dp))
            Text(vm.operationDetail ?: "Operazione in corso…", style = SvType.Meta, color = sv.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            PillButton("Annulla", sv.bg, sv.ink, height = 44.dp, onClick = vm::cancelTask)
        }
    }
}

@Composable
internal fun IndeterminateBar(color: Color, track: Color, modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "attesa")
    val x by transition.animateFloat(-.4f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1200, easing = SvMotion.Emphasized)), label = "barra attesa")
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).clip(SvCircle).background(track)) {
        Box(Modifier.offset(x = maxWidth * x).width(maxWidth * .4f).fillMaxHeight().clip(SvCircle).background(color))
    }
}

/**
 * Every chapter of a book, searchable by number or title and grouped by part with
 * sticky headers; part chips jump through very long books.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ChaptersSheet(book: Book, live: Book, marks: List<Bookmark>, onGo: (BookChapter) -> Unit) {
    val sv = LocalSv.current
    val timeline = remember(book.tracks) { book.chapterTimeline() }
    val parts = remember(book.tracks, timeline) { book.chapterParts(timeline) }
    val long = timeline.size > LONG_BOOK_CHAPTERS
    val current = if (live.completed) -1 else (live.currentChapter()?.ordinal ?: 1) - 1
    val marked = remember(marks, timeline) {
        marks.mapNotNull { m -> timeline.lastOrNull { it.trackIndex == m.trackIndex && it.startMs <= m.positionMs }?.ordinal?.minus(1) }.toSet()
    }
    var query by remember(book.id) { mutableStateOf("") }
    val q = query.trim().lowercase()
    val groups = remember(parts, timeline, q) {
        parts.map { p -> p to (p.first..p.last).filter { i -> q.isEmpty() || "${i + 1}".startsWith(q) || timeline[i].title.lowercase().contains(q) } }
            .filter { it.second.isNotEmpty() }
    }
    // Flat positions of headers and rows, for the chips and the initial scroll.
    val headerAt = remember(groups) {
        var n = 0
        groups.associate { (p, rows) -> p.index to n.also { n += 1 + rows.size } }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(book.id) {
        val g = groups.firstOrNull { (_, rows) -> current in rows } ?: return@LaunchedEffect
        val index = (headerAt[g.first.index] ?: 0) + 1 + g.second.indexOf(current)
        // A few rows of context above the current chapter.
        listState.scrollToItem((index - 3).coerceAtLeast(0))
    }
    val left = listeningTime(book.durationMs - live.playedMs, live.speed)

    Row(verticalAlignment = Alignment.Bottom) {
        Text("Tutti i capitoli", Modifier.weight(1f), style = SvType.Label.copy(fontSize = 16.sp), color = sv.ink)
        Text("${timeline.size} · ${dur(left)} rimasti", style = SvType.Mono, color = sv.ink2, maxLines = 1)
    }
    Spacer(Modifier.height(14.dp))
    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("chapter_search"), singleLine = true,
        textStyle = SvType.Body.copy(color = sv.ink), cursorBrush = SolidColor(sv.accent),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(46.dp).clip(svRounded(23.dp)).background(sv.bg).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = sv.ink2)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Numero o titolo del capitolo", style = SvType.Body, color = sv.ink2, maxLines = 1)
                    inner()
                }
            }
        })
    if (long && parts.size > 1) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEach { p ->
                Box(Modifier.widthIn(min = 52.dp).height(36.dp).clip(SvCircle).background(sv.bg)
                    .motionClickable(pressedScale = .94f, onClickLabel = p.label) {
                        headerAt[p.index]?.let { scope.launch { listState.animateScrollToItem(it) } }
                    }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(p.chip, style = SvType.Mono, color = sv.ink, maxLines = 1)
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(430.dp).testTag("chapter_list"), state = listState) {
        if (groups.isEmpty()) item(key = "empty") {
            Text("Nessun capitolo corrisponde a “$query”", Modifier.fillMaxWidth().padding(vertical = 40.dp), style = SvType.BodySmall,
                color = sv.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        groups.forEach { (part, rows) ->
            stickyHeader(key = "part:${part.index}") {
                Column(Modifier.fillMaxWidth().background(sv.surface)) {
                    Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(if (long) part.label else "Capitoli", sv.accent, Modifier.weight(1f))
                        Text("${rows.size}", style = SvType.MonoSmall, color = sv.ink2)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(sv.line))
                }
            }
            items(rows, key = { "row:$it" }) { i ->
                val c = timeline[i]
                val isCurrent = i == current
                Column(Modifier.fillMaxWidth().testTag("sheet_chapter_${c.ordinal}")
                    .motionClickable(pressedScale = .98f, onClickLabel = "Riproduci ${c.title}") { onGo(c) }
                    .graphicsLayer { alpha = if (current < 0 || i < current) .5f else 1f }) {
                    Row(Modifier.height(47.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}".padStart(3, '0'), Modifier.width(36.dp), style = SvType.Mono, color = sv.ink2)
                        Text(c.title, Modifier.weight(1f), style = SvType.BodySmall.copy(fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (isCurrent) sv.accent else sv.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (i in marked) Icon(Icons.Rounded.Bookmark, "Con segnalibro", Modifier.padding(start = 6.dp).size(13.dp), tint = sv.accent)
                        if (isCurrent) Text("ORA", Modifier.padding(start = 8.dp), style = SvType.EyebrowSmall.copy(fontWeight = FontWeight.Bold), color = sv.accent)
                        Text(dur(c.durationMs), Modifier.width(52.dp), style = SvType.Mono, color = sv.ink2, maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(sv.line))
                }
            }
        }
    }
}
