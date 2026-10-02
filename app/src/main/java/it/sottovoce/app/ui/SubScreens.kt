package it.sottovoce.app.ui

import android.app.StatusBarManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.BuildConfig
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.R
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackTileService
import it.sottovoce.app.playback.PlaybackWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared frame for Series, Import, Stats and Settings: slides in from the right over the dimmed home. */
@Composable
private fun SubScaffold(onBack: () -> Unit, tag: String, hasBottomBar: Boolean = false, bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit) {
    val sv = LocalSv.current
    // The full-screen list receives every touch, so nothing reaches the dimmed home below.
    Box(Modifier.fillMaxSize().background(sv.bg)) {
        LazyColumn(Modifier.fillMaxSize().testTag(tag),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (hasBottomBar) 120.dp else 48.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp), content = content)
        Box(Modifier.fillMaxWidth().background(sv.bg).statusBarsPadding().height(56.dp).padding(horizontal = 8.dp)) {
            IconCircle(Icons.Rounded.ChevronLeft, "Indietro", 44.dp, sv.ink, Modifier.align(Alignment.CenterStart), iconSize = 28.dp, onClick = onBack)
        }
        if (hasBottomBar) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(sv.bg.copy(alpha = 0f), sv.bg, sv.bg)))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) { bottomBar() }
        }
    }
}

@Composable
private fun ScreenTitle(eyebrow: String?, title: String, size: Int = 50) {
    val sv = LocalSv.current
    Column(Modifier.padding(bottom = 22.dp)) {
        eyebrow?.let { Eyebrow(it, sv.ink2); Spacer(Modifier.height(10.dp)) }
        Text(title, style = SvType.display(size.sp), color = sv.ink)
    }
}

@Composable
private fun SectionTitle(text: String) {
    val sv = LocalSv.current
    Text(text, Modifier.padding(top = 30.dp, bottom = 10.dp), style = SvType.display(28.sp), color = sv.ink)
}

private fun roman(n: Int): String {
    if (n <= 0 || n >= 4000) return n.toString()
    val values = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    var rest = n
    return buildString { values.forEach { (v, s) -> while (rest >= v) { append(s); rest -= v } } }
}

// Series ------------------------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun SeriesScreen(vm: LibraryViewModel, books: List<Book>, key: String?, activeId: String?, playing: Boolean,
    onBack: () -> Unit, onOpenBook: (Book, String) -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val entries = books.filter { key != null && seriesKey(it.series) == seriesKey(key) }
        .sortedWith(compareBy<Book> { it.seriesPosition ?: Int.MAX_VALUE }.thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
    val name = entries.firstOrNull()?.series?.trim()?.replace(Regex("\\s+"), " ") ?: key.orEmpty()
    val total = entries.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = entries.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (total > 0) played.toFloat() / total else 0f
    var fanned by remember { mutableStateOf(false) }
    LaunchedEffect(key) { fanned = true }
    SubScaffold(onBack, "series_view") {
        item(key = "fan") {
            Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                val shown = entries.take(3).reversed()
                val rotations = listOf(9f, -2f, -10f).takeLast(shown.size)
                val offsets = listOf(50f, 0f, -50f).takeLast(shown.size)
                shown.forEachIndexed { i, b ->
                    val r by animateFloatAsState(if (fanned) rotations[i] else 0f,
                        policy.emphasized(700, delay = 150 + i * 60), label = "ventaglio $i")
                    val x by animateFloatAsState(if (fanned) offsets[i] else 0f,
                        policy.emphasized(700, delay = 150 + i * 60), label = "ventaglio x $i")
                    BookCover(b, rememberBookColors(b), Modifier.size(150.dp).graphicsLayer { rotationZ = r; translationX = x * density }
                        .coverShadow(18.dp, 10.dp), radius = 18.dp)
                }
            }
        }
        item(key = "head") {
            Column(Modifier.padding(top = 18.dp)) {
                Eyebrow("Serie · ${entries.size} ${if (entries.size == 1) "volume" else "volumi"}", sv.ink2)
                Spacer(Modifier.height(10.dp))
                Text(name, style = SvType.display(36.sp), color = sv.ink)
                entries.firstOrNull()?.author?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, style = SvType.Body, color = sv.ink2) }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(SvCircle).background(sv.line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(sv.ink))
                }
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt()}% della serie · ${dur(total - played)} rimaste", style = SvType.MetaSmall, color = sv.ink2)
                Spacer(Modifier.height(18.dp))
            }
        }
        if (entries.isEmpty()) item { Text("Nessun libro in questa serie.", style = SvType.Body, color = sv.ink2) }
        itemsIndexed(entries, key = { _, b -> b.id }) { index, b ->
            val live = b.live(vm.now)
            val st = live.state()
            Row(Modifier.fillMaxWidth().clip(svRounded(26.dp)).testTag("book_${b.id}")
                .motionClickable(pressedScale = .98f, onClickLabel = "Apri ${b.title}") { onOpenBook(b, "s-${b.id}") }
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(roman(b.seriesPosition ?: (index + 1)), Modifier.width(38.dp), style = SvType.display(22.sp), color = sv.ink2)
                BookCover(b, rememberBookColors(b), Modifier.size(76.dp).coverOrigin("s-${b.id}", 16.dp), radius = 16.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = SvType.ListTitle, color = sv.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(when (st) {
                        BookState.DONE -> "Finito"
                        BookState.NEW -> "Da iniziare · ${dur(b.durationMs)}"
                        BookState.PROGRESS -> "${(live.progress * 100).toInt()}% · ${dur(listeningTime(b.durationMs - live.playedMs, live.speed))} rimasti"
                    } + if (activeId == b.id && playing) " · in ascolto" else "", style = SvType.Meta, color = sv.ink2, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(SvCircle).background(sv.line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(live.progress).background(sv.ink))
                    }
                }
            }
        }
    }
}

