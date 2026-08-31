@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package it.sottovoce.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val LightColors = lightColorScheme(primary = Color(0xFF355A41), onPrimary = Color(0xFFFFFAF0),
    background = Color(0xFFFAF7F0), surface = Color(0xFFFAF7F0), onSurface = Color(0xFF242B24),
    primaryContainer = Color(0xFFE2EADC), onPrimaryContainer = Color(0xFF243F2B),
    surfaceVariant = Color(0xFFF0EBE1), onSurfaceVariant = Color(0xFF62665B))
private val DarkColors = darkColorScheme(primary = Color(0xFFB1D2A5), onPrimary = Color(0xFF1B321C),
    background = Color(0xFF1D211E), surface = Color(0xFF1D211E), onSurface = Color(0xFFF3EFE5),
    primaryContainer = Color(0xFF354531), onPrimaryContainer = Color(0xFFE2EADC),
    surfaceVariant = Color(0xFF292E29), onSurfaceVariant = Color(0xFFB6BCAE))

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
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Sottovoce", style = MaterialTheme.typography.titleMedium)
                } }, navigationIcon = { if (vm.screen != "library") IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Torna alla libreria") } },
                    actions = { if (vm.screen != "settings") IconButton(onClick = { vm.screen = "settings" }) { Icon(Icons.Default.Settings, "Impostazioni") } })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (last != null && vm.screen != "player") MiniPlayer(last, vm.now.playing && active != null,
                    onOpen = { if (active != null) vm.screen = "player" else play(last) },
                    onPlay = { if (active != null) vm.togglePlay() else play(last) })
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (vm.screen) {
                    "library" -> LibraryScreen(books, last, onImport = { vm.relinkId = null; dialog = "import" },
                        onBook = { vm.selectedId = it.id; vm.screen = "detail" }, onPlay = { play(it) })
                    "detail" -> if (book != null) DetailScreen(book, bookmarks.filter { it.bookId == book.id },
                        onPlay = { index, position -> play(book, index, position) }, onEdit = { dialog = "edit" },
                        onRelink = { vm.relinkId = book.id; pickFiles() }, onComplete = { vm.markCompleted(book) },
                        onRemove = { dialog = "remove" }, onRemoveCopies = { dialog = "copies" },
                        onDeleteMark = { id -> vm.task("Rimozione…") { vm.library.removeBookmark(id) } })
                    "player" -> if (active != null) PlayerScreen(active, vm, timer,
                        onSpeed = { dialog = "speed" }, onTimer = { dialog = "timer" }, onBookmark = { dialog = "bookmark" },
                        onChapters = { vm.selectedId = active.id; vm.screen = "detail" })
                        else EmptyMessage("Scegli un libro dalla libreria.")
                    "import" -> if (reorderId != null) ReorderScreen(vm, requireNotNull(reorderId)) else ImportPreview(vm, onReorder = { reorderId = it })
                    "settings" -> SettingsScreen(vm,
                        onTheme = { dialog = "theme" }, onSkips = { dialog = "skips" },
                        onBackup = { backupExport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "sottovoce-backup.json").putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                        onRestore = { backupImport.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)) },
                        onInstall = { dialog = "install" })
                }
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
            "edit" -> if (book != null) EditBookDialog(book, onDismiss = { dialog = null }) { title, author, narrator -> vm.saveMetadata(book, title, author, narrator); dialog = null }
            "speed" -> ChoiceDialog("Velocità di ascolto", listOf(.5f,.75f,1f,1.1f,1.25f,1.5f,1.75f,2f,2.5f,3f).map { it.toString()+"×" to it }, vm.now.speed, { dialog = null }) { vm.speed(it); dialog = null }
            "timer" -> ChoiceDialog("Timer di spegnimento", listOf("Disattivato" to 0,"15 minuti" to 15,"30 minuti" to 30,"45 minuti" to 45,"60 minuti" to 60,"Fine capitolo / traccia" to -1), null, { dialog = null }) { vm.timer(it); dialog = null }
            "theme" -> ChoiceDialog("Aspetto", listOf("Come il sistema" to "system","Chiaro" to "light","Scuro" to "dark"), vm.theme, { dialog = null }) { vm.setTheme(it); dialog = null }
            "skips" -> ChoiceDialog("Salti del lettore", listOf("Indietro 10 s · avanti 10 s" to (10 to 10),"Indietro 15 s · avanti 30 s" to (15 to 30),"Indietro 30 s · avanti 30 s" to (30 to 30),"Indietro 60 s · avanti 60 s" to (60 to 60)), vm.skipBack to vm.skipForward, { dialog = null }) { vm.setSkips(it.first,it.second); dialog = null }
            "bookmark" -> NoteDialog({ dialog = null }) { vm.addBookmark(it); dialog = null }
            "remove", "copies" -> if (book != null) AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == "copies") "Eliminare le copie nell’app?" else "Rimuovere il libro?") },
                text = { Text(if (dialog == "copies") "Verranno eliminate solo le copie audio gestite dall’app. Progressi e segnalibri rimangono; dovrai ricollegare gli audio. I file originali non saranno toccati." else "Il libro, i suoi progressi, i segnalibri e le eventuali copie audio nell’app saranno rimossi. I file originali scelti dal dispositivo non saranno toccati.") },
                confirmButton = { TextButton(onClick = { vm.removeBook(book, dialog == "copies"); dialog = null }) { Text("Rimuovi", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } })
            "install" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Installare l’aggiornamento?") },
                text = { Text("L’ascolto verrà fermato e la posizione salvata. Android potrebbe chiederti di autorizzare l’installazione da Sottovoce; dopo averlo fatto torna qui e premi nuovamente Installa.") },
                confirmButton = { TextButton(onClick = { dialog = null; vm.installUpdate { context.startActivity(it) } }) { Text("Continua") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Più tardi") } })
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

@Composable private fun LibraryScreen(books: List<Book>, last: Book?, onImport: () -> Unit, onBook: (Book) -> Unit, onPlay: (Book) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Tutti") }
    var sort by rememberSaveable { mutableStateOf("Recenti") }
    var sortOpen by remember { mutableStateOf(false) }
    val filtered = books.filter { b ->
        (b.title+" "+b.author+" "+b.narrator).contains(search, ignoreCase = true) && when (filter) {
            "In ascolto" -> b.lastPlayedAt > 0 && !b.completed
            "Da iniziare" -> b.lastPlayedAt == 0L && !b.completed
            "Completati" -> b.completed
            else -> true
        }
    }.let { if (sort == "Titolo") it.sortedWith { a,b -> NaturalOrder.compare(a.title,b.title) } else if (sort == "Autore") it.sortedBy { b -> b.author.lowercase() } else it }
    LazyColumn(Modifier.fillMaxSize().testTag("library"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("La tua libreria", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium); Text("I tuoi audiolibri, sul tuo dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (last != null) item { Hero(last, onPlay) }
        if (books.isEmpty()) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.LibraryBooks, null, Modifier.size(40.dp))
                Text("La prossima storia è già tua.", style = MaterialTheme.typography.headlineSmall)
                Text("Importa un MP3, un M4B o una cartella di capitoli. Nessun account, nessun catalogo online.")
                Button(onClick = onImport, Modifier.fillMaxWidth().testTag("import_button")) { Icon(Icons.Default.Add, null); Text("Importa audiolibri") }
            }
        } } else {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("I tuoi libri", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onImport) { Icon(Icons.Default.Add, null); Text("Importa") }
                Box { IconButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, "Ordina libri") }
                    DropdownMenu(sortOpen, { sortOpen = false }) { listOf("Recenti","Titolo","Autore").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortOpen = false }) } }
                }
            } }
            item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("library_search"), label = { Text("Cerca nella libreria") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Tutti","In ascolto","Da iniziare","Completati").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) } } }
            if (filtered.isEmpty()) item { EmptyMessage("Nessun libro corrisponde alla ricerca.") }
            items(filtered, key = { it.id }) { b -> BookRow(b) { onBook(b) } }
        }
    }
}

