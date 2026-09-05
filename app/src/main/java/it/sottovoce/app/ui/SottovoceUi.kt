@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package it.sottovoce.app.ui

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.listSaver
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import it.sottovoce.app.NowPlaying
import it.sottovoce.app.R
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
import it.sottovoce.app.playback.PlaybackTileService
import it.sottovoce.app.playback.PlaybackWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
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

private data class NavigationFrame(val screen: String, val selectedId: String?, val selectedSeries: String?)

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
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var reorderId by rememberSaveable { mutableStateOf<String?>(null) }
    val backStack = rememberSaveable(saver = listSaver(
        save = { stack -> stack.flatMap { listOf(it.screen, it.selectedId.orEmpty(), it.selectedSeries.orEmpty()) } },
        restore = { values -> mutableStateListOf<NavigationFrame>().apply {
            values.chunked(3).forEach { add(NavigationFrame(it[0], it[1].ifEmpty { null }, it[2].ifEmpty { null })) }
        } }
    )) { mutableStateListOf<NavigationFrame>() }
    val destinationStateHolder = rememberSaveableStateHolder()
    var sharedBookOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedSeriesOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedStatsOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var sharedReorderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
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
    fun navigateTo(screen: String, selectedId: String? = vm.selectedId, selectedSeries: String? = vm.selectedSeries) {
        if (vm.screen == screen && vm.selectedId == selectedId && vm.selectedSeries == selectedSeries) return
        if (vm.screen == "library") backStack.clear()
        backStack += NavigationFrame(vm.screen, vm.selectedId, vm.selectedSeries)
        if (screen != "import") sharedReorderKey = null
        vm.selectedId = selectedId
        vm.selectedSeries = selectedSeries
        vm.screen = screen
    }
    fun openBook(target: Book, origin: String) {
        sharedBookOrigin = origin
        navigateTo("detail", selectedId = target.id)
    }
    fun openSettings() {
        navigateTo("settings")
    }
    fun goBack() {
        if (reorderId != null) {
            reorderId = null
            return
        }
        if (vm.screen == "import") { vm.candidates = emptyList(); vm.relinkId = null }
        val previous = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else NavigationFrame("library", null, null)
        vm.selectedId = previous.selectedId
        vm.selectedSeries = previous.selectedSeries
        vm.screen = previous.screen
    }
    PredictiveBackHandler((vm.screen != "library" || reorderId != null) && vm.busy == null) { events ->
        try {
            events.collect { predictiveBackProgress = it.progress }
            goBack()
        } catch (_: CancellationException) {
            // An interrupted gesture returns to the current destination.
        } finally {
            predictiveBackProgress = 0f
        }
    }
    LaunchedEffect(vm.message) { vm.message?.let {
        val reset = it == "Libro segnato come non iniziato."
        val result = snackbar.showSnackbar(it, actionLabel = if (reset) "Annulla" else null, duration = SnackbarDuration.Long)
        vm.message = null
        if (reset && result == SnackbarResult.ActionPerformed) vm.undoReset()
    } }
    ProvideSottovoceMotion {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = SottovoceTypography) {
    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
        Scaffold(
            modifier = Modifier.testTag("app_scaffold"),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Sottovoce", style = MaterialTheme.typography.titleLarge)
                } }, navigationIcon = {
                    AnimatedVisibility(vm.screen != "library", enter = fadeIn(tween(160)) + slideInHorizontally { -it / SottovoceMotionTokens.BackIconOffsetFraction },
                        exit = fadeOut(tween(120)) + slideOutHorizontally { it / SottovoceMotionTokens.BackIconOffsetFraction }) {
                        IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Torna indietro") }
                    }
                }, actions = {
                    AnimatedVisibility(vm.screen != "settings", enter = fadeIn(tween(160)),
                        exit = fadeOut(tween(120))) settingsVisibility@{
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@settingsVisibility) {
                            IconButton(onClick = ::openSettings, Modifier.sottovoceSharedElement("settings:source")) {
                                Icon(Icons.Default.Settings, "Impostazioni")
                            }
                        }
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },

        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val motion = LocalMotionPolicy.current
                AnimatedVisibility(vm.release != null,
                    enter = expandVertically(tween(motion.durationMillis(240), easing = SottovoceMotionTokens.StandardEasing), expandFrom = Alignment.Top) + fadeIn(tween(motion.durationMillis(180))),
                    exit = shrinkVertically(tween(motion.durationMillis(180), easing = SottovoceMotionTokens.AccelerateEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(motion.durationMillis(140)))) {
                    vm.release?.let { release ->
                        UpdateBanner(release.versionName, vm.updateInProgress, vm.updateProgress) {
                            vm.updateAndInstall(launchUpdateIntent)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { AnimatedContent(targetState = NavigationFrame(vm.screen, vm.selectedId, vm.selectedSeries),
                    transitionSpec = {
                        // Only the originating element travels; pages never slide or zoom.
                        (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false))
                    }, label = "navigazione contestuale") { frame ->
                    val screen = frame.screen
                    val book = books.find { it.id == frame.selectedId }
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@AnimatedContent) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).graphicsLayer {
                            if (screen == vm.screen && predictiveBackProgress > 0f) {
                                translationX = size.width * .16f * predictiveBackProgress
                                scaleX = 1f - .018f * predictiveBackProgress
                                scaleY = 1f - .018f * predictiveBackProgress
                            }
                        }) { when (screen) {
                            "library" -> destinationStateHolder.SaveableStateProvider("library") {
                                LibraryScreen(books, last, vm.now.bookId, vm.now.playing, vm, vm.stats,
                                    onImport = { vm.relinkId = null; dialog = "import" },
                                    onBook = { target, origin -> openBook(target, origin) }, onPlay = { play(it) },
                                    onSeries = { key, origin -> sharedSeriesOrigin = origin; navigateTo("series", selectedSeries = key) },
                                    onStats = { origin -> sharedStatsOrigin = origin; navigateTo("stats"); vm.openStats() })
                            }
                            "series" -> frame.selectedSeries?.let { key ->
                                destinationStateHolder.SaveableStateProvider("series:$key") {
                                    val seriesBooks = books.filter { seriesKey(it.series) == seriesKey(key) }
                                    val name = seriesBooks.firstOrNull()?.series?.trim()?.replace(Regex("\\s+"), " ") ?: key
                                    SeriesScreen(name, seriesBooks, vm, vm.now.bookId, vm.now.playing, sharedSeriesOrigin,
                                        onBook = { target, origin -> openBook(target, origin) })
                                }
                            }
                            "stats" -> StatsScreen(vm.stats, sharedStatsOrigin)
                            "detail" -> if (book != null) DetailScreen(book, bookmarks.filter { it.bookId == book.id }, vm.now.bookId == book.id, vm, timer,
                                sharedCoverKey = sharedBookOrigin,
                                onPlay = { index, position -> play(book, index, position) }, onEdit = { dialog = "edit" },
                                onSpeed = { dialog = "speed" }, onTimer = { dialog = "timer" }, onBookmark = { dialog = "bookmark" },
                                onRelink = { vm.relinkId = book.id; pickFiles() }, onComplete = { vm.markCompleted(book) },
                                onRemove = { dialog = "remove" }, onRemoveCopies = { dialog = "copies" },
                                onDeleteMark = { id -> vm.task("Rimozione…") { vm.library.removeBookmark(id) } })
                            "import" -> AnimatedContent(reorderId, transitionSpec = {
                                EnterTransition.None togetherWith ExitTransition.None
                            }, label = "anteprima e riordino") reorder@{ selected ->
                                CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@reorder) {
                                    if (selected != null) ReorderScreen(vm, selected, sharedReorderKey) else ImportPreview(vm, sharedReorderKey) { reorderId = it; sharedReorderKey = it }
                                }
                            }
                            "settings" -> SettingsScreen(vm,
                                sharedKey = "settings:source",
                                onTheme = { dialog = "theme" }, onSkips = { dialog = "skips" },
                                onNightDuration = { dialog = "nightDuration" },
                                onBackup = { backupExport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                                    .putExtra(Intent.EXTRA_TITLE, "sottovoce-backup.json").putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                                onRestore = { backupImport.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                                onInstall = { vm.updateAndInstall(launchUpdateIntent) })
                        } }
                    }
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
            "timer" -> TimerDialog({ dialog = null }) { vm.timerForBook(it); dialog = null }
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
            text = { Text("Sostituirà libri e segnalibri. I backup nuovi includono le statistiche; quelli precedenti conservano gli ascolti locali dei libri corrispondenti. La copia precedente sarà recuperabile dalle impostazioni. Le copie audio disponibili saranno ricollegate, gli altri audio andranno selezionati di nuovo.") },
            confirmButton = { TextButton(onClick = vm::restoreBackup) { Text("Ripristina") } }, dismissButton = { TextButton(onClick = { vm.pendingBackup = null }) { Text("Annulla") } }) }
        vm.busy?.let { label -> AlertDialog(onDismissRequest = {}, title = { Text(label) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (label.startsWith("Scaricamento")) LinearProgressIndicator(progress = { vm.updateProgress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(vm.operationDetail ?: "Operazione in corso…", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = vm::cancelTask) { Text("Annulla") } }) }
    }
    }
    }
}