// Stats -------------------------------------------------------------------------------------------

private val ItalianDays = mapOf(DayOfWeek.MONDAY to "L", DayOfWeek.TUESDAY to "M", DayOfWeek.WEDNESDAY to "M",
    DayOfWeek.THURSDAY to "G", DayOfWeek.FRIDAY to "V", DayOfWeek.SATURDAY to "S", DayOfWeek.SUNDAY to "D")
private val ItalianMonths = listOf("Gen", "Feb", "Mar", "Apr", "Mag", "Giu", "Lug", "Ago", "Set", "Ott", "Nov", "Dic")

@UnstableApi
@Composable
internal fun StatsScreen(vm: LibraryViewModel, books: List<Book>, active: Boolean, onBack: () -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val s = vm.stats
    val grow = remember { Animatable(0f) }
    LaunchedEffect(active, s != null) {
        if (active && s != null) { grow.snapTo(0f); grow.animateTo(1f, tween(policy.durationMillis(1200), easing = LinearEasing)) }
    }
    fun staged(i: Int, step: Int, delay: Int, duration: Int): Float =
        SvMotion.Emphasized.transform(((grow.value * 1200f - delay - i * step) / duration).coerceIn(0f, 1f))
    SubScaffold(onBack, "stats_view") {
        item {
            Column {
                Eyebrow("Oggi", sv.ink2)
                Spacer(Modifier.height(10.dp))
                Text(dur(s?.todayMs ?: 0), style = SvType.display(76.sp), color = sv.ink)
                Text("Tutto resta sul dispositivo", style = SvType.Meta, color = sv.ink2)
            }
        }
        if (s == null) return@SubScaffold
        item {
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(svRounded(28.dp)).background(sv.surface).padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Ultimi 7 giorni", style = SvType.Label, color = sv.ink)
                    Spacer(Modifier.weight(1f))
                    Text(dur(s.weekMs), style = SvType.Mono, color = sv.ink2)
                }
                Spacer(Modifier.height(16.dp))
                val max = s.days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    s.days.forEachIndexed { i, d ->
                        val g = staged(i, 50, 200, 800)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (d.durationMs > 0) Text("${d.durationMs / 60_000}m", style = SvType.MonoSmall.copy(fontSize = 10.sp), color = sv.ink2,
                                modifier = Modifier.graphicsLayer { alpha = g })
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height((112f * d.durationMs / max * g).coerceAtLeast(3f).dp)
                                .clip(svRounded(topStart = 8.dp, topEnd = 8.dp)).background(if (i == s.days.lastIndex) sv.accent else sv.surface2))
                            Spacer(Modifier.height(6.dp))
                            Text(ItalianDays[LocalDate.ofEpochDay(d.day).dayOfWeek] ?: "", style = SvType.MonoSmall, color = sv.ink2)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Serie di giorni", if (s.currentStreak == 1) "1 giorno" else "${s.currentStreak} giorni", Modifier.weight(1f))
                StatTile("Libri finiti", "${s.completedBooks}/${s.totalBooks}", Modifier.weight(1f))
                StatTile("Serie finite", "${s.completedSeries}/${s.totalSeries}", Modifier.weight(1f))
            }
        }
        item {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(svRounded(28.dp)).background(sv.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ultimi 6 mesi", style = SvType.Label, color = sv.ink)
                val max = s.months.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1
                s.months.forEachIndexed { i, m ->
                    val g = staged(i, 60, 300, 800)
                    val month = m.key.substringAfter('-').toIntOrNull()?.let { ItalianMonths.getOrNull(it - 1) } ?: m.label
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(month, Modifier.width(40.dp), style = SvType.MonoSmall, color = sv.ink2)
                        Box(Modifier.weight(1f).height(14.dp)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((m.durationMs.toFloat() / max * g).coerceIn(0f, 1f)).clip(SvCircle)
                                .background(if (i == s.months.lastIndex) sv.accent else sv.ink2))
                        }
                        Text(if (m.durationMs > 0) "${m.durationMs / 3_600_000}h" else "—", Modifier.width(44.dp), style = SvType.MonoSmall,
                            color = sv.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
        if (s.topBookIds.isNotEmpty()) {
            item { SectionTitle("Più ascoltati") }
            s.topBookIds.forEach { (id, ms) ->
                val b = books.find { it.id == id } ?: return@forEach
                item(key = "top:$id") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(b, rememberBookColors(b), Modifier.size(48.dp), radius = 12.dp, showTitle = false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = SvType.Label, color = sv.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b.author, style = SvType.MetaSmall, color = sv.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(dur(ms), style = SvType.Mono, color = sv.ink)
                    }
                }
            }
        }
        item {
            Text("Totale registrato: ${dur(s.totalMs)} · questo mese: ${dur(s.thisMonthMs)}. Le statistiche non lasciano mai il dispositivo.",
                Modifier.padding(top = 24.dp), style = SvType.MetaSmall, color = sv.ink2)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    val sv = LocalSv.current
    Column(modifier.fillMaxHeight().clip(svRounded(24.dp)).background(sv.surface).padding(16.dp)) {
        Eyebrow(label, sv.ink2, small = true)
        Spacer(Modifier.height(8.dp))
        Text(value, style = SvType.display(30.sp), color = sv.ink, maxLines = 1)
    }
}

// Import ------------------------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun ImportScreen(vm: LibraryViewModel, books: List<Book>, onBack: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit,
    onListen: (String) -> Unit) {
    val sv = LocalSv.current
    val busy = vm.busy
    val scanning = busy != null && busy.startsWith("Lettura")
    val importing = busy != null && (busy.startsWith("Copia") || busy.startsWith("Importazione"))
    val done = vm.importDone
    val preview = vm.candidates.isNotEmpty() && !importing && done == null
    val relink = vm.relinkId != null
    SubScaffold(onBack, "import_view", hasBottomBar = preview, bottomBar = {
        PillButton(if (relink) "Conferma e ricollega" else if (vm.candidates.size == 1) "Aggiungi alla libreria" else "Aggiungi ${vm.candidates.size} libri",
            sv.accent, sv.onAccent, Modifier.fillMaxWidth().testTag("confirm_import"), height = 58.dp,
            enabled = vm.candidates.isNotEmpty() && vm.candidates.all { it.title.isNotBlank() }, onClick = vm::confirmImport)
    }) {
        when {
            done != null -> item(key = "done") { ImportDone(vm, books.find { it.id == done.firstBookId }, done.count, done.tracks, done.copied, onListen) }
            importing -> item(key = "run") { ImportRunning(vm.candidates.firstOrNull(), vm.operationDetail) }
            scanning -> item(key = "scan") {
                Column {
                    ScreenTitle(null, "Leggo tracce e capitoli…", 44)
                    Shimmer(Modifier.size(200.dp).clip(svRounded(22.dp)))
                    Spacer(Modifier.height(16.dp))
                    Shimmer(Modifier.fillMaxWidth(.7f).height(22.dp).clip(SvCircle))
                    Spacer(Modifier.height(10.dp))
                    Shimmer(Modifier.fillMaxWidth(.45f).height(16.dp).clip(SvCircle))
                }
            }
            preview -> importPreview(vm, relink)
            else -> item(key = "pick") {
                Column {
                    ScreenTitle(if (relink) "Ricollega" else null, if (relink) "Scegli di nuovo gli audio" else "Aggiungi alla tua libreria", 44)
                    Text(if (relink) "Scegli la stessa registrazione con lo stesso numero e ordine di file: posizione e segnalibri restano."
                        else "Scegli file già presenti sul dispositivo. Più file selezionati insieme diventano un solo libro.",
                        style = SvType.Body, color = sv.ink2)
                    Spacer(Modifier.height(22.dp))
                    PickCard(Icons.Rounded.MusicNote, "Scegli file", "MP3, M4B, M4A, OGG, FLAC e altri", onFiles)
                    Spacer(Modifier.height(12.dp))
                    if (!relink) PickCard(Icons.Rounded.Folder, "Scegli cartella", "Ogni sottocartella diventa un libro separato", onFolder)
                }
            }
        }
    }
}

@Composable
private fun PickCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val sv = LocalSv.current
    Row(Modifier.fillMaxWidth().clip(svRounded(24.dp)).background(sv.surface)
        .motionClickable(pressedScale = .97f, onClickLabel = title, onClick = onClick).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(SvCircle).background(sv.accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = sv.onAccent)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SvType.display(26.sp), color = sv.ink)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = SvType.Meta, color = sv.ink2)
        }
    }
}