@Composable private fun Hero(book: Book, onPlay: (Book) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.width(86.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("RIPRENDI L’ASCOLTO", style = MaterialTheme.typography.labelSmall)
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                }
            }
            LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.Center) {
                Text("${(book.progress*100).toInt()}% · ${timeLabel(((book.durationMs-book.playedMs)/book.speed).toLong())} rimasti", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onPlay(book) }) { Icon(Icons.Default.PlayArrow, null); Text("Riprendi") }
            }
        }
    }
}
@Composable private fun BookRow(book: Book, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Cover(book, Modifier.width(58.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (book.needsRelink) "File da ricollegare" else "${timeLabel(book.durationMs)} · ${if (book.completed) "Completato" else if (book.lastPlayedAt > 0) "${(book.progress*100).toInt()}%" else "Da iniziare"}", style = MaterialTheme.typography.bodySmall, color = if (book.needsRelink) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun Cover(book: Book, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) { runCatching { book.coverPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull() }
    }
    val colors = listOf(Color(0xFFE3B786), Color(0xFFB4C8CE), Color(0xFFD6B0A3))
    Box(modifier.aspectRatio(.75f).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 5.dp)).background(colors[(book.title.hashCode() and Int.MAX_VALUE)%colors.size])) {
        if (image != null) Image(requireNotNull(image), "Copertina di ${book.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Color(0xFF302C22), maxLines = 5, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.Headphones, null, tint = Color(0xFF514632), modifier = Modifier.align(Alignment.End))
        }
    }
}
@Composable private fun MiniPlayer(book: Book, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    Surface(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, null)
                Column { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text(if (playing) "In riproduzione" else "In pausa", style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = onPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pausa" else "Riprendi ascolto") }
        }
    }
}