@Composable private fun UpdateBanner(version: String, downloading: Boolean, progress: Float, onUpdate: () -> Unit) {
    val shownProgress by animateFloatAsState(progress.coerceIn(0f, 1f),
        tween(SottovoceMotionTokens.DurationProgress, easing = LinearEasing), label = "download aggiornamento")
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { if (downloading) stateDescription = "Download ${(shownProgress * 100).toInt()} per cento" },
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedStateIcon(downloading, icon = { if (it) Icons.Default.Downloading else Icons.Default.SystemUpdate },
                    contentDescription = { null }, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Aggiornamento disponibile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    AnimatedContent(downloading, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "stato aggiornamento") {
                        Text(if (it) "Download ${(shownProgress * 100).toInt()}%" else "Sottovoce $version è pronto", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = onUpdate, enabled = !downloading, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    AnimatedContent(downloading, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) }, label = "azione aggiornamento") {
                        Text(if (it) "Attendi" else "Aggiorna")
                    }
                }
            }
            AnimatedVisibility(downloading, enter = expandVertically(tween(180)) + fadeIn(tween(150)), exit = shrinkVertically(tween(140)) + fadeOut(tween(100))) {
                LinearProgressIndicator(progress = { shownProgress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@UnstableApi
@Composable private fun LibraryScreen(books: List<Book>, last: Book?, activeId: String?, playing: Boolean, vm: LibraryViewModel, stats: ListeningStats?,
    onImport: () -> Unit, onBook: (Book, String) -> Unit, onPlay: (Book) -> Unit,
    onSeries: (String, String) -> Unit, onStats: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    var sort by rememberSaveable { mutableStateOf("Recenti") }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
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
    val gridState = rememberLazyGridState()
    val pinned = playing && activeId != null && last?.id == activeId
    val compact by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 48 } }
    val hero: @Composable (Boolean) -> Unit = { small ->
        last?.let { current -> ContinueListeningCard(current, vm.now.takeIf { it.bookId == current.id }, vm.skipBack, vm.skipForward,
            sharedKey = "hero:${current.id}", compact = small,
            onOpen = { onBook(current, "hero:${current.id}") },
            onBack = { if (activeId == current.id) vm.skip(-vm.skipBack) else onPlay(current) },
            onToggle = { if (activeId == current.id) vm.togglePlay() else onPlay(current) },
            onForward = { if (activeId == current.id) vm.skip(vm.skipForward) else onPlay(current) }) }
    }
    Column(Modifier.fillMaxSize()) {
    if (pinned) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("pinned_listening")) { hero(compact) }
    LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f).testTag("library"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("La tua libreria", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            val listening = books.count { it.lastPlayedAt > 0 && !it.completed }
            Text("${books.size} ${if (books.size == 1) "audiolibro" else "audiolibri"} · $listening in ascolto", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (last != null && !pinned) item(key = "last_listening", span = { GridItemSpan(maxLineSpan) }) { hero(false) }
        if (stats != null && books.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(Modifier.fillMaxWidth().sottovoceSharedBounds("stats:summary").clickable { onStats("stats:summary") }, shape = SottovoceDesign.Soft) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Oggi · ${timeLabel(stats.todayMs)}")
                    Text("Statistiche", color = MaterialTheme.colorScheme.primary)
                }
            }
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
                Column(Modifier.weight(1f)) { Text("Scaffale", style = MaterialTheme.typography.titleLarge)
                    Text("${filtered.size} ${if (filtered.size == 1) "titolo" else "titoli"}${if (seriesCount > 0) " · $seriesCount serie" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onImport) { Icon(Icons.Default.Add, null); Text("Aggiungi") }
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) search = "" }) {
                    AnimatedStateIcon(searchOpen, icon = { if (it) Icons.Default.Close else Icons.Default.Search },
                        contentDescription = { if (it) "Chiudi ricerca" else "Cerca libri" })
                }
                IconButton(onClick = { vm.changeLibraryViewMode(if (vm.libraryViewMode == "grid") "compact" else "grid") }) {
                    AnimatedStateIcon(vm.libraryViewMode, icon = { if (it == "grid") Icons.Default.ViewAgenda else Icons.Default.GridView },
                        contentDescription = { if (it == "grid") "Vista compatta" else "Vista a griglia" })
                }
                Box { IconButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, "Ordina libri") }
                    DropdownMenu(sortOpen, { sortOpen = false }) { listOf("Recenti","Titolo","Autore","Serie").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortOpen = false }) } }
                }
            } }
            item(key = "library_search", span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(searchOpen, enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))) {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("library_search"),
                        placeholder = { Text("Titolo, autore, narratore o serie") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { IconButton(onClick = { search = ""; searchOpen = false }) { Icon(Icons.Default.Close, "Chiudi ricerca") } },
                        singleLine = true, shape = SottovoceDesign.Soft)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { LibraryFilterBar(filter) { filter = it } }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage("Nessun libro corrisponde alla ricerca.") }
            else {
                val entries = if (search.isNotBlank()) filtered.map { LibraryEntry.Single(it) } else groupForLibrary(filtered, books)
                val seriesEntries = entries.filterIsInstance<LibraryEntry.SeriesGroup>()
                val singleEntries = entries.filterIsInstance<LibraryEntry.Single>()
                if (seriesEntries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionTitle("Serie", "Tocca una serie per vedere tutti i volumi") }
                    seriesEntries.forEach { entry ->
                        item(key = "series:${entry.key}", span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) {
                            Box(Modifier.animateItem()) {
                                SeriesCard(entry.name, entry.books, entry.totalCount, vm.libraryViewMode == "compact", activeId, playing,
                                    sharedKey = "series:${entry.key}") { onSeries(entry.key, "series:${entry.key}") }
                            }
                        }
                    }
                }
                if (singleEntries.isNotEmpty()) {
                    if (seriesEntries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionTitle("Altri libri", null) }
                    singleEntries.forEach { entry ->
                        val b = entry.book
                        item(key = b.id, span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) {
                            Box(Modifier.animateItem()) {
                                LibraryBookItem(b, vm.libraryViewMode, activeId == b.id, playing && activeId == b.id,
                                    sharedKey = "library:${b.id}") { onBook(b, "library:${b.id}") }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable private fun LibraryBookItem(book: Book, mode: String, active: Boolean, playing: Boolean, sharedKey: String?, onOpen: () -> Unit) {
    if (mode == "compact") AudiobookCompactItem(book, active, playing, sharedKey, onOpen)
    else AudiobookGridItem(book, active, playing, sharedKey, onOpen)
}

@Composable private fun LibraryFilterBar(selected: String, onSelect: (String) -> Unit) {
    val choices = listOf("Tutti", "In ascolto", "Da iniziare", "Completati")
    BoxWithConstraints(Modifier.fillMaxWidth().height((56 * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(3.dp)) {
        val segmentWidth = maxWidth / choices.size
        val targetOffset = segmentWidth * choices.indexOf(selected).coerceAtLeast(0)
        val indicatorOffset by animateDpAsState(targetOffset,
            spring(dampingRatio = .88f, stiffness = Spring.StiffnessMediumLow), label = "indicatore filtro")
        Box(Modifier.offset(x = indicatorOffset).width(segmentWidth).fillMaxHeight().clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            choices.forEach { label ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .selectable(selected = label == selected, role = Role.Tab, onClick = { onSelect(label) }),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable private fun ContinueListeningCard(book: Book, now: NowPlaying?, back: Int, forward: Int, sharedKey: String?, compact: Boolean = false,
    onOpen: () -> Unit, onBack: () -> Unit, onToggle: () -> Unit, onForward: () -> Unit) {
    val active = now != null
    val playing = now?.playing == true
    val displayBook = if (now != null) book.copy(trackIndex = now.trackIndex, positionMs = now.position, speed = now.speed) else book
    val chapter = displayBook.currentChapter()
    val chapterProgress = if (displayBook.completed) 1f else chapter?.progress(displayBook.positionMs) ?: displayBook.progress
    val progress by animateFloatAsState(chapterProgress, tween(500), label = "progresso capitolo")
    val coverWidth by animateDpAsState(if (compact) 42.dp else 106.dp, tween(LocalMotionPolicy.current.durationMillis(260)), label = "copertina pannello")
    Surface(Modifier.fillMaxWidth().testTag(if (compact) "listening_compact" else "listening_expanded").animateContentSize()
        .motionClickable(pressedScale = .99f, onClickLabel = "Apri ${book.title}", onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer, shape = SottovoceDesign.Card, shadowElevation = 2.dp) {
        if (compact) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cover(book, Modifier.width(32.dp).sottovoceSharedElement(sharedKey))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = onBack) { Icon(Icons.Default.Replay, "Indietro $back secondi") }
            FilledIconButton(onClick = onToggle) { AnimatedPlayPauseIcon(playing, Modifier.size(24.dp), playContentDescription = "Riprendi", pauseContentDescription = "Pausa") }
            IconButton(onClick = onForward) { Icon(Icons.Default.Forward30, "Avanti $forward secondi") }
        } else Column(Modifier.padding(if (compact) 10.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                Cover(book, Modifier.width(coverWidth).sottovoceSharedElement(sharedKey))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedContent(targetState = active && playing, label = "stato riproduzione") { isPlaying ->
                            Icon(if (isPlaying) Icons.Default.GraphicEq else Icons.Default.History, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(if (active && playing) "IN RIPRODUZIONE" else if (active) "IN PAUSA" else "ULTIMO ASCOLTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(book.title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
                    if (!compact && book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    if (!compact) Text(chapter?.title ?: "Audiolibro", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(chapter?.let { "${timeLabel(it.elapsedMs(displayBook.positionMs))} · ${timeLabel(listeningTime(it.remainingMs(displayBook.positionMs), displayBook.speed))} rimasti" } ?: timeLabel(book.durationMs),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .65f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipButton(back, true, onBack)
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(if (compact) 48.dp else 64.dp)) {
                    AnimatedPlayPauseIcon(playing, Modifier.size(32.dp), playContentDescription = "Riprendi", pauseContentDescription = "Pausa")
                }
                SkipButton(forward, false, onForward)
            }
        }
    }
}

@Composable private fun SkipButton(seconds: Int, back: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val direction by animateFloatAsState(if (pressed) (if (back) -4f else 4f) else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "direzione salto")
    FilledTonalButton(onClick = onClick, interactionSource = interaction, shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)) {
        Icon(if (back) Icons.Default.Replay else Icons.Default.Forward30,
            if (back) "Indietro $seconds secondi" else "Avanti $seconds secondi",
            Modifier.size(19.dp).graphicsLayer { translationX = direction })
        Spacer(Modifier.width(4.dp)); Text("$seconds s", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun AudiobookGridItem(book: Book, active: Boolean, playing: Boolean, sharedKey: String?, onOpen: () -> Unit) {
    val chapter = book.currentChapter()
    val status = when {
        book.needsRelink -> "File da ricollegare"
        book.completed -> "Completato · ${timeLabel(book.durationMs)}"
        book.lastPlayedAt == 0L -> "Da iniziare · ${timeLabel(book.durationMs)}"
        else -> chapter?.let { "Cap. ${it.ordinal}/${it.total} · −${timeLabel(listeningTime(it.remainingMs(book.positionMs), book.speed))}" } ?: "In ascolto"
    }
    Column(Modifier.fillMaxWidth().testTag("book_${book.id}")
        .motionClickable(onClickLabel = "Apri ${book.title}", onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box {
            Cover(book, Modifier.fillMaxWidth().sottovoceSharedElement(sharedKey))
            if (active || book.completed) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.surface) {
                Icon(if (active && playing) Icons.Default.GraphicEq else if (active) Icons.Default.Pause else Icons.Default.Check,
                    if (active && playing) "In riproduzione" else if (active) "In pausa" else "Completato",
                    Modifier.padding(6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, style = MaterialTheme.typography.labelSmall,
            color = if (book.needsRelink) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.lastPlayedAt > 0 || book.completed) LinearProgressIndicator(progress = { if (book.completed) 1f else book.progress },
            modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}

private fun seriesLabel(book: Book): String = book.series + (book.seriesPosition?.let { " · Libro $it" } ?: "")

@Composable private fun AudiobookCompactItem(book: Book, active: Boolean, playing: Boolean, sharedKey: String?, onOpen: () -> Unit) {
    val chapter = book.currentChapter()
    Surface(Modifier.fillMaxWidth().testTag("book_${book.id}")
        .motionClickable(onClickLabel = "Apri ${book.title}", onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cover(book, Modifier.width(52.dp).sottovoceSharedElement(sharedKey))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildString {
                    append(book.author.ifBlank { "Autore non indicato" })
                    if (book.series.isNotBlank()) append(" · ").append(seriesLabel(book))
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    book.needsRelink -> "File da ricollegare"
                    book.completed -> "Completato"
                    book.lastPlayedAt == 0L -> "Da iniziare · ${timeLabel(book.durationMs)}"
                    else -> chapter?.let { "Cap. ${it.ordinal}/${it.total} · ${it.title} · −${timeLabel(listeningTime(it.remainingMs(book.positionMs), book.speed))}" } ?: "In ascolto"
                }, style = MaterialTheme.typography.labelSmall, color = if (book.needsRelink) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.lastPlayedAt > 0 || book.completed) LinearProgressIndicator(progress = { if (book.completed) 1f else book.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape))
            }
            AnimatedStateIcon(active && playing,
                icon = { if (it) Icons.Default.GraphicEq else Icons.Default.ChevronRight },
                contentDescription = { if (it) "In riproduzione" else "Apri" },
                modifier = Modifier.size(20.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun LibrarySectionTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun ListeningSummaryCard(stats: ListeningStats, sharedKey: String?, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().sottovoceSharedBounds(sharedKey)
        .motionClickable(onClickLabel = "Apri le statistiche di ascolto", onClick = onOpen),
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

@Composable private fun SeriesCard(name: String, entries: List<Book>, totalCount: Int, compact: Boolean, activeId: String?, playing: Boolean,
    sharedKey: String?, onOpen: () -> Unit) {
    if (entries.isEmpty()) return
    val totalDuration = entries.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = entries.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (totalDuration > 0) (played.toFloat() / totalDuration).coerceIn(0f, 1f) else if (entries.all { it.completed }) 1f else 0f
    val current = entries.firstOrNull { it.id == activeId } ?: entries.firstOrNull { !it.completed && it.lastPlayedAt > 0 } ?: entries.firstOrNull { !it.completed }
    Column(Modifier.fillMaxWidth().testTag("series_card_$name").sottovoceSharedBounds(sharedKey)
        .motionClickable(onClickLabel = "Apri la serie $name", onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeriesMosaic(entries, Modifier.width(70.dp))
            SeriesCardInfo(name, entries, totalCount, progress, current, playing && current?.id == activeId, Modifier.weight(1f))
        } else {
            SeriesMosaic(entries, Modifier.fillMaxWidth())
            SeriesCardInfo(name, entries, totalCount, progress, current, playing && current?.id == activeId, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun SeriesCardInfo(name: String, entries: List<Book>, totalCount: Int, progress: Float, current: Book?, isPlaying: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val countLabel = if (entries.size == totalCount) "$totalCount ${if (totalCount == 1) "libro" else "libri"}" else "${entries.size} di $totalCount volumi"
        Text("$countLabel · ${"%.0f".format(progress * 100)}%",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        current?.let { Text(if (isPlaying) "In ascolto · ${it.title}" else "Prossimo · ${it.title}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
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
@Composable private fun SeriesScreen(name: String, entries: List<Book>, vm: LibraryViewModel, activeId: String?, playing: Boolean,
    sharedKey: String?, onBook: (Book, String) -> Unit) {
    val sorted = entries.sortedWith(compareBy<Book> { it.seriesPosition ?: Int.MAX_VALUE }
        .thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
    val totalDuration = entries.sumOf { it.durationMs.coerceAtLeast(0) }
    val played = entries.sumOf { if (it.completed) it.durationMs.coerceAtLeast(0) else it.playedMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) }
    val progress = if (totalDuration > 0) (played.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f
    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize().sottovoceSharedBounds(sharedKey).testTag("series_view"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Surface(Modifier.fillMaxWidth(),
            shape = SottovoceDesign.Soft, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary)
                    Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${entries.size} ${if (entries.size == 1) "libro" else "libri"} · ${entries.count { it.completed }} completati · ${"%.0f".format(progress * 100)}% completato", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            }
        } }
        if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage("Nessun libro in questa serie.") }
        else gridItems(sorted, key = { it.id }, span = { GridItemSpan(if (vm.libraryViewMode == "compact") maxLineSpan else 1) }) { b ->
            val origin = "series:${seriesKey(name)}:book:${b.id}"
            Box(Modifier.animateItem()) {
                LibraryBookItem(b, vm.libraryViewMode, activeId == b.id, playing && activeId == b.id, origin) { onBook(b, origin) }
            }
        }
    }
}
@Composable private fun Cover(book: Book, modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFFE3B786), Color(0xFFB4C8CE), Color(0xFFD6B0A3))
    BoxWithConstraints(modifier.aspectRatio(2f/3f).clip(SottovoceDesign.Cover).background(colors[(book.title.hashCode() and Int.MAX_VALUE)%colors.size])) {
        val compact = maxWidth < 72.dp
        CoverImage(book.coverPath, "Copertina di ${book.title}", Modifier.fillMaxSize()) {
            if (compact) Text(book.title.trim().take(1).uppercase(), Modifier.align(Alignment.Center),
                fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Color(0xFF302C22))
            else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Color(0xFF302C22), maxLines = 5, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.Headphones, null, tint = Color(0xFF514632), modifier = Modifier.align(Alignment.End))
            }
        }
    }
}
@Composable private fun StatsScreen(stats: ListeningStats?, sharedKey: String?) {
    LazyColumn(Modifier.fillMaxSize().sottovoceSharedBounds(sharedKey),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                val targetHeight = (m.durationMs.toFloat() / max * 80).dp.coerceAtLeast(if (m.durationMs > 0) 6.dp else 2.dp)
                val height by animateDpAsState(targetHeight, tween(360, easing = SottovoceMotionTokens.StandardEasing), label = "mese ${m.key}")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth()
                        .height(height)
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
                val targetHeight = (day.durationMs.toFloat() / max * 80).dp.coerceAtLeast(if (day.durationMs > 0) 6.dp else 2.dp)
                val height by animateDpAsState(targetHeight, tween(340, easing = SottovoceMotionTokens.StandardEasing), label = "giorno ${day.day}")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(height)
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
    val target = if (total > 0) value.toFloat() / total else 0f
    val progress by animateFloatAsState(target, tween(300, easing = SottovoceMotionTokens.StandardEasing), label = label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$value di $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress },
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
    sharedCoverKey: String?,
    onPlay: (Int?,Long?) -> Unit, onEdit: () -> Unit, onSpeed: () -> Unit, onTimer: () -> Unit, onBookmark: () -> Unit,
    onRelink: () -> Unit, onComplete: () -> Unit, onRemove: () -> Unit, onRemoveCopies: () -> Unit, onDeleteMark: (String) -> Unit) {
    val detailState = rememberLazyListState()
    val compactHeader by remember { derivedStateOf { detailState.firstVisibleItemIndex > 0 } }
    val headerCoverWidth by animateDpAsState(if (compactHeader) 40.dp else 100.dp,
        tween(LocalMotionPolicy.current.durationMillis(220)), label = "copertina intestazione")
    val now = vm.now
    val playing = active && now.playing
    val shownTrack = if (active) now.trackIndex else book.trackIndex
    val shownPosition = if (active) now.position else book.positionMs
    val shownSpeed = if (active) now.speed else book.speed
    val displayBook = if (active) book.copy(trackIndex = shownTrack, positionMs = shownPosition, speed = shownSpeed) else book
    val timeline = remember(book.tracks) { book.chapterTimeline() }
    val current = timeline.lastOrNull { it.trackIndex == shownTrack && it.startMs <= shownPosition }
    val completed = timeline.count { displayBook.chapterStatus(it) == ChapterStatus.COMPLETED }
    val trackDuration = if (active) now.duration.takeIf { it > 0 } ?: book.tracks.getOrNull(shownTrack)?.durationMs.orZero()
        else book.tracks.getOrNull(shownTrack)?.durationMs.orZero()
    val chapterStart = current?.startMs ?: 0
    val chapterEnd = current?.endMs?.takeIf { it > chapterStart } ?: trackDuration
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0)
    var dragging by remember(book.id, current?.ordinal) { mutableStateOf<Float?>(null) }
    val chapterElapsed = dragging?.toLong() ?: (shownPosition - chapterStart).coerceIn(0, chapterDuration)
    val totalPlayed = displayBook.playedMs
    var managementOpen by rememberSaveable(book.id) { mutableStateOf(false) }
    var chapterQuery by rememberSaveable(book.id) { mutableStateOf("") }
    var section by rememberSaveable(book.id) { mutableStateOf("chapters") }
    val sliderScale by animateFloatAsState(if (dragging != null) 1.035f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "presa cursore")
    LazyColumn(Modifier.fillMaxSize().testTag("book_detail"), state = detailState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stickyHeader { Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.padding(if (compactHeader) 8.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.width(headerCoverWidth).sottovoceSharedElement(sharedCoverKey))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (!compactHeader) Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
                        AnimatedContent(active, transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(tween(180), .92f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(120), .96f))
                        }, label = "presenza stato ascolto") { isActive ->
                            if (isActive) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                AnimatedStateIcon(playing,
                                    icon = { if (it) Icons.Default.GraphicEq else Icons.Default.PauseCircle },
                                    contentDescription = { null }, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                AnimatedContent(playing, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                                    label = "stato ascolto") { isPlaying ->
                                    Text(if (isPlaying) "IN RIPRODUZIONE" else "IN PAUSA",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            } else Spacer(Modifier.fillMaxSize())
                        }
                    }
                    Text(book.title, style = if (compactHeader) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, maxLines = if (compactHeader) 2 else 4, overflow = TextOverflow.Ellipsis)
                    if (!compactHeader && book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.titleSmall)
                    if (!compactHeader && book.series.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.CollectionsBookmark, "Serie", Modifier.size(16.dp)); Text(seriesLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!compactHeader && book.narrator.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.Mic, "Narratore", Modifier.size(16.dp)); Text("Letto da ${book.narrator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, "Durata", Modifier.size(15.dp)); Text(timeLabel(book.durationMs), style = MaterialTheme.typography.bodySmall)
                        Text("•"); Icon(Icons.Default.MenuBook, "Capitoli", Modifier.size(15.dp)); Text("${timeline.size} capitoli", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Box {
                    IconButton(onClick = { managementOpen = true }) { Icon(Icons.Default.MoreVert, "Gestione del libro") }
                    DropdownMenu(managementOpen, { managementOpen = false }) {
                        DropdownMenuItem(text = { Text("Modifica dettagli") }, onClick = { managementOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Riascolta dall’inizio") }, onClick = { managementOpen = false; vm.markNotStarted(book) })
                        DropdownMenuItem(text = { Text("Ricollega audio") }, onClick = { managementOpen = false; onRelink() })
                        if (book.tracks.any { it.owned }) DropdownMenuItem(text = { Text("Elimina copie audio") }, onClick = { managementOpen = false; onRemoveCopies() })
                        DropdownMenuItem(text = { Text("Rimuovi libro") }, onClick = { managementOpen = false; onRemove() })
                    }
                }
            }
        } }
        item { Surface(Modifier.bookDetailsReveal(), color = MaterialTheme.colorScheme.primaryContainer, shape = SottovoceDesign.Card, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AnimatedContent(current?.ordinal, transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically { it / 5 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically { -it / 5 })
                        }, label = "capitolo corrente") { ordinal ->
                            val shownChapter = timeline.firstOrNull { it.ordinal == ordinal } ?: current
                            Column {
                                Text(shownChapter?.title ?: "Capitolo", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                shownChapter?.let { chapter -> Text("Capitolo ${chapter.ordinal} di ${chapter.total}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                    AnimatedVisibility(active, enter = fadeIn(tween(160)) + scaleIn(tween(160), .85f), exit = fadeOut(tween(110))) {
                        AnimatedStateIcon(playing, icon = { if (it) Icons.Default.GraphicEq else Icons.Default.Headphones },
                            contentDescription = { null }, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Slider(value = chapterElapsed.toFloat().coerceIn(0f, chapterDuration.coerceAtLeast(1).toFloat()),
                    onValueChange = { dragging = it }, valueRange = 0f..chapterDuration.coerceAtLeast(1).toFloat(),
                    enabled = chapterDuration > 0 && !book.needsRelink,
                    onValueChangeFinished = {
                        dragging?.let { value -> if (active) vm.seek(chapterStart + value.toLong()) else onPlay(shownTrack, chapterStart + value.toLong()) }
                        dragging = null
                    }, modifier = Modifier.testTag("seek_slider").graphicsLayer { scaleY = sliderScale }
                        .semantics { stateDescription = "${timeLabel(chapterElapsed)} di ${timeLabel(chapterDuration)}" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeLabel(chapterElapsed), style = MaterialTheme.typography.bodySmall)
                    Text("−${timeLabel(((chapterDuration - chapterElapsed).coerceAtLeast(0) / shownSpeed).toLong())}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Libro: ${timeLabel(totalPlayed)} / ${timeLabel(book.durationMs)} · ${timeLabel(((book.durationMs-totalPlayed).coerceAtLeast(0)/shownSpeed).toLong())} rimasti",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = active, transitionSpec = {
                        fadeIn(tween(210, easing = SottovoceMotionTokens.StandardEasing)) togetherWith
                            fadeOut(tween(130, easing = SottovoceMotionTokens.AccelerateEasing))
                    }, contentAlignment = Alignment.Center, label = "comandi integrati") { isActive ->
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
                                        AnimatedPlayPauseIcon(playing, Modifier.size(36.dp))
                                    }
                                }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SkipButton(vm.skipForward, false) { vm.skip(vm.skipForward) } }
                            }
                            AnimatedContent(playing, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "testo riproduzione") {
                                Text(if (it) "In riproduzione" else "In pausa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerTool(Icons.Default.Speed, "${shownSpeed}×", true, onSpeed, Modifier.weight(1f))
                    PlayerTool(Icons.Default.Timer, if (timer.isEmpty()) "Timer" else timer, true, onTimer, Modifier.weight(1f))
                    PlayerTool(Icons.Default.BookmarkAdd, "Segnalibro", active, onBookmark, Modifier.weight(1f))
                }
                AnimatedVisibility(active && timer.isNotEmpty(),
                    enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(170), shrinkTowards = Alignment.Top) + fadeOut(tween(120))) {
                    Text("Spegnimento: $timer", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick=onEdit, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Edit,null); Spacer(Modifier.width(6.dp)); Text("Dettagli") }
            FilledTonalButton(onClick=onComplete, modifier=Modifier.weight(1f)) {
                AnimatedStateIcon(book.completed, icon = { if(it) Icons.Default.RestartAlt else Icons.Default.CheckCircle },
                    contentDescription = { null }, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                AnimatedContent(book.completed, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "stato completamento") {
                    Text(if(it) "Segna da completare" else "Segna completato")
                }
            }
            IconButton(onClick=onRelink) { Icon(Icons.Default.FolderOpen,"Ricollega file") }
        } }
        if (book.lastPlayedAt > 0 || book.completed || active) item {
            TextButton(onClick = { vm.markNotStarted(book) }, Modifier.fillMaxWidth().testTag("reset_book")) {
                Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text("Segna come non iniziato")
            }
        }
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
        if (section == "chapters") item {
            Column {
                OutlinedTextField(chapterQuery, { chapterQuery = it }, label = { Text("Cerca capitolo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = { chapterQuery = current?.ordinal?.toString().orEmpty() }) { Text("Mostra il capitolo corrente") }
            }
        }
        if (section == "chapters") items(timeline.filter { chapterQuery.isBlank() || it.title.contains(chapterQuery, true) || it.ordinal.toString() == chapterQuery }.chunked(2), key = { "chapters:${it.first().ordinal}" }) { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { chapter -> Box(Modifier.weight(1f).fillMaxHeight().testTag("chapter_${chapter.ordinal}")) {
                    CompactChapterRow(displayBook, chapter, !book.needsRelink) { onPlay(chapter.trackIndex, chapter.startMs) }
                } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        } else if(bookmarks.isEmpty()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.BookmarkBorder,null,Modifier.size(30.dp)); Text("Nessun segnalibro")
                Text("Aggiungili dai comandi di ascolto qui sopra.", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
            }
        } } else items(bookmarks,key={it.id}) { mark -> Box(Modifier.animateItem(fadeInSpec = tween(180), fadeOutSpec = tween(120))) {
            BookmarkRow(book, mark, !book.needsRelink,
                onOpen = { onPlay(mark.trackIndex,mark.positionMs) }, onDelete = { onDeleteMark(mark.id) })
        } }
        item { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)); Text("Gestione del libro", style = MaterialTheme.typography.titleMedium) }
        if(book.tracks.any { it.owned }) item { OutlinedButton(onClick=onRemoveCopies,Modifier.fillMaxWidth()){Icon(Icons.Default.CleaningServices,null);Spacer(Modifier.width(8.dp));Text("Elimina copie audio nell’app")} }
        item { TextButton(onClick=onRemove,Modifier.fillMaxWidth()){Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error);Spacer(Modifier.width(8.dp));Text("Rimuovi dalla libreria",color=MaterialTheme.colorScheme.error)} }
    }
}

@Composable private fun PlayerTool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, label, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
        AnimatedContent(label, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "valore comando") {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun ChapterBookmarkSelector(selected: String, chapters: Int, bookmarks: Int, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height((56 * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)).padding(4.dp)
        .semantics { stateDescription = if (selected == "chapters") "Capitoli selezionati" else "Segnalibri selezionati" }) {
        val segmentWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(if (selected == "chapters") 0.dp else segmentWidth,
            spring(dampingRatio = .88f, stiffness = Spring.StiffnessMediumLow), label = "selettore libro")
        Box(Modifier.offset(x = indicatorOffset).width(segmentWidth).fillMaxHeight().clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer))
        Row(Modifier.fillMaxSize()) {
            listOf("chapters" to "Capitoli  $chapters", "bookmarks" to "Segnalibri  $bookmarks").forEach { (key, label) ->
                Row(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .motionClickable(onClickLabel = "Apri $label", onClick = { onSelect(key) }),
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
    Card(Modifier.fillMaxWidth().motionClickable(enabled = enabled, onClickLabel = "Riproduci dal segnalibro", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
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
    var fullTitle by rememberSaveable(book.id, chapter.ordinal) { mutableStateOf(false) }
    if (fullTitle) AlertDialog(onDismissRequest = { fullTitle = false }, title = { Text("Capitolo ${chapter.ordinal}") },
        text = { Text(chapter.title) }, confirmButton = { TextButton(onClick = { fullTitle = false; onPlay() }, enabled = enabled) { Text("Riproduci") } },
        dismissButton = { TextButton(onClick = { fullTitle = false }) { Text("Chiudi") } })
    val status = book.chapterStatus(chapter)
    val current = status == ChapterStatus.CURRENT
    val foreground = when (status) {
        ChapterStatus.COMPLETED, ChapterStatus.CURRENT -> MaterialTheme.colorScheme.primary
        ChapterStatus.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerTarget = when (status) {
        ChapterStatus.CURRENT -> MaterialTheme.colorScheme.primaryContainer
        ChapterStatus.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = .055f)
        ChapterStatus.UPCOMING -> Color.Transparent
    }
    val container by animateColorAsState(containerTarget, tween(240), label = "stato capitolo")
    val targetProgress = if (current) chapter.progress(book.positionMs) else if (status == ChapterStatus.COMPLETED) 1f else 0f
    val progress by animateFloatAsState(targetProgress, tween(SottovoceMotionTokens.DurationProgress, easing = LinearEasing), label = "progresso riga capitolo")
    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
            .combinedClickable(enabled = enabled, onClickLabel = "Riproduci ${chapter.title}", onLongClickLabel = "Mostra titolo completo", onLongClick = { fullTitle = true }, onClick = onPlay),
        color = container, shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(when (status) { ChapterStatus.COMPLETED -> Icons.Default.Check; ChapterStatus.CURRENT -> Icons.Default.GraphicEq; ChapterStatus.UPCOMING -> Icons.Default.PlayArrow },
                        when (status) { ChapterStatus.COMPLETED -> "Capitolo completato"; ChapterStatus.CURRENT -> "Capitolo corrente"; ChapterStatus.UPCOMING -> "Capitolo da ascoltare" },
                        Modifier.size(16.dp), tint = foreground)
                    Text(chapter.ordinal.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall, color = foreground)
                    Spacer(Modifier.weight(1f))
                    Text(timeLabel(chapter.durationMs), style = MaterialTheme.typography.labelSmall, color = foreground)
                }
                Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                if (current) Text("${timeLabel(chapter.elapsedMs(book.positionMs))} · −${timeLabel(listeningTime(chapter.remainingMs(book.positionMs), book.speed))}",
                    style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@UnstableApi
@Composable private fun ImportPreview(vm:LibraryViewModel, sharedKey: String?, onReorder:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize().sottovoceSharedBounds(sharedKey),
        contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Column { Text(if(vm.relinkId!=null)"Ricollega la registrazione" else "Controlla l’importazione",style=MaterialTheme.typography.headlineMedium) } }
        if(vm.relinkId!=null) item { Text("Scegli la stessa registrazione con lo stesso numero e ordine di file. Confermando manterrai i vecchi progressi e segnalibri; una lettura diversa potrebbe non corrispondere.",color=MaterialTheme.colorScheme.error) }
        else item { ListItem(headlineContent={Text("Copia i file nell’app")},supportingContent={Text(if(vm.mustCopyImports)"Copia necessaria: questo archivio non concede accesso permanente." else if(vm.copyImports)"Usa spazio aggiuntivo. Conserva gli originali separatamente." else "Usa gli originali: non spostarli dopo l’importazione.")},trailingContent={Switch(vm.copyImports,{vm.copyImports=it},enabled=!vm.mustCopyImports)}) }
        if (vm.relinkId == null && vm.candidates.any { it.tracks.size > 1 }) item {
            TextButton(onClick = vm::splitCandidates) { Text("Ogni file è un libro separato") }
        }
        items(vm.candidates,key={it.id}) { b -> Card(Modifier.animateItem(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
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
@Composable private fun ReorderScreen(vm:LibraryViewModel,id:String, sharedKey: String?) {
    val book=vm.candidates.find{it.id==id}?:return
    LazyColumn(Modifier.fillMaxSize().sottovoceSharedBounds(sharedKey),
        contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Column { Text("Ordine dei file",style=MaterialTheme.typography.headlineMedium);Text(book.title) } }
        itemsIndexed(book.tracks,key={_,t->t.id}) { i,t -> ListItem(headlineContent={Text("${i+1}. ${t.name}")},supportingContent={Text(timeLabel(t.durationMs))},trailingContent={Row {
            IconButton(onClick={vm.moveTrack(id,i,0)},enabled=i>0){Icon(Icons.Default.VerticalAlignTop,"Sposta all’inizio ${t.name}")}
            IconButton(onClick={vm.moveTrack(id,i,i-1)},enabled=i>0){Icon(Icons.Default.ArrowUpward,"Sposta su ${t.name}")}
            IconButton(onClick={vm.moveTrack(id,i,i+1)},enabled=i<book.tracks.lastIndex){Icon(Icons.Default.ArrowDownward,"Sposta giù ${t.name}")}
        }}, modifier = Modifier.animateItem()) }
    }
}

@UnstableApi
@Composable private fun SettingsScreen(vm:LibraryViewModel, sharedKey: String?, onTheme:()->Unit,onSkips:()->Unit,onNightDuration:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onInstall:()->Unit) {
    val context=LocalContext.current
    var cleanUnused by rememberSaveable { mutableStateOf(false) }
    if (cleanUnused) AlertDialog(onDismissRequest = { cleanUnused = false }, title = { Text("Eliminare le copie inutilizzate?") },
        text = { Text("Elimina soltanto i file privati non associati alla libreria attuale o alla copia di recupero. Gli originali restano intatti.") },
        confirmButton = { TextButton(onClick = { cleanUnused = false; vm.removeUnusedCopies() }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { cleanUnused = false }) { Text("Annulla") } })
    val storage by produceState(0L) {value=withContext(Dispatchers.IO){File(context.filesDir,"books").walkTopDown().filter{it.isFile}.sumOf{it.length()}}}
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        stickyHeader { Surface(Modifier.fillMaxWidth(), shape = SottovoceDesign.Soft,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Settings, null, Modifier.size(24.dp).sottovoceSharedElement(sharedKey), tint = MaterialTheme.colorScheme.primary)
                Text("Impostazioni",style=MaterialTheme.typography.headlineLarge)
            }
        } }
        item {SettingRow("Aspetto",when(vm.theme){"dark"->"Scuro";"light"->"Chiaro";else->"Come il sistema"},Icons.Default.Palette,onTheme)}
        item {SettingRow("Salti del lettore","Indietro ${vm.skipBack} s · avanti ${vm.skipForward} s",Icons.Default.Replay,onSkips)}
        item {Text("Ascolto",style=MaterialTheme.typography.titleLarge)}
        item {SwitchSettingRow("Ripresa intelligente","Torna indietro in base alla durata della pausa.",Icons.Default.History,vm.smartRewind,vm::changeSmartRewind)}
        item {SwitchSettingRow("Timer automatico notturno","Si attiva una sola volta per notte quando inizi ad ascoltare.",Icons.Default.Bedtime,vm.nightTimerEnabled,vm::changeNightTimerEnabled)}
        item(key = "night_timer_options") { AnimatedVisibility(vm.nightTimerEnabled,
            enter = expandVertically(tween(240), expandFrom = Alignment.Top) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow("Inizia dopo",clockLabel(vm.nightTimerStartMinutes),Icons.Default.Schedule) {
                    TimePickerDialog(context,{_,hour,minute->vm.changeNightTimerStart(hour*60+minute)},vm.nightTimerStartMinutes/60,vm.nightTimerStartMinutes%60,true).show()
                }
                SettingRow("Durata notturna","${vm.nightTimerDuration} minuti",Icons.Default.Timer,onNightDuration)
            }
        } }
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
        if (vm.library.hasRecovery()) item { OutlinedButton(onClick = vm::recoverBackup, modifier = Modifier.fillMaxWidth()) { Text("Recupera la libreria precedente") } }
        item { TextButton(onClick = { cleanUnused = true }) { Text("Elimina copie non più associate") } }
        item { TextButton(onClick = vm::cleanIncompleteCopies) { Text("Elimina copie incomplete") } }
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
    ListItem(headlineContent={Text(title)},supportingContent={AnimatedContent(value,
        transitionSpec={fadeIn(tween(160)) togetherWith fadeOut(tween(100))},label="$title valore"){Text(it)}},
        leadingContent={Icon(icon,null)},trailingContent={Icon(Icons.Default.ChevronRight,null)},
        modifier=Modifier.clip(RoundedCornerShape(12.dp)).motionClickable(onClickLabel=title,onClick=onClick))
}
@Composable private fun SwitchSettingRow(title:String,value:String,icon:ImageVector,checked:Boolean,onChecked:(Boolean)->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(value)},leadingContent={Icon(icon,null)},
        trailingContent={Switch(checked,onChecked)},modifier=Modifier.clip(RoundedCornerShape(12.dp))
            .motionClickable(onClickLabel=title,onClick={onChecked(!checked)}))
}
private fun clockLabel(minutes:Int)="%02d:%02d".format(minutes/60,minutes%60)
@Composable private fun EmptyMessage(text:String){Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun TimerDialog(onDismiss:()->Unit,onSelect:(Int)->Unit){
    var custom by remember{mutableStateOf("")}
    val customMinutes=custom.toIntOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Timer di spegnimento")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp)){
        items(listOf("Disattivato" to 0,"10 minuti" to 10,"15 minuti" to 15,"30 minuti" to 30,"45 minuti" to 45,"60 minuti" to 60,"90 minuti" to 90,"Fine capitolo" to -1)){(label,value)->
            ListItem(headlineContent={Text(label)},modifier=Modifier.clip(RoundedCornerShape(10.dp))
                .motionClickable(onClickLabel=label,onClick={onSelect(value)}))
        }
        item{HorizontalDivider(Modifier.padding(vertical=6.dp))}
        item{OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(3)},label={Text("Minuti personalizzati")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{Button(onClick={customMinutes?.let(onSelect)},enabled=customMinutes?.let{it in 1..180}==true,modifier=Modifier.fillMaxWidth()){Text("Avvia timer personalizzato")}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Chiudi")}})
}
@Composable private fun <T> ChoiceDialog(title:String,choices:List<Pair<String,T>>,selected:T?,onDismiss:()->Unit,onSelect:(T)->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={LazyColumn{items(choices){(label,value)->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .motionClickable(onClickLabel=label,onClick={onSelect(value)}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==value,onClick={onSelect(value)});Text(label)}}}},confirmButton={TextButton(onClick=onDismiss){Text("Chiudi")}})
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