@Composable
private fun Shimmer(modifier: Modifier) {
    val sv = LocalSv.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-1f, 2f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmer x")
    BoxWithConstraints(modifier.background(sv.surface)) {
        Box(Modifier.fillMaxHeight().width(maxWidth * .5f).offset(x = maxWidth * x)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, sv.surface2, Color.Transparent))))
    }
}

@UnstableApi
private fun LazyListScope.importPreview(vm: LibraryViewModel, relink: Boolean) {
    item(key = "preview_head") {
        val sv = LocalSv.current
        Column {
            ScreenTitle(if (relink) "Ricollega" else "Anteprima", if (relink) "Controlla la registrazione" else "Controlla l’importazione", 40)
            if (!relink) {
                ImportModeSwitch(vm.copyImports, vm.mustCopyImports) { vm.copyImports = it }
                Spacer(Modifier.height(10.dp))
                Text(if (vm.mustCopyImports) "Copia necessaria: questo archivio non concede accesso permanente."
                    else if (vm.copyImports) "Copia gli audio nello spazio privato di Sottovoce. Sicuro anche se gli originali vengono spostati."
                    else "Riproduce i file dove si trovano ora. Non occupa altro spazio.", style = SvType.Meta, color = sv.ink2)
                if (vm.candidates.any { it.tracks.size > 1 }) {
                    Spacer(Modifier.height(10.dp))
                    Text("Ogni file è un libro separato", Modifier.clip(SvCircle).motionClickable(onClick = vm::splitCandidates)
                        .padding(vertical = 8.dp), style = SvType.Label, color = sv.accent)
                }
            } else Text("Scegli la stessa registrazione con lo stesso numero e ordine di file. Una lettura diversa potrebbe non corrispondere.",
                style = SvType.Meta, color = SvError)
        }
    }
    vm.candidates.forEach { b ->
        item(key = "candidate:${b.id}") {
            val sv = LocalSv.current
            Column(Modifier.padding(top = 22.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    BookCover(b, rememberBookColors(b), Modifier.size(96.dp), radius = 16.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SvTextField(b.title, { vm.changeCandidate(b.id, title = it) }, "Titolo", Modifier.testTag("candidate_title"))
                        SvTextField(b.author, { vm.changeCandidate(b.id, author = it) }, "Autore")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row { Eyebrow("${b.tracks.size} ${if (b.tracks.size == 1) "traccia" else "tracce"}", sv.ink2); Spacer(Modifier.weight(1f))
                    Text(timeLabel(b.durationMs), style = SvType.MonoSmall, color = sv.ink2) }
                Spacer(Modifier.height(6.dp))
                b.tracks.take(200).forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((i + 1).toString().padStart(2, '0'), Modifier.width(30.dp), style = SvType.MonoSmall, color = sv.ink2)
                        Text(t.name, Modifier.weight(1f), style = SvType.BodySmall, color = sv.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timeLabel(t.durationMs), style = SvType.MonoSmall, color = sv.ink2)
                        IconCircle(Icons.Rounded.ArrowUpward, "Sposta su ${t.name}", 36.dp, sv.ink, enabled = i > 0, iconSize = 18.dp) {
                            vm.moveTrack(b.id, i, i - 1)
                        }
                    }
                }
                if (b.tracks.size > 200) Text("… e altre ${b.tracks.size - 200} tracce", style = SvType.MetaSmall, color = sv.ink2)
            }
        }
    }
}