private data class ChapterRow(val index: Int, val position: Long, val title: String)
private fun chapters(book: Book): List<ChapterRow> = book.tracks.flatMapIndexed { i, t ->
    if (t.chapters.isEmpty()) listOf(ChapterRow(i, 0, t.name)) else t.chapters.map { ChapterRow(i, it.startMs, it.title) }
}
@Composable private fun DetailScreen(book: Book, bookmarks: List<Bookmark>, onPlay: (Int?,Long?) -> Unit,
    onEdit: () -> Unit, onRelink: () -> Unit, onComplete: () -> Unit, onRemove: () -> Unit, onRemoveCopies: () -> Unit, onDeleteMark: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(book, Modifier.width(160.dp)) } }
        item { Text(book.title, style = MaterialTheme.typography.headlineMedium); if(book.author.isNotBlank()) Text(book.author); if(book.narrator.isNotBlank()) Text("Voce: ${book.narrator}", style = MaterialTheme.typography.bodySmall) }
        item { Text("${timeLabel(book.durationMs)} · ${book.tracks.size} ${if(book.tracks.size==1) "file" else "file"} · ${if(book.tracks.any { it.owned }) "Copie nell’app" else "File originali"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = { if(book.needsRelink) onRelink() else onPlay(null,null) }, Modifier.fillMaxWidth()) { Icon(if(book.needsRelink) Icons.Default.FolderOpen else Icons.Default.PlayArrow,null); Text(if(book.needsRelink) "Ricollega file" else if(book.lastPlayedAt>0) "Riprendi" else "Ascolta") } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onEdit) { Icon(Icons.Default.Edit,null); Text("Modifica") }
            OutlinedButton(onClick=onComplete) { Icon(Icons.Default.CheckCircle,null); Text(if(book.completed) "Da ascoltare" else "Completato") }
            TextButton(onClick=onRelink) { Text("Ricollega file") }
        } }
        item { Text("Capitoli e tracce",style=MaterialTheme.typography.titleLarge)
            if(book.tracks.size==1 && book.tracks.first().chapters.isEmpty()) Text("Nessun capitolo incorporato riconosciuto: ascolto come traccia unica.",style=MaterialTheme.typography.bodySmall) }
        items(chapters(book)) { c -> ListItem(headlineContent={Text(c.title)}, supportingContent={Text("Traccia ${c.index+1} · ${timeLabel(c.position)}")}, leadingContent={Icon(Icons.Default.PlayArrow,null)}, modifier=Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled=!book.needsRelink) {onPlay(c.index,c.position)}) }
        item { Text("Segnalibri",style=MaterialTheme.typography.titleLarge) }
        if(bookmarks.isEmpty()) item {Text("Aggiungili dal lettore per ritrovare un passaggio.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(bookmarks,key={it.id}) { m -> ListItem(headlineContent={Text(m.note.ifBlank {"Segnalibro"})}, supportingContent={Text("Traccia ${m.trackIndex+1} · ${timeLabel(m.positionMs)}")}, modifier=Modifier.clickable(enabled=!book.needsRelink){onPlay(m.trackIndex,m.positionMs)}, trailingContent={IconButton(onClick={onDeleteMark(m.id)}){Icon(Icons.Default.Delete,"Elimina segnalibro")}}) }
        if(book.tracks.any { it.owned }) item { OutlinedButton(onClick=onRemoveCopies,Modifier.fillMaxWidth()){Text("Elimina copie audio nell’app")} }
        item { TextButton(onClick=onRemove,Modifier.fillMaxWidth()){Text("Rimuovi dalla libreria",color=MaterialTheme.colorScheme.error)} }
    }
}

