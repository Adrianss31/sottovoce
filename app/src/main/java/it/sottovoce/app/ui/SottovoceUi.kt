@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package it.sottovoce.app.ui

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.BuildConfig
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.R
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
import it.sottovoce.app.playback.PlaybackTileService
import it.sottovoce.app.playback.PlaybackWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val LightColors = lightColorScheme(primary = Color(0xFF234D38), onPrimary = Color(0xFFFFFBF4),
    background = Color(0xFFF8F5EE), surface = Color(0xFFF8F5EE), onSurface = Color(0xFF17231C),
    primaryContainer = Color(0xFFE7EDE2), onPrimaryContainer = Color(0xFF173725),
    secondaryContainer = Color(0xFFF1EAF7), onSecondaryContainer = Color(0xFF342B3A),
    surfaceVariant = Color(0xFFF0ECE3), onSurfaceVariant = Color(0xFF6E716C))
private val DarkColors = darkColorScheme(primary = Color(0xFFB1D2A5), onPrimary = Color(0xFF1B321C),
    background = Color(0xFF1D211E), surface = Color(0xFF1D211E), onSurface = Color(0xFFF3EFE5),
    primaryContainer = Color(0xFF354531), onPrimaryContainer = Color(0xFFE2EADC),
    surfaceVariant = Color(0xFF292E29), onSurfaceVariant = Color(0xFFB6BCAE))

private object SottovoceDesign {
    val Card = RoundedCornerShape(28.dp)
    val Soft = RoundedCornerShape(20.dp)
    val Cover = RoundedCornerShape(12.dp)
}

private val SottovoceTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
)