@Composable
private fun ImportModeSwitch(copy: Boolean, forced: Boolean, onChange: (Boolean) -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).clip(SvCircle).background(sv.surface).padding(4.dp)) {
        val half = maxWidth / 2
        val x by androidx.compose.animation.core.animateDpAsState(if (copy) half else 0.dp, policy.emphasized(SvMotion.DurationIndicator), label = "modalità")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(SvCircle).background(sv.ink))
        Row(Modifier.fillMaxSize()) {
            listOf(false to "Collega gli originali", true to "Conserva una copia").forEach { (value, label) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(SvCircle)
                    .motionClickable(enabled = !forced || value, pressedScale = .97f, onClickLabel = label, role = Role.Tab) { onChange(value) }
                    .semantics { selected = copy == value }, contentAlignment = Alignment.Center) {
                    Text(label, style = SvType.Label.copy(fontSize = 13.sp), color = if (copy == value) sv.bg else sv.ink.copy(alpha = if (forced) .4f else 1f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ImportRunning(book: Book?, detail: String?) {
    val sv = LocalSv.current
    val veil = remember { Animatable(1f) }
    LaunchedEffect(Unit) { veil.animateTo(.12f, tween(8000, easing = SvMotion.Emphasized)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 30.dp)) {
        if (book != null) Box(Modifier.size(220.dp).clip(svRounded(22.dp))) {
            BookCover(book, rememberBookColors(book), Modifier.fillMaxSize(), radius = 22.dp)
            Box(Modifier.fillMaxWidth().fillMaxHeight(veil.value).background(sv.bg.copy(alpha = .78f)))
        }
        Spacer(Modifier.height(24.dp))
        Text("Importazione…", style = SvType.display(40.sp), color = sv.ink)
        Spacer(Modifier.height(8.dp))
        Text(detail ?: "Leggo i metadati e costruisco i capitoli", style = SvType.Meta, color = sv.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@UnstableApi
@Composable
private fun ImportDone(vm: LibraryViewModel, book: Book?, count: Int, tracks: Int, copied: Boolean, onListen: (String) -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { popped = true }
    val scale by animateFloatAsState(if (popped) 1f else .92f, policy.overshoot(600), label = "copertina importata")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 30.dp)) {
        if (book != null) BookCover(book, rememberBookColors(book), Modifier.size(220.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .coverShadow(22.dp, 18.dp), radius = 22.dp)
        Spacer(Modifier.height(24.dp))
        Text(if (count == 1) "Aggiunto alla libreria" else "$count libri aggiunti", style = SvType.display(40.sp), color = sv.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("$tracks ${if (tracks == 1) "traccia" else "tracce"} · ${if (copied) "copia conservata" else "collegato agli originali"}",
            style = SvType.Meta, color = sv.ink2)
        Spacer(Modifier.height(26.dp))
        if (book != null) PillButton("Inizia l’ascolto", sv.accent, sv.onAccent, Modifier.fillMaxWidth(), height = 58.dp,
            icon = Icons.Rounded.PlayArrow) { onListen(book.id) }
        Spacer(Modifier.height(10.dp))
        PillButton("Importa altro", Color.Transparent, sv.ink, Modifier.fillMaxWidth(), height = 52.dp, border = sv.line) { vm.importDone = null }
    }
}

// Settings ----------------------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun SettingsScreen(vm: LibraryViewModel, onBack: () -> Unit, onExport: () -> Unit, onRestore: () -> Unit, onInstall: () -> Unit) {
    val sv = LocalSv.current
    val context = LocalContext.current
    val storage by produceState(0L) { value = withContext(Dispatchers.IO) { File(context.filesDir, "books").walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    SubScaffold(onBack, "settings_view") {
        item { ScreenTitle(null, "Impostazioni") }
        item { Eyebrow("Aspetto", sv.ink2) }
        item(key = "theme_auto") {
            val ts = vm.themeSettings
            val subtitle = if (!ts.auto) "Disattivato · resta il tema che scegli" else when (ts.schedule) {
                ThemeSchedule.SYSTEM -> "Tema notte quando il sistema è scuro"
                ThemeSchedule.FIXED -> "Tema notte dalle 22:00 alle 07:00"
                ThemeSchedule.SUN -> SunTimes.of(LocalDate.now(), ZoneId.systemDefault())
                    ?.let { (rise, set) -> "Tema notte dal tramonto all’alba · circa ${clockLabel(set)}–${clockLabel(rise)}" }
                    ?: "Tema notte dal tramonto all’alba"
            }
            ToggleRow("Cambia tema di notte", subtitle, ts.auto, Modifier.padding(top = 6.dp).testTag("theme_auto")) { vm.changeThemeAuto(!ts.auto) }
        }
        if (vm.themeSettings.auto) item(key = "theme_schedule") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeSchedule.entries.forEach { option ->
                    val on = vm.themeSettings.schedule == option
                    Box(Modifier.height(36.dp).clip(SvCircle).background(if (on) sv.ink else Color.Transparent)
                        .border(1.5.dp, if (on) sv.ink else sv.line, SvCircle)
                        .motionClickable(pressedScale = .95f, onClickLabel = option.title, role = Role.RadioButton) { vm.changeThemeSchedule(option) }
                        .semantics { selected = on }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(option.title, style = SvType.MetaSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = if (on) sv.bg else sv.ink, maxLines = 1)
                    }
                }
            }
        }
        item(key = "themes") {
            val ts = vm.themeSettings
            val showingNight = vm.themePreviewNight ?: ts.isNight(isSystemInDarkTheme(), ZonedDateTime.now())
            Column(Modifier.testTag("theme_options")) {
                listOf(false, true).forEach { night ->
                    ThemeSlotHeader(night, if (night) ts.night else ts.day, showing = showingNight == night, auto = ts.auto) { vm.showThemeSide(night) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (if (night) AppTheme.nightThemes else AppTheme.dayThemes).forEach { theme ->
                            ThemeCard(theme, theme == (if (night) ts.night else ts.day)) { vm.changeTheme(theme.id) }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Ascolto") }
        item { ToggleRow("Ripresa intelligente", "Torna indietro di qualche secondo in base alla durata della pausa", vm.smartRewind) { vm.changeSmartRewind(!vm.smartRewind) } }
        item { ToggleRow("Timer notturno automatico", "Si avvia una volta per notte quando inizi ad ascoltare", vm.nightTimerEnabled) { vm.changeNightTimerEnabled(!vm.nightTimerEnabled) } }
        if (vm.nightTimerEnabled) item(key = "night_options") {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ValueChip("Dopo le ${"%02d:%02d".format(vm.nightTimerStartMinutes / 60, vm.nightTimerStartMinutes % 60)}", Modifier.weight(1f)) {
                    TimePickerDialog(context, { _, h, m -> vm.changeNightTimerStart(h * 60 + m) }, vm.nightTimerStartMinutes / 60, vm.nightTimerStartMinutes % 60, true).show()
                }
                ValueChip("${vm.nightTimerDuration} minuti", Modifier.weight(1f)) {
                    val options = listOf(15, 20, 30, 45, 60, 90)
                    vm.changeNightTimerDuration(options[(options.indexOf(vm.nightTimerDuration) + 1) % options.size])
                }
            }
        }
        item { ToggleRow("Dissolvenza finale", "Abbassa gradualmente il volume nell’ultimo minuto del timer", vm.timerFade) { vm.changeTimerFade(!vm.timerFade) } }
        item { ToggleRow("Scuoti per altri 10 minuti", "Funziona soltanto mentre un timer è attivo", vm.timerShakeExtend) { vm.changeTimerShakeExtend(!vm.timerShakeExtend) } }
        item { ToggleRow("Controlla aggiornamenti all’avvio", "Verifica un piccolo descrittore firmato su GitHub", vm.autoUpdateCheck) { vm.changeAutoUpdateCheck(!vm.autoUpdateCheck) } }
        item {
            val options = listOf(10 to 10, 15 to 30, 30 to 30, 60 to 60)
            SettingLink("Salti del lettore", "Indietro ${vm.skipBack} s · avanti ${vm.skipForward} s") {
                val next = options[(options.indexOf(vm.skipBack to vm.skipForward) + 1) % options.size]
                vm.setSkips(next.first, next.second)
            }
        }
        item { SectionTitle("Accesso rapido") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Widget", sv.surface, sv.ink, Modifier.weight(1f), icon = Icons.Rounded.Widgets) {
                    val manager = AppWidgetManager.getInstance(context)
                    if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, PlaybackWidgetProvider::class.java), null, null)
                    else vm.message = "Aggiungi il widget Sottovoce dal menu dei widget del launcher."
                }
                PillButton("Riquadro", sv.surface, sv.ink, Modifier.weight(1f), icon = Icons.Rounded.DashboardCustomize) {
                    if (Build.VERSION.SDK_INT >= 33) context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, PlaybackTileService::class.java), "Sottovoce", AndroidIcon.createWithResource(context, R.drawable.ic_tile), context.mainExecutor
                    ) { result -> vm.message = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                        "Riquadro Sottovoce disponibile nei comandi rapidi." else "Il riquadro non è stato aggiunto." }
                    else vm.message = "Apri i comandi rapidi, scegli Modifica e trascina il riquadro Sottovoce."
                }
            }
        }
        item { SectionTitle("Libreria e backup") }
        item {
            Text("Salva libreria, progressi, segnalibri e preferenze. Gli audio vanno conservati separatamente.", style = SvType.Meta, color = sv.ink2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Esporta", sv.ink, sv.bg, Modifier.weight(1f), onClick = onExport)
                PillButton("Ripristina", Color.Transparent, sv.ink, Modifier.weight(1f), border = sv.line, onClick = onRestore)
            }
        }
        if (vm.library.hasRecovery()) item { SettingLink("Recupera la libreria precedente", "Annulla l’ultimo ripristino", vm::recoverBackup) }
        item { SettingLink("Spazio gestito: ${storage / 1024 / 1024} MB", "Elimina le copie non più associate alla libreria") { vm.removeUnusedCopies() } }
        item { SettingLink("Elimina copie incomplete", "Pulisce copie interrotte a metà", vm::cleanIncompleteCopies) }
        item { SectionTitle("Informazioni") }
        item {
            Column(Modifier.fillMaxWidth().clip(svRounded(26.dp)).background(sv.surface).padding(20.dp)) {
                Text("Sottovoce ${BuildConfig.VERSION_NAME}", style = SvType.display(28.sp), color = sv.ink)
                Spacer(Modifier.height(6.dp))
                if (BuildConfig.DEBUG) Text("Versione di sviluppo. Installa l’APK release per gli aggiornamenti firmati.", style = SvType.Meta, color = sv.ink2)
                else {
                    Text("Solo il controllo degli aggiornamenti contatta GitHub; i tuoi audio non vengono mai inviati online.", style = SvType.Meta, color = sv.ink2)
                    Spacer(Modifier.height(14.dp))
                    val r = vm.release
                    if (r != null) {
                        Text("Disponibile ${r.versionName} · ${"%.1f".format(r.size / 1_048_576.0)} MB", style = SvType.Label, color = sv.ink)
                        Spacer(Modifier.height(10.dp))
                        PillButton(if (vm.updateInProgress) "Download ${(vm.updateProgress * 100).toInt()}%" else "Aggiorna e installa", sv.accent, sv.onAccent,
                            Modifier.fillMaxWidth(), enabled = r.minSdk <= Build.VERSION.SDK_INT && !vm.updateInProgress, onClick = onInstall)
                    } else {
                        PillButton("Controlla aggiornamenti", sv.bg, sv.ink, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, onClick = vm::checkUpdate)
                        if (vm.updateChecked) { Spacer(Modifier.height(8.dp)); Text("Sei alla versione più recente.", style = SvType.Meta, color = sv.accent) }
                    }
                }
            }
        }
        item {
            Text("Nessun account, pubblicità o tracciamento. Libreria e ascolto funzionano offline.", Modifier.padding(top = 18.dp), style = SvType.Meta, color = sv.ink2)
            Text("Codice e istruzioni su GitHub", Modifier.padding(top = 8.dp).clip(SvCircle)
                .motionClickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Adrianss31/sottovoce"))) }
                .padding(vertical = 8.dp), style = SvType.Label, color = sv.accent)
        }
    }
}

private fun clockLabel(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** "Giorno · Carta" with the in-use badge, or the button that shows that side. */
@Composable
private fun ThemeSlotHeader(night: Boolean, theme: AppTheme, showing: Boolean, auto: Boolean, onShow: () -> Unit) {
    val sv = LocalSv.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (night) Icons.Rounded.DarkMode else Icons.Rounded.LightMode, null, Modifier.size(18.dp), tint = sv.ink)
        Spacer(Modifier.width(9.dp))
        Text(buildAnnotatedString {
            append(if (night) "Notte" else "Giorno")
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = sv.ink2)) { append("  ${theme.title}") }
        }, Modifier.weight(1f), style = SvType.Body.copy(fontWeight = FontWeight.Medium), color = sv.ink, maxLines = 1)
        if (showing) Box(Modifier.height(26.dp).clip(svRounded(13.dp)).background(sv.accent).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center) {
            Text(if (auto) "VISIBILE" else "IN USO", style = SvType.EyebrowSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = .1.em), color = sv.onAccent)
        } else Box(Modifier.height(30.dp).clip(svRounded(15.dp)).border(1.5.dp, sv.line, svRounded(15.dp))
            .motionClickable(pressedScale = .95f, onClickLabel = if (auto) "Anteprima del tema ${if (night) "notte" else "giorno"}" else "Usa ${theme.title}", onClick = onShow)
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (auto) "Anteprima" else "Usa", style = SvType.MetaSmall.copy(fontWeight = FontWeight.Medium), color = sv.ink)
        }
    }
}