@UnstableApi
@Composable private fun PlayerScreen(book: Book, vm: LibraryViewModel, timer: String, onSpeed:()->Unit,onTimer:()->Unit,onBookmark:()->Unit,onChapters:()->Unit) {
    val now=vm.now
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration=now.duration.takeIf{it>0}?:book.tracks.getOrNull(now.trackIndex)?.durationMs?:0
    val totalPlayed=book.tracks.take(now.trackIndex).sumOf{it.durationMs}+now.position
    LazyColumn(Modifier.fillMaxSize().testTag("player"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item { Cover(book,Modifier.widthIn(max=220.dp).fillMaxWidth(.58f)) }
        item { Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(book.title,style=MaterialTheme.typography.headlineSmall)
            if(book.author.isNotBlank()) Text(book.author,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Traccia ${now.trackIndex+1} di ${book.tracks.size}",style=MaterialTheme.typography.bodySmall)
        } }
        item { Column {
            Text(book.tracks.getOrNull(now.trackIndex)?.name.orEmpty(),maxLines=2,style=MaterialTheme.typography.labelLarge)
            Slider(value=dragging?:now.position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={dragging=it},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0,onValueChangeFinished={dragging?.let{vm.seek(it.toLong())};dragging=null},modifier=Modifier.testTag("seek_slider"))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(timeLabel((dragging?.toLong()?:now.position)),style=MaterialTheme.typography.bodySmall);Text(timeLabel(duration),style=MaterialTheme.typography.bodySmall)}
            Spacer(Modifier.height(8.dp)); Text("Libro: ${timeLabel(totalPlayed)} / ${timeLabel(book.durationMs)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedButton(onClick={vm.skip(-vm.skipBack)}){Text("↶ ${vm.skipBack} s")}
            FilledIconButton(onClick=vm::togglePlay,modifier=Modifier.size(76.dp).testTag("play_pause")){Icon(if(now.playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(now.playing)"Pausa" else "Riprendi ascolto",Modifier.size(38.dp))}
            OutlinedButton(onClick={vm.skip(vm.skipForward)}){Text("${vm.skipForward} s ↷")}
        } }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onSpeed){Icon(Icons.Default.Speed,null);Text("${now.speed}×")}
            OutlinedButton(onClick=onTimer){Icon(Icons.Default.Timer,null);Text("Timer")}
            OutlinedButton(onClick=onBookmark){Icon(Icons.Default.BookmarkAdd,null);Text("Segnalibro")}
        } }
        if(timer.isNotEmpty()) item { Text("Spegnimento: $timer",color=MaterialTheme.colorScheme.primary) }
        item { TextButton(onClick=onChapters){Icon(Icons.Default.List,null);Text("Capitoli e segnalibri")} }
    }
}