@UnstableApi
@Composable fun SottovoceUi(vm: LibraryViewModel) {
    val context = LocalContext.current
    val books by vm.library.books.collectAsStateWithLifecycle()
    val bookmarks by vm.library.bookmarks.collectAsStateWithLifecycle()
    val timer by PlaybackSignals.timer.collectAsStateWithLifecycle()
    val dark = when (vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var reorderId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val book = books.find { it.id == vm.selectedId }
    val active = books.find { it.id == vm.now.bookId }
    val last = active ?: books.firstOrNull { it.lastPlayedAt > 0 }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) vm.updateAndInstall { context.startActivity(it) }
    }
    val launchUpdateIntent: (Intent) -> Unit = { intent ->
        if (intent.action == android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) unknownSources.launch(intent)
        else context.startActivity(intent)
    }
    fun play(b: Book, index: Int? = null, position: Long? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.playBook(b, index, position)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                if (uris.isNotEmpty()) vm.importFiles(uris, data.flags)
            }
        } else vm.relinkId = null
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data -> data.data?.let { vm.importFolder(it, data.flags) } }
    }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(vm::exportBackup)
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(vm::readBackup)
    }
    fun pickFiles() {
        filesPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/octet-stream", "video/mp4"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    fun goBack() {
        if (reorderId != null) reorderId = null
        else { if (vm.screen == "import") { vm.candidates = emptyList(); vm.relinkId = null }; vm.screen = "library" }
    }
    BackHandler(vm.screen != "library" && vm.busy == null) { goBack() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.message = null } }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = SottovoceTypography) {
        Scaffold(
            modifier = Modifier.testTag("app_scaffold"),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Sottovoce", style = MaterialTheme.typography.titleLarge)
                } }, navigationIcon = { if (vm.screen != "library") IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Torna alla libreria") } },
                    actions = { if (vm.screen != "settings") IconButton(onClick = { vm.screen = "settings" }) { Icon(Icons.Default.Settings, "Impostazioni") } })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (last != null && !(vm.screen == "detail" && book?.id == last.id)) MiniPlayer(last, vm.now.playing && active != null,
                    onOpen = { vm.selectedId = last.id; vm.screen = "detail" },
                    onPlay = { if (active != null) vm.togglePlay() else play(last) })
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(vm.release != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    vm.release?.let { release ->
                        UpdateBanner(release.versionName, vm.updateInProgress, vm.updateProgress) {
                            vm.updateAndInstall(launchUpdateIntent)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { AnimatedContent(targetState = vm.screen,
                    transitionSpec = {
                        when {
                            initialState == "library" && targetState == "detail" ->
                                (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .82f, stiffness = 260f), .80f, TransformOrigin(.5f, .16f))) togetherWith
                                    (fadeOut(tween(150)) + scaleOut(tween(260), 1.045f, TransformOrigin(.5f, .22f)))
                            targetState == "library" ->
                                (fadeIn(tween(220)) + slideInHorizontally(tween(360)) { -it / 3 }) togetherWith
                                    (fadeOut(tween(180)) + slideOutHorizontally(tween(360)) { it })
                            else ->
                                (fadeIn(tween(220)) + slideInHorizontally(tween(360)) { it }) togetherWith
                                    (fadeOut(tween(180)) + slideOutHorizontally(tween(360)) { -it / 3 })
                        }.using(SizeTransform(clip = false))
                    }, label = "navigazione contestuale") { screen -> when (screen) {
                    "library" -> LibraryScreen(books, last, vm.now.bookId, vm.now.playing, vm, vm.stats,
                        onImport = { vm.relinkId = null; dialog = "import" },
                        onBook = { vm.selectedId = it.id; vm.screen = "detail" }, onPlay = { play(it) },
                        onSeries = vm::openSeries, onStats = vm::openStats)
                    "series" -> vm.selectedSeries?.let { key ->
                        val seriesBooks = books.filter { seriesKey(it.series) == seriesKey(key) }
                        val name = seriesBooks.firstOrNull()?.series?.trim()?.replace(Regex("\\s+"), " ") ?: key
                        SeriesScreen(name, seriesBooks, vm, vm.now.bookId, vm.now.playing,
                            onBook = { vm.selectedId = it.id; vm.screen = "detail" })
                    }
                    "stats" -> StatsScreen(vm.stats)
                    "detail" -> if (book != null) DetailScreen(book, bookmarks.filter { it.bookId == book.id }, vm.now.bookId == book.id, vm, timer,
                        onPlay = { index, position -> play(book, index, position) }, onEdit = { dialog = "edit" },
                        onSpeed = { dialog = "speed" }, onTimer = { dialog = "timer" }, onBookmark = { dialog = "bookmark" },
                        onRelink = { vm.relinkId = book.id; pickFiles() }, onComplete = { vm.markCompleted(book) },
                        onRemove = { dialog = "remove" }, onRemoveCopies = { dialog = "copies" },
                        onDeleteMark = { id -> vm.task("Rimozione…") { vm.library.removeBookmark(id) } })
                    "import" -> if (reorderId != null) ReorderScreen(vm, requireNotNull(reorderId)) else ImportPreview(vm, onReorder = { reorderId = it })
                    "settings" -> SettingsScreen(vm,
                        onTheme = { dialog = "theme" }, onSkips = { dialog = "skips" },
                        onNightDuration = { dialog = "nightDuration" },
                        onBackup = { backupExport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "sottovoce-backup.json").putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                        onRestore = { backupImport.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                        onInstall = { vm.updateAndInstall(launchUpdateIntent) })
                } } }
            }
        }
        when (dialog) {
            "import" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Importa audiolibri") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scegli file già presenti sul dispositivo. Più file selezionati insieme diventano un solo libro.")
                    Button(onClick = { dialog = null; pickFiles() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Scegli file") }
                    OutlinedButton(onClick = { dialog = null; folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .putExtra(Intent.EXTRA_LOCAL_ONLY, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Scegli cartella")
                    }
                    Text("In una cartella, ogni sottocartella di primo livello viene proposta come libro separato.", style = MaterialTheme.typography.bodySmall)
                } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Chiudi") } })
            "edit" -> if (book != null) EditBookDialog(book, onDismiss = { dialog = null }) { title, author, narrator, series, position -> vm.saveMetadata(book, title, author, narrator, series, position); dialog = null }
            "speed" -> if (book != null) ChoiceDialog("Velocità di ascolto", listOf(.5f,.75f,1f,1.1f,1.25f,1.5f,1.75f,2f,2.5f,3f).map { it.toString()+"×" to it }, if (vm.now.bookId == book.id) vm.now.speed else book.speed, { dialog = null }) { vm.speed(book, it); dialog = null }
            "timer" -> TimerDialog({ dialog = null }) { vm.timer(it); dialog = null }
            "nightDuration" -> ChoiceDialog("Durata del timer notturno", listOf(15,20,30,45,60,90).map { "$it minuti" to it }, vm.nightTimerDuration, { dialog = null }) { vm.changeNightTimerDuration(it); dialog = null }
            "theme" -> ChoiceDialog("Aspetto", listOf("Come il sistema" to "system","Chiaro" to "light","Scuro" to "dark"), vm.theme, { dialog = null }) { vm.changeTheme(it); dialog = null }
            "skips" -> ChoiceDialog("Salti del lettore", listOf("Indietro 10 s · avanti 10 s" to (10 to 10),"Indietro 15 s · avanti 30 s" to (15 to 30),"Indietro 30 s · avanti 30 s" to (30 to 30),"Indietro 60 s · avanti 60 s" to (60 to 60)), vm.skipBack to vm.skipForward, { dialog = null }) { vm.setSkips(it.first,it.second); dialog = null }
            "bookmark" -> NoteDialog({ dialog = null }) { vm.addBookmark(it); dialog = null }
            "remove", "copies" -> if (book != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == "copies") "Eliminare le copie nell’app?" else "Rimuovere il libro?") },
                text = { Text(if (dialog == "copies") "Verranno eliminate solo le copie audio gestite dall’app. Progressi e segnalibri rimangono; dovrai ricollegare gli audio. I file originali non saranno toccati." else "Il libro, i suoi progressi, i segnalibri e le eventuali copie audio nell’app saranno rimossi. I file originali scelti dal dispositivo non saranno toccati.") },
                confirmButton = { TextButton(onClick = { vm.removeBook(book, dialog == "copies"); dialog = null }) { Text("Rimuovi", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } })
        }
        vm.pendingBackup?.let { backup -> AlertDialog(onDismissRequest = { vm.pendingBackup = null }, title = { Text("Ripristinare ${backup.books.size} libri?") },
            text = { Text("Sostituirà la libreria corrente e i segnalibri. Una copia di sicurezza dei dati correnti verrà conservata nell’app. Gli audio non vengono cancellati né inclusi nel backup: dovrai ricollegarli.") },
            confirmButton = { TextButton(onClick = vm::restoreBackup) { Text("Ripristina") } }, dismissButton = { TextButton(onClick = { vm.pendingBackup = null }) { Text("Annulla") } }) }
        vm.busy?.let { label -> AlertDialog(onDismissRequest = {}, title = { Text(label) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (label.startsWith("Scaricamento")) LinearProgressIndicator(progress = { vm.updateProgress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("I file originali e la versione installata restano al sicuro.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = vm::cancelTask) { Text("Annulla") } }) }
    }
}

@Composable private fun UpdateBanner(version: String, downloading: Boolean, progress: Float, onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Aggiornamento disponibile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (downloading) "Download ${(progress * 100).toInt()}%" else "Sottovoce $version è pronto", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onUpdate, enabled = !downloading, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(if (downloading) "Attendi" else "Aggiorna")
                }
            }
            if (downloading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@UnstableApi
@Composable private fun LibraryScreen(books: List<Book>, last: Book?, activeId: String?, playing: Boolean, vm: LibraryViewModel, stats: ListeningStats?,
    onImport: () -> Unit, onBook: (Book) -> Unit, onPlay: (Book) -> Unit, onSeries: (String) -> Unit, onStats: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    var sort by rememberSaveable { mutableStateOf("Recenti") }
    var sortOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val filtered = books.filter { b ->
        (b.title+" "+b.author+" "+b.narrator+" "+b.series).contains(search, ignoreCase = true) && when (filter) {
            "In ascolto" -> b.lastPlayedAt > 0 && !b.completed
            "Da iniziare" -> b.lastPlayedAt == 0L && !b.completed
            "Completati" -> b.completed
            else -> true
        }
    }.let { if (sort == "Titolo") it.sortedWith { a,b -> NaturalOrder.compare(a.title,b.title) }
        else if (sort == "Autore") it.sortedBy { b -> b.author.lowercase() }
        else if (sort == "Serie") it.sortedWith(compareBy<Book> { it.series.isBlank() }.thenBy { it.series.lowercase() }
            .thenBy { it.seriesPosition ?: Int.MAX_VALUE }.thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
        else it.sortedByDescending { b -> b.lastPlayedAt.coerceAtLeast(b.createdAt) } }
    val seriesCount = filtered.filter { it.series.isNotBlank() }.map { seriesKey(it.series) }.toSet().size
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), modifier = Modifier.fillMaxSize().testTag("library"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("La tua libreria", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
            val listening = books.count { it.lastPlayedAt > 0 && !it.completed }
            Text("${books.size} ${if (books.size == 1) "audiolibro" else "audiolibri"} · $listening in ascolto", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (last != null) item(span = { GridItemSpan(maxLineSpan) }) {
            ContinueListeningCard(last, activeId == last.id, playing, vm.skipBack, vm.skipForward,
                onOpen = { onBook(last) }, onBack = { if (activeId == last.id) vm.skip(-vm.skipBack) else onPlay(last) },
                onToggle = { if (activeId == last.id) vm.togglePlay() else onPlay(last) },
                onForward = { if (activeId == last.id) vm.skip(vm.skipForward) else onPlay(last) })
        }
        if (stats != null && books.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            ListeningSummaryCard(stats, onStats)
        }
        if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.LibraryBooks, null, Modifier.size(40.dp))
                Text("La prossima storia è già tua", style = MaterialTheme.typography.headlineSmall)
                Text("Importa un MP3, un M4B o una cartella di capitoli. Nessun account, nessun catalogo online.")
                Button(onClick = onImport, Modifier.fillMaxWidth().testTag("import_button")) { Icon(Icons.Default.Add, null); Text("Importa audiolibri") }
            }
        } } else {
            item(span = { GridItemSpan(maxLineSpan) }) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Tutti i libri", style = MaterialTheme.typography.headlineSmall)
                    Text("${filtered.size} ${if (filtered.size == 1) "titolo" else "titoli"}${if (seriesCount > 0) " · $seriesCount serie" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onImport) { Icon(Icons.Default.Add, null); Text("Aggiungi") }
                IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Default.Search, if (searchOpen) "Chiudi ricerca" else "Cerca libri") }
                IconButton(onClick = { vm.changeLibraryViewMode(if (vm.libraryViewMode == "grid") "compact" else "grid") }) {
                    Icon(if (vm.libraryViewMode == "grid") Icons.Default.ViewAgenda else Icons.Default.GridView,
                        if (vm.libraryViewMode == "grid") "Vista compatta" else "Vista a griglia")
                }
                Box { IconButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, "Ordina libri") }
                    DropdownMenu(sortOpen, { sortOpen = false }) { listOf("Recenti","Titolo","Autore","Serie").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortOpen = false }) } }
                }
            } }
            if (searchOpen) item(span = { GridItemSpan(maxLineSpan) }) { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("library_search"),
                placeholder = { Text("Titolo, autore, narratore o serie") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = SottovoceDesign.Soft) }
            item(span = { GridItemSpan(maxLineSpan) }) { LibraryFilterBar(filter) { filter = it } }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage("Nessun libro corrisponde alla ricerca.") }
            else {
                val entries = groupForLibrary(filtered, books)
                val seriesEntries = entries.filterIsInstance<LibraryEntry.SeriesGroup>()
                val singleEntries = entries.filterIsInstance<LibraryEntry.Single>()
                if (seriesEntries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionTitle("Serie", "Tocca una serie per vedere tutti i volumi") }
                    seriesEntries.forEach { entry ->
                        item(key = "series:${entry.key}", span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) {
                            SeriesCard(entry.name, entry.books, entry.totalCount, vm.libraryViewMode == "compact", activeId, playing) { onSeries(entry.key) }
                        }
                    }
                }
                if (singleEntries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionTitle(if (seriesEntries.isEmpty()) "Libri" else "Altri libri", null) }
                    singleEntries.forEach { entry ->
                        val b = entry.book
                        item(key = b.id, span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) {
                            LibraryBookItem(b, vm.libraryViewMode, activeId == b.id, playing && activeId == b.id) { onBook(b) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LibraryBookItem(book: Book, mode: String, active: Boolean, playing: Boolean, onOpen: () -> Unit) {
    if (mode == "compact") AudiobookCompactItem(book, active, playing, onOpen)
    else AudiobookGridItem(book, active, playing, onOpen)
}

@Composable private fun LibraryFilterBar(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("Tutti","In ascolto","Da iniziare","Completati").forEach { label ->
            val color by animateColorAsState(if (selected == label) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tween(190), label = "filtro $label")
            Box(Modifier.weight(1f).clip(CircleShape).background(color).clickable { onSelect(label) }.padding(horizontal = 5.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable private fun ContinueListeningCard(book: Book, active: Boolean, playing: Boolean, back: Int, forward: Int,
    onOpen: () -> Unit, onBack: () -> Unit, onToggle: () -> Unit, onForward: () -> Unit) {
    val chapter = book.currentChapter()
    val chapterProgress = if (book.completed) 1f else chapter?.progress(book.positionMs) ?: book.progress
    val progress by animateFloatAsState(chapterProgress, tween(500), label = "progresso capitolo")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressione ultimo libro")
    Surface(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer, shape = SottovoceDesign.Card, shadowElevation = 2.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                Cover(book, Modifier.width(106.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedContent(targetState = active && playing, label = "stato riproduzione") { isPlaying ->
                            Icon(if (isPlaying) Icons.Default.GraphicEq else Icons.Default.History, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(if (active && playing) "IN RIPRODUZIONE" else if (active) "IN PAUSA" else "ULTIMO ASCOLTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(chapter?.title ?: "Audiolibro", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(chapter?.let { "${timeLabel(it.elapsedMs(book.positionMs))} · ${timeLabel(it.remainingMs(book.positionMs))} rimasti" } ?: timeLabel(book.durationMs),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .65f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipButton(back, true, onBack)
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) { AnimatedContent(playing, label = "hero play pausa") {
                    Icon(if (it) Icons.Default.Pause else Icons.Default.PlayArrow, if (it) "Pausa" else "Riprendi", Modifier.size(32.dp))
                } }
                SkipButton(forward, false, onForward)
            }
        }
    }
}

@Composable private fun SkipButton(seconds: Int, back: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = CircleShape, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)) {
        Icon(if (back) Icons.Default.Replay else Icons.Default.Forward30, if (back) "Indietro $seconds secondi" else "Avanti $seconds secondi", Modifier.size(19.dp))
        Spacer(Modifier.width(4.dp)); Text("$seconds s", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun AudiobookGridItem(book: Book, active: Boolean, playing: Boolean, onOpen: () -> Unit) {
    val chapter = book.currentChapter()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressione libro")
    Column(Modifier.fillMaxWidth().testTag("book_${book.id}").graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box {
                Cover(book, Modifier.fillMaxWidth().shadow(2.dp, SottovoceDesign.Cover))
                if (active || book.completed) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(99.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (active && playing) Icons.Default.GraphicEq else Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (active && playing) "In ascolto" else if (active) "In pausa" else "Completato", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "Autore non indicato" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.series.isNotBlank()) Text(seriesLabel(book), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.needsRelink) Text("File da ricollegare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else {
                    Text(when { book.completed -> "✓ Completato"; book.lastPlayedAt == 0L -> "Da iniziare";
                        else -> chapter?.let { "${it.title} · ${timeLabel(it.remainingMs(book.positionMs))} rimasti" } ?: "In ascolto" },
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (book.lastPlayedAt > 0 || book.completed) LinearProgressIndicator(progress = { if (book.completed) 1f else book.progress },
                        Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
    }
}

private fun seriesLabel(book: Book): String = book.series + (book.seriesPosition?.let { " · Libro $it" } ?: "")

@Composable private fun AudiobookCompactItem(book: Book, active: Boolean, playing: Boolean, onOpen: () -> Unit) {
    val chapter = book.currentChapter()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressione riga libro")
    Surface(Modifier.fillMaxWidth().testTag("book_${book.id}").graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen), shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Cover(book, Modifier.width(58.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "Autore non indicato" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.series.isNotBlank()) Text(seriesLabel(book), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    book.needsRelink -> "File da ricollegare"
                    book.completed -> "Completato"
                    book.lastPlayedAt == 0L -> "Da iniziare · ${timeLabel(book.durationMs)}"
                    else -> chapter?.let { "${it.title} · ${timeLabel(it.remainingMs(book.positionMs))} rimasti" } ?: "In ascolto"
                }, style = MaterialTheme.typography.labelSmall, color = if (book.needsRelink) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.lastPlayedAt > 0 || book.completed) LinearProgressIndicator(progress = { if (book.completed) 1f else book.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
            }
            Icon(if (active && playing) Icons.Default.GraphicEq else Icons.Default.ChevronRight,
                if (active && playing) "In riproduzione" else null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun LibrarySectionTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun ListeningSummaryCard(stats: ListeningStats, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressione statistiche")
    Surface(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        shape = SottovoceDesign.Card, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                    Icon(Icons.Default.Insights, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Il tuo ascolto", style = MaterialTheme.typography.titleMedium)
                    Text(if (stats.weekMs > 0) "Questa settimana" else "Inizia a raccogliere i tuoi dati",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Vedi tutto", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBlock("Settimana", humanDuration(stats.weekMs), Modifier.weight(1f))
                StatBlock("Oggi", humanDuration(stats.todayMs), Modifier.weight(1f))
                StatBlock("Giorni attivi", "${stats.activeDaysLast7}/7", Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun SeriesCard(name: String, entries: List<Book>, totalCount: Int, compact: Boolean, activeId: String?, playing: Boolean, onOpen: () -> Unit) {
    if (entries.isEmpty()) return
    val totalDuration = entries.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = entries.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (totalDuration > 0) (played.toFloat() / totalDuration).coerceIn(0f, 1f) else if (entries.all { it.completed }) 1f else 0f
    val current = entries.firstOrNull { it.id == activeId } ?: entries.firstOrNull { !it.completed && it.lastPlayedAt > 0 } ?: entries.firstOrNull { !it.completed }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "pressione serie")
    Surface(Modifier.fillMaxWidth().testTag("series_card_$name").graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        shape = SottovoceDesign.Soft, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f), shadowElevation = 1.dp) {
        if (compact) {
            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                SeriesMosaic(entries, Modifier.width(82.dp))
                SeriesCardInfo(name, entries, totalCount, progress, current, playing && current?.id == activeId, Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, "Apri la serie", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SeriesMosaic(entries, Modifier.fillMaxWidth())
                SeriesCardInfo(name, entries, totalCount, progress, current, playing && current?.id == activeId, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun SeriesCardInfo(name: String, entries: List<Book>, totalCount: Int, progress: Float, current: Book?, isPlaying: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val countLabel = if (entries.size == totalCount) "$totalCount ${if (totalCount == 1) "libro" else "libri"}" else "${entries.size} di $totalCount volumi"
        Text("$countLabel · ${"%.0f".format(progress * 100)}% completato",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        current?.let { Text(if (isPlaying) "In ascolto · ${it.title}" else "Prossimo · ${it.title}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
    }
}

@Composable private fun SeriesMosaic(entries: List<Book>, modifier: Modifier) {
    val shown = entries.take(4)
    Box(modifier.aspectRatio(1.52f).clip(SottovoceDesign.Cover).background(MaterialTheme.colorScheme.surface)) {
        if (shown.size <= 2) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            shown.forEach { Cover(it, Modifier.weight(1f).fillMaxHeight()) }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                shown.take(2).forEach { Cover(it, Modifier.weight(1f).fillMaxHeight()) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                shown.drop(2).take(2).forEach { Cover(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        if (entries.size > 4) Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Text("+${entries.size - 4}", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
@UnstableApi
@Composable private fun SeriesScreen(name: String, entries: List<Book>, vm: LibraryViewModel, activeId: String?, playing: Boolean, onBook: (Book) -> Unit) {
    val sorted = entries.sortedWith(compareBy<Book> { it.seriesPosition ?: Int.MAX_VALUE }
        .thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
    val totalDuration = entries.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = entries.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (totalDuration > 0) (played.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), modifier = Modifier.fillMaxSize().testTag("series_view"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary)
                Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${entries.size} ${if (entries.size == 1) "libro" else "libri"} · ${entries.count { it.completed }} completati · ${"%.0f".format(progress * 100)}% completato", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
        } }
        if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage("Nessun libro in questa serie.") }
        else gridItems(sorted, key = { it.id }, span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) { b ->
            LibraryBookItem(b, vm.libraryViewMode, activeId == b.id, playing && activeId == b.id) { onBook(b) }
        }
    }
}
@Composable private fun Cover(book: Book, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) { runCatching { book.coverPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull() }
    }
    val colors = listOf(Color(0xFFE3B786), Color(0xFFB4C8CE), Color(0xFFD6B0A3))
    BoxWithConstraints(modifier.aspectRatio(2f/3f).clip(SottovoceDesign.Cover).background(colors[(book.title.hashCode() and Int.MAX_VALUE)%colors.size])) {
        if (image != null) Image(requireNotNull(image), "Copertina di ${book.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (maxWidth < 72.dp) Text(book.title.trim().take(1).uppercase(), Modifier.align(Alignment.Center),
            fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Color(0xFF302C22))
        else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Color(0xFF302C22), maxLines = 5, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.Headphones, null, tint = Color(0xFF514632), modifier = Modifier.align(Alignment.End))
        }
    }
}
@Composable private fun MiniPlayer(book: Book, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val chapter = book.currentChapter()
    val targetProgress = if (book.completed) 1f else chapter?.progress(book.positionMs) ?: book.progress
    val progress by animateFloatAsState(targetProgress, tween(450), label = "mini progresso")
    Surface(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).animateContentSize(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(book, Modifier.width(42.dp))
                    Column { Text(chapter?.title ?: book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${book.title} · ${if (playing) "In riproduzione" else "In pausa"}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(onClick = onPlay) { AnimatedContent(playing, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "mini play pausa") { isPlaying ->
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausa" else "Riprendi ascolto")
                } }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
@Composable private fun StatsScreen(stats: ListeningStats?) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Il tuo ascolto", style = MaterialTheme.typography.headlineLarge)
            Text("Tutto resta sul dispositivo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        val s = stats
        if (s == null) item {}
        else if (s.totalMs == 0L && s.completedBooks == 0) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Insights, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Inizia ad ascoltare", style = MaterialTheme.typography.titleMedium)
                Text("Qui vedrai il tempo ascoltato, i giorni attivi e i libri che stai portando avanti. I dati non lasciano mai il dispositivo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } else {
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Questa settimana", style = MaterialTheme.typography.titleMedium)
                    Text(humanDuration(s.weekMs), style = MaterialTheme.typography.displaySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBlock("Oggi", humanDuration(s.todayMs), Modifier.weight(1f))
                        StatBlock("Giorni attivi", "${s.activeDaysLast7}/7", Modifier.weight(1f))
                        StatBlock("Continuità", if (s.currentStreak > 0) "${s.currentStreak} giorni" else "—", Modifier.weight(1f))
                    }
                }
            } }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { DailyBars(s.days) }
            } }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Andamento mensile", style = MaterialTheme.typography.titleMedium)
                    MonthlyBars(s.months)
                }
            } }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Avanzamento libreria", style = MaterialTheme.typography.titleMedium)
                    ProgressLine("Libri completati", s.completedBooks, s.totalBooks)
                    if (s.totalSeries > 0) ProgressLine("Serie completate", s.completedSeries, s.totalSeries)
                }
            } }
            if (s.topBooks.isNotEmpty()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Più ascoltati", style = MaterialTheme.typography.titleMedium)
                    s.topBooks.forEach { (title, ms) -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(humanDuration(ms), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Totale registrato: ${humanDuration(s.totalMs)} · questo mese: ${humanDuration(s.thisMonthMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Il tempo viene conteggiato da quando questa funzione è stata attivata e non viene incluso nei backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}
@Composable private fun StatBlock(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
@Composable private fun MonthlyBars(months: List<MonthStat>) {
    val max = months.maxOf { it.durationMs }.coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ultimi 6 mesi", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            months.forEach { m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth()
                        .height((m.durationMs.toFloat() / max * 80).dp.coerceAtLeast(if (m.durationMs > 0) 6.dp else 2.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.height(5.dp))
                    Text(m.label, style = MaterialTheme.typography.labelSmall)
                    Text(if (m.durationMs > 0) shortDuration(m.durationMs) else "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun DailyBars(days: List<DayStat>) {
    val max = days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ultimi 7 giorni", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((day.durationMs.toFloat() / max * 80).dp.coerceAtLeast(if (day.durationMs > 0) 6.dp else 2.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.height(5.dp))
                    Text(day.label, style = MaterialTheme.typography.labelSmall)
                    Text(if (day.durationMs > 0) shortDuration(day.durationMs) else "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@Composable private fun ProgressLine(label: String, value: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$value di $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { if (total > 0) value.toFloat() / total else 0f },
            modifier = Modifier.width(90.dp).height(5.dp).clip(CircleShape))
    }
}
private fun humanDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "%dh %02dmin".format(minutes / 60, minutes % 60) else "$minutes min"
}
private fun shortDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"
}

@UnstableApi
@Composable private fun DetailScreen(book: Book, bookmarks: List<Bookmark>, active: Boolean, vm: LibraryViewModel, timer: String,
    onPlay: (Int?,Long?) -> Unit, onEdit: () -> Unit, onSpeed: () -> Unit, onTimer: () -> Unit, onBookmark: () -> Unit,
    onRelink: () -> Unit, onComplete: () -> Unit, onRemove: () -> Unit, onRemoveCopies: () -> Unit, onDeleteMark: (String) -> Unit) {
    val now = vm.now
    val playing = active && now.playing
    val shownTrack = if (active) now.trackIndex else book.trackIndex
    val shownPosition = if (active) now.position else book.positionMs
    val shownSpeed = if (active) now.speed else book.speed
    val displayBook = if (active) book.copy(trackIndex = shownTrack, positionMs = shownPosition, speed = shownSpeed) else book
    val timeline = displayBook.chapterTimeline()
    val current = displayBook.currentChapter(shownTrack, shownPosition)
    val completed = timeline.count { displayBook.chapterStatus(it) == ChapterStatus.COMPLETED }
    val trackDuration = if (active) now.duration.takeIf { it > 0 } ?: book.tracks.getOrNull(shownTrack)?.durationMs.orZero()
        else book.tracks.getOrNull(shownTrack)?.durationMs.orZero()
    val chapterStart = current?.startMs ?: 0
    val chapterEnd = current?.endMs?.takeIf { it > chapterStart } ?: trackDuration
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0)
    var dragging by remember(book.id, current?.ordinal) { mutableStateOf<Float?>(null) }
    val chapterElapsed = dragging?.toLong() ?: (shownPosition - chapterStart).coerceIn(0, chapterDuration)
    val totalPlayed = displayBook.playedMs
    var section by rememberSaveable(book.id) { mutableStateOf("chapters") }
    var coverExpanded by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) { coverExpanded = true }
    val coverWidth by animateDpAsState(if (coverExpanded) 122.dp else 76.dp,
        spring(dampingRatio = .76f, stiffness = 220f), label = "copertina che si apre")
    LazyColumn(Modifier.testTag("book_detail"), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Surface(shape = SottovoceDesign.Card, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 1.dp) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.width(coverWidth))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    AnimatedVisibility(active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.PauseCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(if (playing) "IN RIPRODUZIONE" else "IN PAUSA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.titleSmall)
                    if (book.series.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.CollectionsBookmark, "Serie", Modifier.size(16.dp)); Text(seriesLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (book.narrator.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.Mic, "Narratore", Modifier.size(16.dp)); Text("Letto da ${book.narrator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, "Durata", Modifier.size(15.dp)); Text(timeLabel(book.durationMs), style = MaterialTheme.typography.bodySmall)
                        Text("•"); Icon(Icons.Default.MenuBook, "Capitoli", Modifier.size(15.dp)); Text("${timeline.size} capitoli", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }
        item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = SottovoceDesign.Card, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current?.title ?: "Capitolo", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        current?.let { Text("Capitolo ${it.ordinal} di ${it.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (active) Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                }
                Slider(value = chapterElapsed.toFloat().coerceIn(0f, chapterDuration.coerceAtLeast(1).toFloat()),
                    onValueChange = { dragging = it }, valueRange = 0f..chapterDuration.coerceAtLeast(1).toFloat(),
                    enabled = chapterDuration > 0 && !book.needsRelink,
                    onValueChangeFinished = {
                        dragging?.let { value -> if (active) vm.seek(chapterStart + value.toLong()) else onPlay(shownTrack, chapterStart + value.toLong()) }
                        dragging = null
                    }, modifier = Modifier.testTag("seek_slider"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeLabel(chapterElapsed), style = MaterialTheme.typography.bodySmall)
                    Text("−${timeLabel(((chapterDuration - chapterElapsed).coerceAtLeast(0) / shownSpeed).toLong())}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Libro: ${timeLabel(totalPlayed)} / ${timeLabel(book.durationMs)} · ${timeLabel(((book.durationMs-totalPlayed).coerceAtLeast(0)/shownSpeed).toLong())} rimasti",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedContent(targetState = active, transitionSpec = {
                    (fadeIn(tween(220)) + expandVertically(tween(280), expandFrom = Alignment.CenterVertically)) togetherWith
                        (fadeOut(tween(140)) + shrinkVertically(tween(220), shrinkTowards = Alignment.CenterVertically))
                }, label = "comandi integrati") { isActive ->
                    if (!isActive) Button(onClick = { if (book.needsRelink) onRelink() else onPlay(null, null) },
                        Modifier.fillMaxWidth().height(52.dp).testTag("start_playback")) {
                        Icon(if (book.needsRelink) Icons.Default.FolderOpen else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (book.needsRelink) "Ricollega file" else if (book.lastPlayedAt > 0) "Riprendi l’ascolto" else "Inizia l’ascolto")
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkipButton(vm.skipBack, true) { vm.skip(-vm.skipBack) } }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp).testTag("play_pause")) {
                                    AnimatedContent(playing, transitionSpec = { scaleIn(tween(180), .45f) + fadeIn() togetherWith scaleOut(tween(130), 1.45f) + fadeOut() }, label = "play pausa contestuale") { isPlaying ->
                                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pausa" else "Riprendi ascolto", Modifier.size(36.dp))
                                    }
                                }
                            }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkipButton(vm.skipForward, false) { vm.skip(vm.skipForward) } }
                        }
                        Text(if (playing) "In riproduzione" else "In pausa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerTool(Icons.Default.Speed, "${shownSpeed}×", true, onSpeed, Modifier.weight(1f))
                    PlayerTool(Icons.Default.Timer, if (timer.isEmpty()) "Timer" else timer, active, onTimer, Modifier.weight(1f))
                    PlayerTool(Icons.Default.BookmarkAdd, "Segnalibro", active, onBookmark, Modifier.weight(1f))
                }
                AnimatedVisibility(active && timer.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text("Spegnimento: $timer", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick=onEdit, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Edit,null); Spacer(Modifier.width(6.dp)); Text("Dettagli") }
            FilledTonalButton(onClick=onComplete, modifier=Modifier.weight(1f)) { Icon(if(book.completed) Icons.Default.RestartAlt else Icons.Default.CheckCircle,null); Spacer(Modifier.width(6.dp)); Text(if(book.completed) "Riapri" else "Finito") }
            IconButton(onClick=onRelink) { Icon(Icons.Default.FolderOpen,"Ricollega file") }
        } }
        item { ChapterBookmarkSelector(section, timeline.size, bookmarks.size) { section = it } }
        item { AnimatedContent(targetState = section, transitionSpec = {
            if (targetState == "bookmarks") (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        }, label = "sezione libro direzionale") { selected -> Column {
            Text(if(selected == "chapters") "$completed di ${timeline.size} completati" else "Passaggi salvati",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if(selected == "chapters" && book.tracks.size==1 && book.tracks.first().chapters.isEmpty())
                Text("Nessun capitolo incorporato riconosciuto: ascolto come traccia unica.",style=MaterialTheme.typography.bodySmall)
        } } }
        if (section == "chapters") items(timeline, key = { "${it.trackIndex}:${it.startMs}:${it.ordinal}" }) { chapter ->
            CompactChapterRow(displayBook, chapter, !book.needsRelink) { onPlay(chapter.trackIndex, chapter.startMs) }
        } else if(bookmarks.isEmpty()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.BookmarkBorder,null,Modifier.size(30.dp)); Text("Nessun segnalibro")
                Text("Aggiungili dai comandi di ascolto qui sopra.", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
            }
        } } else items(bookmarks,key={it.id}) { mark -> BookmarkRow(book, mark, !book.needsRelink,
            onOpen = { onPlay(mark.trackIndex,mark.positionMs) }, onDelete = { onDeleteMark(mark.id) }) }
        item { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)); Text("Gestione del libro", style = MaterialTheme.typography.titleMedium) }
        if(book.tracks.any { it.owned }) item { OutlinedButton(onClick=onRemoveCopies,Modifier.fillMaxWidth()){Icon(Icons.Default.CleaningServices,null);Spacer(Modifier.width(8.dp));Text("Elimina copie audio nell’app")} }
        item { TextButton(onClick=onRemove,Modifier.fillMaxWidth()){Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error);Spacer(Modifier.width(8.dp));Text("Rimuovi dalla libreria",color=MaterialTheme.colorScheme.error)} }
    }
}

@Composable private fun PlayerTool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, label, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun ChapterBookmarkSelector(selected: String, chapters: Int, bookmarks: Int, onSelect: (String) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            listOf("chapters" to "Capitoli  $chapters", "bookmarks" to "Segnalibri  $bookmarks").forEach { (key, label) ->
                val color by animateColorAsState(if (selected == key) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, tween(220), label = "selettore $key")
                Row(Modifier.weight(1f).clip(CircleShape).background(color).clickable { onSelect(key) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(if (key == "chapters") Icons.Default.FormatListNumbered else Icons.Default.Bookmarks, label, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun Long?.orZero(): Long = this ?: 0L

@Composable private fun BookmarkRow(book: Book, mark: Bookmark, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val chapter = book.currentChapter(mark.trackIndex, mark.positionMs)
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(mark.note.ifBlank { "Segnalibro" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${chapter?.title ?: "Traccia ${mark.trackIndex+1}"} · ${timeLabel((mark.positionMs-(chapter?.startMs?:0)).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick=onDelete){Icon(Icons.Default.Delete,"Elimina segnalibro")}
        }
    }
}

@Composable private fun CompactChapterRow(book: Book, chapter: BookChapter, enabled: Boolean, onPlay: () -> Unit) {
    val status = book.chapterStatus(chapter)
    val current = status == ChapterStatus.CURRENT
    val foreground = when (status) {
        ChapterStatus.COMPLETED, ChapterStatus.CURRENT -> MaterialTheme.colorScheme.primary
        ChapterStatus.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container by animateColorAsState(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(300), label = "stato capitolo")
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onPlay),
        color = container,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedContent(status, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "icona capitolo") { chapterStatus ->
                    Icon(when (chapterStatus) {
                        ChapterStatus.COMPLETED -> Icons.Default.CheckCircle
                        ChapterStatus.CURRENT -> Icons.Default.GraphicEq
                        ChapterStatus.UPCOMING -> Icons.Default.RadioButtonUnchecked
                    }, null, Modifier.size(20.dp), tint = foreground)
                }
                Text(chapter.ordinal.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall, color = foreground)
                Text(chapter.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                Text(timeLabel(chapter.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current) LinearProgressIndicator(progress = { chapter.progress(book.positionMs) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@UnstableApi
@Composable private fun ImportPreview(vm:LibraryViewModel,onReorder:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text(if(vm.relinkId!=null)"Ricollega la registrazione" else "Controlla l’importazione",style=MaterialTheme.typography.headlineMedium) }
        if(vm.relinkId!=null) item { Text("Scegli la stessa registrazione con lo stesso numero e ordine di file. Confermando manterrai i vecchi progressi e segnalibri; una lettura diversa potrebbe non corrispondere.",color=MaterialTheme.colorScheme.error) }
        else item { ListItem(headlineContent={Text("Copia i file nell’app")},supportingContent={Text(if(vm.mustCopyImports)"Copia necessaria: questo archivio non concede accesso permanente." else if(vm.copyImports)"Usa spazio aggiuntivo. Conserva gli originali separatamente." else "Usa gli originali: non spostarli dopo l’importazione.")},trailingContent={Switch(vm.copyImports,{vm.copyImports=it},enabled=!vm.mustCopyImports)}) }
        items(vm.candidates,key={it.id}) { b -> Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(b.title,{vm.changeCandidate(b.id,title=it)},label={Text("Titolo")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(b.author,{vm.changeCandidate(b.id,author=it)},label={Text("Autore")},modifier=Modifier.fillMaxWidth())
                Text("${b.tracks.size} file · ${timeLabel(b.durationMs)}")
                TextButton(onClick={onReorder(b.id)}){Icon(Icons.Default.Sort,null);Text("Controlla ordine dei file")}
            }
        } }
        item { Button(onClick=vm::confirmImport,modifier=Modifier.fillMaxWidth(),enabled=vm.candidates.isNotEmpty()&&vm.candidates.all{it.title.isNotBlank()}){Text(if(vm.relinkId!=null)"Conferma registrazione e ricollega" else "Importa ${vm.candidates.size} libri")} }
    }
}
@UnstableApi
@Composable private fun ReorderScreen(vm:LibraryViewModel,id:String) {
    val book=vm.candidates.find{it.id==id}?:return
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {Text("Ordine dei file",style=MaterialTheme.typography.headlineMedium);Text(book.title)}
        itemsIndexed(book.tracks,key={_,t->t.id}) { i,t -> ListItem(headlineContent={Text("${i+1}. ${t.name}")},supportingContent={Text(timeLabel(t.durationMs))},trailingContent={Row {
            IconButton(onClick={vm.moveTrack(id,i,i-1)},enabled=i>0){Icon(Icons.Default.ArrowUpward,"Sposta su ${t.name}")}
            IconButton(onClick={vm.moveTrack(id,i,i+1)},enabled=i<book.tracks.lastIndex){Icon(Icons.Default.ArrowDownward,"Sposta giù ${t.name}")}
        }}) }
    }
}

@UnstableApi
@Composable private fun SettingsScreen(vm:LibraryViewModel,onTheme:()->Unit,onSkips:()->Unit,onNightDuration:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onInstall:()->Unit) {
    val context=LocalContext.current
    val storage by produceState(0L) {value=withContext(Dispatchers.IO){File(context.filesDir,"books").walkTopDown().filter{it.isFile}.sumOf{it.length()}}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Text("Impostazioni",style=MaterialTheme.typography.headlineLarge)}
        item {SettingRow("Aspetto",when(vm.theme){"dark"->"Scuro";"light"->"Chiaro";else->"Come il sistema"},Icons.Default.Palette,onTheme)}
        item {SettingRow("Salti del lettore","Indietro ${vm.skipBack} s · avanti ${vm.skipForward} s",Icons.Default.Replay,onSkips)}
        item {Text("Ascolto",style=MaterialTheme.typography.titleLarge)}
        item {SwitchSettingRow("Ripresa intelligente","Torna indietro in base alla durata della pausa.",Icons.Default.History,vm.smartRewind,vm::changeSmartRewind)}
        item {SwitchSettingRow("Timer automatico notturno","Si attiva una sola volta per notte quando inizi ad ascoltare.",Icons.Default.Bedtime,vm.nightTimerEnabled,vm::changeNightTimerEnabled)}
        if(vm.nightTimerEnabled) {
            item {SettingRow("Inizia dopo",clockLabel(vm.nightTimerStartMinutes),Icons.Default.Schedule) {
                TimePickerDialog(context,{_,hour,minute->vm.changeNightTimerStart(hour*60+minute)},vm.nightTimerStartMinutes/60,vm.nightTimerStartMinutes%60,true).show()
            }}
            item {SettingRow("Durata notturna","${vm.nightTimerDuration} minuti",Icons.Default.Timer,onNightDuration)}
        }
        item {SwitchSettingRow("Dissolvenza finale","Riduce gradualmente il volume nell’ultimo minuto.",Icons.Default.VolumeDown,vm.timerFade,vm::changeTimerFade)}
        item {SwitchSettingRow("Scuoti per altri 10 minuti","Funziona soltanto mentre un timer è attivo.",Icons.Default.Vibration,vm.timerShakeExtend,vm::changeTimerShakeExtend)}
        item {SettingRow("Vista della libreria",if(vm.libraryViewMode=="compact")"Compatta" else "Griglia",if(vm.libraryViewMode=="compact")Icons.Default.ViewAgenda else Icons.Default.GridView) {
            vm.changeLibraryViewMode(if(vm.libraryViewMode=="grid")"compact" else "grid")
        }}
        item {Text("Accesso rapido",style=MaterialTheme.typography.titleLarge);Text("Controlla libro, capitolo e riproduzione senza aprire l’app.",style=MaterialTheme.typography.bodyMedium)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={
                val manager=AppWidgetManager.getInstance(context)
                if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context,PlaybackWidgetProvider::class.java),null,null)
                else vm.message="Aggiungi il widget Sottovoce dal menu dei widget del launcher."
            },modifier=Modifier.weight(1f)){Icon(Icons.Default.Widgets,null);Spacer(Modifier.width(5.dp));Text("Widget")}
            OutlinedButton(onClick={
                if(Build.VERSION.SDK_INT>=33) context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context,PlaybackTileService::class.java),"Sottovoce",Icon.createWithResource(context,R.drawable.ic_launcher),context.mainExecutor
                ){result->vm.message=if(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED||result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                    "Riquadro Sottovoce disponibile nei comandi rapidi." else "Il riquadro non è stato aggiunto."}
                else vm.message="Apri i comandi rapidi, scegli Modifica e trascina il riquadro Sottovoce."
            },modifier=Modifier.weight(1f)){Icon(Icons.Default.DashboardCustomize,null);Spacer(Modifier.width(5.dp));Text("Riquadro")}
        }}
        item {Text("Backup locale",style=MaterialTheme.typography.titleLarge);Text("Salva libreria, progressi, segnalibri e preferenze. Gli audio vanno conservati separatamente.",style=MaterialTheme.typography.bodyMedium)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=onBackup,modifier=Modifier.weight(1f)){Text("Esporta")};OutlinedButton(onClick=onRestore,modifier=Modifier.weight(1f)){Text("Ripristina")}}}
        item {Text("Spazio gestito: ${storage/1024/1024} MB",style=MaterialTheme.typography.titleMedium);Text("Copie audio e copertine. Per eliminare una copia apri la scheda del libro: gli originali restano intatti.",style=MaterialTheme.typography.bodySmall)}
        item {Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Aggiornamenti dell’app",style=MaterialTheme.typography.titleLarge)
            Text("Sottovoce ${BuildConfig.VERSION_NAME}")
            if(BuildConfig.DEBUG) Text("Versione di sviluppo. Installa l’APK release per gli aggiornamenti firmati.",style=MaterialTheme.typography.bodySmall)
            else {
                Text("Sottovoce controlla automaticamente all’apertura se esiste una nuova versione. Solo questo controllo contatta GitHub; i tuoi audio non vengono inviati online.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=vm::checkUpdate,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Text("Controlla aggiornamenti")}
                vm.release?.let{r->
                    Text("Disponibile ${r.versionName} · ${r.size/1024/1024} MB",fontWeight=FontWeight.Medium)
                    Text(r.notes)
                    Button(onClick=onInstall,modifier=Modifier.fillMaxWidth(),enabled=r.minSdk<=Build.VERSION.SDK_INT&&!vm.updateInProgress){Text(if(vm.updateInProgress)"Download ${(vm.updateProgress*100).toInt()}%" else "Aggiorna e installa")}
                }
                if(vm.updateChecked&&vm.release==null) Text("Sei alla versione più recente.",color=MaterialTheme.colorScheme.primary)
            }
        }}}
        item {Text("Privata, per scelta",style=MaterialTheme.typography.titleLarge);Text("Nessun account, pubblicità o tracciamento. Libreria e ascolto funzionano offline. Nessun download di audiolibri.")}
        item {TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/Adrianss31/sottovoce")))}){Icon(Icons.Default.Code,null);Text("Codice e istruzioni su GitHub")}}
    }
}
@Composable private fun SettingRow(title:String,value:String,icon:ImageVector,onClick:()->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(value)},leadingContent={Icon(icon,null)},trailingContent={Icon(Icons.Default.ChevronRight,null)},modifier=Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick))
}
@Composable private fun SwitchSettingRow(title:String,value:String,icon:ImageVector,checked:Boolean,onChecked:(Boolean)->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(value)},leadingContent={Icon(icon,null)},
        trailingContent={Switch(checked,onChecked)},modifier=Modifier.clip(RoundedCornerShape(12.dp)).clickable{onChecked(!checked)})
}
private fun clockLabel(minutes:Int)="%02d:%02d".format(minutes/60,minutes%60)
@Composable private fun EmptyMessage(text:String){Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun TimerDialog(onDismiss:()->Unit,onSelect:(Int)->Unit){
    var custom by remember{mutableStateOf("")}
    val customMinutes=custom.toIntOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Timer di spegnimento")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp)){
        items(listOf("Disattivato" to 0,"10 minuti" to 10,"15 minuti" to 15,"30 minuti" to 30,"45 minuti" to 45,"60 minuti" to 60,"90 minuti" to 90,"Fine capitolo" to -1)){(label,value)->
            ListItem(headlineContent={Text(label)},modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable{onSelect(value)})
        }
        item{HorizontalDivider(Modifier.padding(vertical=6.dp))}
        item{OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(3)},label={Text("Minuti personalizzati")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{Button(onClick={customMinutes?.let(onSelect)},enabled=customMinutes?.let{it in 1..180}==true,modifier=Modifier.fillMaxWidth()){Text("Avvia timer personalizzato")}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Chiudi")}})
}
@Composable private fun <T> ChoiceDialog(title:String,choices:List<Pair<String,T>>,selected:T?,onDismiss:()->Unit,onSelect:(T)->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={LazyColumn{items(choices){(label,value)->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable{onSelect(value)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==value,onClick={onSelect(value)});Text(label)}}}},confirmButton={TextButton(onClick=onDismiss){Text("Chiudi")}})
}
@Composable private fun EditBookDialog(book:Book,onDismiss:()->Unit,onSave:(String,String,String,String,Int?)->Unit){
    var title by remember{mutableStateOf(book.title)};var author by remember{mutableStateOf(book.author)};var narrator by remember{mutableStateOf(book.narrator)}
    var series by remember{mutableStateOf(book.series)};var position by remember{mutableStateOf(book.seriesPosition?.toString().orEmpty())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Modifica libro")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{OutlinedTextField(title,{title=it.take(1000)},label={Text("Titolo")},modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(author,{author=it.take(1000)},label={Text("Autore")},modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(narrator,{narrator=it.take(1000)},label={Text("Narratore")},modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(series,{series=it.take(1000)},label={Text("Serie")},placeholder={Text("Es. Il Signore degli Anelli")},modifier=Modifier.fillMaxWidth())}
        item{OutlinedTextField(position,{position=it.filter(Char::isDigit).take(3)},label={Text("Numero nella serie")},placeholder={Text("Es. 1")},enabled=series.isNotBlank(),modifier=Modifier.fillMaxWidth())}
    }},confirmButton={TextButton(onClick={onSave(title,author,narrator,series,position.toIntOrNull())},enabled=title.isNotBlank()&&(position.isBlank()||position.toIntOrNull() in 1..999)){Text("Salva")}},dismissButton={TextButton(onClick=onDismiss){Text("Annulla")}})
}
@Composable private fun NoteDialog(onDismiss:()->Unit,onSave:(String)->Unit){var note by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Nuovo segnalibro")},text={OutlinedTextField(note,{note=it.take(10_000)},label={Text("Nota facoltativa")},modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={onSave(note)}){Text("Salva")}},dismissButton={TextButton(onClick=onDismiss){Text("Annulla")}})
}