/** A live miniature of a theme: its display face, surface, accent, ink and texture. */
@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val sv = LocalSv.current
    val policy = LocalMotionPolicy.current
    val scale by animateFloatAsState(if (selected) 1f else .95f, policy.overshoot(400), label = "tema ${theme.id}")
    val p = svPalette(theme)
    val look = svStyle(theme)
    val corner = if (look.sharp) 0.dp else 20.dp
    Column(Modifier.width(112.dp).motionClickable(pressedScale = .93f, onClickLabel = theme.title, role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }) {
        Box(Modifier.fillMaxWidth().height(148.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (selected) {
                    val w = 2.5.dp.toPx()
                    drawRoundRect(sv.accent, Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w),
                        CornerRadius(corner.toPx() + w / 2), style = Stroke(w))
                }
            }
            .clip(RoundedCornerShape(corner)).background(p.bg).border(1.dp, Color.Gray.copy(alpha = .25f), RoundedCornerShape(corner))
            .clearAndSetSemantics { }) {
            Text("Aa", Modifier.padding(start = 12.dp, top = 8.dp), color = p.ink, style = TextStyle(fontFamily = look.display, fontSize = 42.sp,
                letterSpacing = look.displaySpacing.em, lineHeight = 46.sp,
                shadow = look.glow?.let { Shadow(it, Offset.Zero, look.glowRadius.value * LocalDensity.current.density) }))
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().height(46.dp)
                .clip(RoundedCornerShape(if (look.sharp) 0.dp else 12.dp)).background(p.surface).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(if (look.sharp) RectangleShape else CircleShape).background(p.accent))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(p.ink))
                    Box(Modifier.fillMaxWidth(.6f).height(3.dp).background(p.ink.copy(alpha = .45f)))
                }
            }
            ThemeTexture(look.fx, look.fxAlpha, p.dark)
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(sv.accent),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = sv.onAccent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(theme.title, Modifier.padding(horizontal = 2.dp), style = SvType.Label.copy(fontSize = 13.sp),
            color = if (selected) sv.ink else sv.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(theme.description, Modifier.padding(horizontal = 2.dp), style = SvType.MetaSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = sv.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ValueChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    val sv = LocalSv.current
    Box(modifier.height(44.dp).clip(SvCircle).background(sv.surface).motionClickable(pressedScale = .96f, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) { Text(label, style = SvType.Label, color = sv.ink) }
}

@Composable
private fun SettingLink(title: String, subtitle: String?, onClick: () -> Unit) {
    val sv = LocalSv.current
    Row(Modifier.fillMaxWidth().clip(svRounded(18.dp)).motionClickable(pressedScale = .98f, onClickLabel = title, onClick = onClick)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SvType.Label, color = sv.ink)
            subtitle?.let { Text(it, style = SvType.MetaSmall, color = sv.ink2) }
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = sv.ink2)
    }
}