@UnstableApi
@Composable private fun ImportPreview(vm:LibraryViewModel,onReorder:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text(if(vm.relinkId!=null)"Ricollega la registrazione" else "Controlla l’importazione",style=MaterialTheme.typography.headlineMedium) }
        if(vm.relinkId!=null) item { Text("Scegli la stessa registrazione con lo stesso numero e ordine di file. Confermando manterrai i vecchi progressi e segnalibri; una lettura diversa potrebbe non corrispondere.",color=MaterialTheme.colorScheme.error) }
        else item { ListItem(headlineContent={Text("Copia i file nell’app")},supportingContent={Text(if(vm.copyImports)"Usa spazio aggiuntivo. Conserva gli originali separatamente." else "Usa gli originali: non spostarli dopo l’importazione.")},trailingContent={Switch(vm.copyImports,{vm.copyImports=it})}) }
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
@Composable private fun SettingsScreen(vm:LibraryViewModel,onTheme:()->Unit,onSkips:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onInstall:()->Unit) {
    val context=LocalContext.current
    val storage by produceState(0L) {value=withContext(Dispatchers.IO){File(context.filesDir,"books").walkTopDown().filter{it.isFile}.sumOf{it.length()}}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Text("Impostazioni",style=MaterialTheme.typography.headlineLarge)}
        item {SettingRow("Aspetto",when(vm.theme){"dark"->"Scuro";"light"->"Chiaro";else->"Come il sistema"},Icons.Default.Palette,onTheme)}
        item {SettingRow("Salti del lettore","Indietro ${vm.skipBack} s · avanti ${vm.skipForward} s",Icons.Default.Replay,onSkips)}
        item {Text("Backup locale",style=MaterialTheme.typography.titleLarge);Text("Salva libreria, progressi, segnalibri e preferenze. Gli audio vanno conservati separatamente.",style=MaterialTheme.typography.bodyMedium)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=onBackup,modifier=Modifier.weight(1f)){Text("Esporta")};OutlinedButton(onClick=onRestore,modifier=Modifier.weight(1f)){Text("Ripristina")}}}
        item {Text("Spazio gestito: ${storage/1024/1024} MB",style=MaterialTheme.typography.titleMedium);Text("Copie audio e copertine. Per eliminare una copia apri la scheda del libro: gli originali restano intatti.",style=MaterialTheme.typography.bodySmall)}
        item {Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Aggiornamenti dell’app",style=MaterialTheme.typography.titleLarge)
            Text("Sottovoce ${BuildConfig.VERSION_NAME}")
            if(BuildConfig.DEBUG) Text("Versione di sviluppo. Installa l’APK release per gli aggiornamenti firmati.",style=MaterialTheme.typography.bodySmall)
            else {
                Text("Il controllo è manuale. Solo questa funzione contatta GitHub; i tuoi audio non vengono inviati online.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=vm::checkUpdate,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Refresh,null);Text("Controlla aggiornamenti")}
                vm.release?.let{r->
                    Text("Disponibile ${r.versionName} · ${r.size/1024/1024} MB",fontWeight=FontWeight.Medium)
                    Text(r.notes)
                    Button(onClick=if(vm.updateFile!=null)onInstall else vm::downloadUpdate,modifier=Modifier.fillMaxWidth(),enabled=r.minSdk<=Build.VERSION.SDK_INT){Text(if(vm.updateFile!=null)"Installa aggiornamento" else "Scarica aggiornamento")}
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
@Composable private fun EmptyMessage(text:String){Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun <T> ChoiceDialog(title:String,choices:List<Pair<String,T>>,selected:T?,onDismiss:()->Unit,onSelect:(T)->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={LazyColumn{items(choices){(label,value)->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable{onSelect(value)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==value,onClick={onSelect(value)});Text(label)}}}},confirmButton={TextButton(onClick=onDismiss){Text("Chiudi")}})
}
@Composable private fun EditBookDialog(book:Book,onDismiss:()->Unit,onSave:(String,String,String)->Unit){
    var title by remember{mutableStateOf(book.title)};var author by remember{mutableStateOf(book.author)};var narrator by remember{mutableStateOf(book.narrator)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Modifica libro")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it.take(1000)},label={Text("Titolo")});OutlinedTextField(author,{author=it.take(1000)},label={Text("Autore")});OutlinedTextField(narrator,{narrator=it.take(1000)},label={Text("Narratore")})
    }},confirmButton={TextButton(onClick={onSave(title,author,narrator)},enabled=title.isNotBlank()){Text("Salva")}},dismissButton={TextButton(onClick=onDismiss){Text("Annulla")}})
}
@Composable private fun NoteDialog(onDismiss:()->Unit,onSave:(String)->Unit){var note by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Nuovo segnalibro")},text={OutlinedTextField(note,{note=it.take(10_000)},label={Text("Nota facoltativa")},modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={onSave(note)}){Text("Salva")}},dismissButton={TextButton(onClick=onDismiss){Text("Annulla")}})
}
