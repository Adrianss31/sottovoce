@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package it.sottovoce.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import it.sottovoce.app.LibraryViewModel
import it.sottovoce.app.data.*
import it.sottovoce.app.playback.PlaybackSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Bounds of every cover that can start or end the cover → player transition. */
internal class CoverOrigins {
    val bounds = mutableStateMapOf<String, Rect>()
    val radii = mutableMapOf<String, Dp>()
}

/** The cover travelling between an origin and the player hero. */
internal class CoverFlight {
    var bookId by mutableStateOf<String?>(null)
    var originKey by mutableStateOf<String?>(null)
    var visible by mutableStateOf(false)
    var hideHero by mutableStateOf(false)
    var from by mutableStateOf(Rect.Zero)
    var to by mutableStateOf(Rect.Zero)
    var fromRadius by mutableStateOf(16.dp)
    var toRadius by mutableStateOf(26.dp)
    var drop by mutableStateOf(false)
    var running = false
    val progress = Animatable(0f)
}

internal val LocalCoverOrigins = staticCompositionLocalOf { CoverOrigins() }
internal val LocalCoverFlight = staticCompositionLocalOf { CoverFlight() }

/** Registers a cover as a transition origin and hides it while its twin is flying. */
internal fun Modifier.coverOrigin(key: String, radius: Dp): Modifier = composed {
    val origins = LocalCoverOrigins.current
    val flight = LocalCoverFlight.current
    DisposableEffect(key) { onDispose { origins.bounds.remove(key) } }
    this.onGloballyPositioned {
        origins.bounds[key] = Rect(it.positionInRoot(), it.size.toSize())
        origins.radii[key] = radius
    }.graphicsLayer { alpha = if (flight.visible && flight.originKey == key) 0f else 1f }
}

internal const val HeroKey = "hero"

internal enum class Sheet { SPEED, SLEEP, MARK, MANAGE, EDIT, REMOVE, COPIES, RESTORE, CHAPTERS }

@UnstableApi
@Composable
fun SottovoceUi(vm: LibraryViewModel) {
    ProvideSottovoceMotion { SottovoceRoot(vm) }
}

@UnstableApi
@Composable
private fun SottovoceRoot(vm: LibraryViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val policy = LocalMotionPolicy.current
    val scope = rememberCoroutineScope()
    val books by vm.library.books.collectAsStateWithLifecycle()
    val bookmarks by vm.library.bookmarks.collectAsStateWithLifecycle()
    val timerLabel by PlaybackSignals.timer.collectAsStateWithLifecycle()
    val timerRemaining by PlaybackSignals.timerRemainingMs.collectAsStateWithLifecycle()
    val timerTotal by PlaybackSignals.timerTotalMs.collectAsStateWithLifecycle()
    // Day or night theme: by hand, with the system, from sunset to sunrise or 22:00–07:00.
    val themeSettings = vm.themeSettings
    var clock by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(themeSettings.auto, themeSettings.schedule) {
        if (themeSettings.auto && themeSettings.schedule != ThemeSchedule.SYSTEM) while (true) {
            clock = ZonedDateTime.now()
            delay((60 - clock.second) * 1000L)
        }
    }
    val night = vm.themePreviewNight?.takeIf { vm.screen == "settings" } ?: themeSettings.isNight(isSystemInDarkTheme(), clock)
    val resolvedTheme = if (night) themeSettings.night else themeSettings.day
    val style = remember(resolvedTheme) { svStyle(resolvedTheme) }
    if (SvLook.style != style) SvLook.style = style
    if (SvLook.density != density.density) SvLook.density = density.density
    val sv = animatedPalette(svPalette(resolvedTheme), policy.animationsEnabled)
    val origins = remember { CoverOrigins() }
    val flight = remember { CoverFlight() }
    val playerReveal = remember { Animatable(0f) }
    val riseClock = remember { Animatable(0f) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var playerTab by rememberSaveable { mutableStateOf("chapters") }
    var toast by remember { mutableStateOf<Toast?>(null) }

    val active = books.find { it.id == vm.now.bookId }
    val current = active ?: books.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }
    val openBook = books.find { it.id == vm.selectedId }

    // Launchers ---------------------------------------------------------------------------------
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
    fun pickFolder() {
        folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    fun exportBackup() {
        backupExport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "sottovoce-backup.json").putExtra(Intent.EXTRA_LOCAL_ONLY, true))
    }
    fun restoreBackup() {
        backupImport.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true))
    }

    // Navigation ----------------------------------------------------------------------------------
    fun openPlayer(book: Book, key: String?) {
        if (flight.running) return
        flight.running = true
        vm.selectedId = book.id
        playerTab = "chapters"
        flight.bookId = book.id
        flight.originKey = key
        flight.hideHero = true
        flight.drop = false
        origins.bounds.remove(HeroKey)
        vm.playerOpen = true
        scope.launch {
            try {
                playerReveal.snapTo(0f); riseClock.snapTo(0f)
                val from = key?.let { origins.bounds[it] }
                val to = withTimeoutOrNull(700) { snapshotFlow { origins.bounds[HeroKey] }.filterNotNull().first() }
                launch { playerReveal.animateTo(1f, tween(policy.durationMillis(340), easing = LinearEasing)) }
                launch { riseClock.animateTo(1600f, tween(policy.durationMillis(1600), easing = LinearEasing)) }
                if (from != null && to != null && policy.animationsEnabled) {
                    flight.from = from; flight.to = to
                    flight.fromRadius = origins.radii[key] ?: 16.dp; flight.toRadius = 26.dp
                    flight.progress.snapTo(0f)
                    flight.visible = true
                    flight.progress.animateTo(1f, tween(SvMotion.DurationCoverOpen, easing = SvMotion.Emphasized))
                } else riseClock.snapTo(1600f)
            } finally {
                flight.visible = false; flight.hideHero = false; flight.running = false
                playerReveal.snapTo(1f)
            }
        }
    }
    fun closePlayer() {
        if (!vm.playerOpen || flight.running) return
        flight.running = true
        sheet = null
        val bookId = vm.selectedId
        var key = flight.originKey
        if ((key == "panel" || key == "pill") && current?.id != bookId) key = "g-$bookId"
        if (key == "pill" && origins.bounds["pill"] == null) key = "panel"
        scope.launch {
            try {
                val from = origins.bounds[HeroKey]
                val to = key?.let { origins.bounds[it] }
                launch { playerReveal.animateTo(0f, tween(policy.durationMillis(380), policy.durationMillis(60), easing = LinearEasing)) }
                if (from != null && policy.animationsEnabled) {
                    flight.from = from
                    flight.originKey = key
                    if (to != null) {
                        flight.to = to; flight.drop = false
                        flight.fromRadius = 26.dp; flight.toRadius = origins.radii[key] ?: 16.dp
                    } else {
                        flight.to = from.translate(Offset(0f, with(density) { 60.dp.toPx() }))
                        flight.drop = true; flight.fromRadius = 26.dp; flight.toRadius = 26.dp
                    }
                    flight.hideHero = true
                    flight.progress.snapTo(0f)
                    flight.visible = true
                    flight.progress.animateTo(1f, tween(if (to != null) SvMotion.DurationCoverClose else 380, easing = SvMotion.Emphasized))
                } else playerReveal.snapTo(0f)
            } finally {
                vm.playerOpen = false
                flight.visible = false; flight.hideHero = false; flight.running = false
            }
        }
    }
    fun openSub(screen: String) {
        if (screen == "stats") vm.refreshStatsNow()
        if (screen == "import") { vm.importDone = null }
        vm.screen = screen
    }
    fun closeSub() {
        if (vm.screen == "import") { vm.candidates = emptyList(); vm.relinkId = null; vm.importDone = null }
        vm.screen = "library"
        vm.themePreviewNight = null
    }

    // The ViewModel may ask to show a book (e.g. a book that needs relinking).
    LaunchedEffect(vm.playerOpen) {
        if (vm.playerOpen && !flight.running && playerReveal.value == 0f) playerReveal.snapTo(1f).also { riseClock.snapTo(1600f) }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { text ->
            val reset = text == "Libro segnato come non iniziato."
            toast = Toast(text, if (reset) "Annulla" else null, System.nanoTime())
            vm.message = null
        }
    }

    // Back: sheet → player → sub-screen.
    LaunchedEffect(vm.pendingBackup) { if (vm.pendingBackup != null) sheet = Sheet.RESTORE }
    var lastSheet by remember { mutableStateOf<Sheet?>(null) }
    if (sheet != null && lastSheet != sheet) lastSheet = sheet
    BackHandler(sheet != null) { if (sheet == Sheet.RESTORE) vm.pendingBackup = null; sheet = null }
    val relinking = vm.screen == "import" && vm.relinkId != null
    BackHandler(sheet == null && vm.playerOpen && !relinking) { closePlayer() }
    BackHandler(sheet == null && (!vm.playerOpen || relinking) && vm.screen != "library" && vm.busy == null) { closeSub() }

    // Status bar icons follow the surface below them: book ink in the player, theme ink elsewhere.
    val playerColors = openBook?.let { rememberBookColors(it) }
    val lightStatusIcons = if (vm.playerOpen && playerColors != null) playerColors.c1.luminance() > .5f else !sv.dark
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightStatusIcons
                isAppearanceLightNavigationBars = lightStatusIcons
            }
        }
    }

    val subTarget = if (vm.screen != "library") 1f else 0f
    val subProgress by animateFloatAsState(subTarget, policy.emphasized(SvMotion.DurationSubScreen), label = "sotto-schermata")
    var shownSub by remember { mutableStateOf(vm.screen) }
    if (vm.screen != "library") shownSub = vm.screen

    CompositionLocalProvider(LocalSv provides sv, LocalCoverOrigins provides origins, LocalCoverFlight provides flight) {
    MaterialTheme(colorScheme = themeColors(resolvedTheme), typography = Typography()) {
    BoxWithConstraints(Modifier.fillMaxSize().background(sv.bg).testTag("app_scaffold")) {
        val widthPx = constraints.maxWidth.toFloat()
        val homeState = androidx.compose.foundation.lazy.rememberLazyListState()
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -.24f * widthPx * subProgress }) {
            HomeScreen(vm, books, current, active, timerLabel, homeState,
                onOpenBook = { b, key -> openPlayer(b, key) },
                onPlay = { play(it) },
                onOpenSub = ::openSub,
                onUpdate = { vm.updateAndInstall(launchUpdateIntent) },
                onImportFiles = ::pickFiles)
            if (subProgress > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = subProgress }.background(Color.Black.copy(alpha = .45f)))
        }
        val subLayer: @Composable () -> Unit = { if (subProgress > 0f || vm.screen != "library") {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = widthPx * 1.05f * (1f - subProgress) }) {
                when (shownSub) {
                    "series" -> SeriesScreen(vm, books, vm.selectedSeries, active?.id, vm.now.playing, onBack = ::closeSub,
                        onOpenBook = { b, key -> openPlayer(b, key) })
                    "stats" -> StatsScreen(vm, books, active = subTarget == 1f, onBack = ::closeSub)
                    "import" -> ImportScreen(vm, books, onBack = ::closeSub, onFiles = ::pickFiles, onFolder = ::pickFolder,
                        onListen = { id ->
                            closeSub()
                            books.find { it.id == id }?.let { play(it) }
                            scope.launch { homeState.scrollToItem(0) }
                        })
                    "settings" -> SettingsScreen(vm, onBack = ::closeSub, onExport = ::exportBackup, onRestore = ::restoreBackup,
                        onInstall = { vm.updateAndInstall(launchUpdateIntent); closeSub() })
                }
            }
        } }
        val subAbovePlayer = shownSub == "import" && vm.relinkId != null
        if (!subAbovePlayer) subLayer()
        // Floating pill: only on the home, once the now-playing card has scrolled away.
        val pastCard by remember { derivedStateOf {
            homeState.firstVisibleItemIndex > 1 || (homeState.firstVisibleItemIndex == 1 && homeState.firstVisibleItemScrollOffset > with(density) { 300.dp.toPx() }) ||
                (homeState.firstVisibleItemIndex == 0 && homeState.firstVisibleItemScrollOffset > with(density) { 300.dp.toPx() })
        } }
        if (current != null) FloatingPill(vm, current, active?.id == current.id && vm.now.playing,
            visible = pastCard && vm.screen == "library" && !vm.playerOpen,
            onOpen = { openPlayer(current, "pill") },
            onToggle = { if (active?.id == current.id) vm.togglePlay() else play(current) },
            onBack = { if (active?.id == current.id) vm.skip(-vm.skipBack) else play(current) })

        if (vm.playerOpen && openBook != null) {
            val reveal = playerReveal.value
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
                PlayerScreen(vm, openBook, bookmarks.filter { it.bookId == openBook.id }, isActive = active?.id == openBook.id,
                    timerLabel = timerLabel, timerRemaining = timerRemaining, riseClock = { riseClock.value },
                    heroHidden = flight.hideHero, tab = playerTab, onTab = { playerTab = it },
                    onClose = ::closePlayer, onPlay = { i, p -> play(openBook, i, p) },
                    onSheet = { sheet = it },
                    onRelink = { vm.relinkId = openBook.id; pickFiles() })
            }
        }
        if (subAbovePlayer) subLayer()
        if (flight.visible) {
            val flyBook = books.find { it.id == flight.bookId }
            if (flyBook != null) FlyingCover(flyBook, flight)
        }
        val sheetBook = openBook ?: current
        SheetHost(visible = sheet != null, onDismiss = { if (sheet == Sheet.RESTORE) vm.pendingBackup = null; sheet = null }) {
            val shown = lastSheet
            if (shown == Sheet.RESTORE) RestoreSheet(vm) { sheet = null }
            else if (sheetBook != null) when (shown) {
                Sheet.SPEED -> SpeedSheet(vm, sheetBook, active?.id == sheetBook.id)
                Sheet.SLEEP -> SleepSheet(vm, sheetBook, timerLabel, timerRemaining, timerTotal,
                    onClose = { sheet = null }, play = { play(it) })
                Sheet.CHAPTERS -> ChaptersSheet(sheetBook, if (active?.id == sheetBook.id) sheetBook.live(vm.now) else sheetBook,
                    bookmarks.filter { it.bookId == sheetBook.id }) { c ->
                    sheet = null
                    if (active?.id == sheetBook.id) vm.playBook(sheetBook, c.trackIndex, c.startMs) else play(sheetBook, c.trackIndex, c.startMs)
                    vm.message = "Capitolo ${c.ordinal}"
                }
                Sheet.MARK -> BookmarkSheet(vm, sheetBook, active?.id == sheetBook.id) { note ->
                    vm.addBookmark(sheetBook, note); sheet = null; playerTab = "bookmarks"
                }
                Sheet.MANAGE -> ManageSheet(vm, sheetBook,
                    onEdit = { sheet = Sheet.EDIT },
                    onReset = { sheet = null; vm.markNotStarted(sheetBook) },
                    onRelink = { sheet = null; vm.relinkId = sheetBook.id; pickFiles() },
                    onRemoveCopies = { sheet = Sheet.COPIES },
                    onRemove = { sheet = Sheet.REMOVE },
                    onComplete = { sheet = null; vm.markCompleted(sheetBook) })
                Sheet.EDIT -> EditSheet(sheetBook) { title, author, narrator, series, position ->
                    vm.saveMetadata(sheetBook, title, author, narrator, series, position); sheet = null
                }
                Sheet.REMOVE -> ConfirmSheet("Rimuovere dalla libreria?",
                    "Il libro, i progressi, i segnalibri e le eventuali copie nell’app saranno rimossi. I file originali sul dispositivo non vengono mai eliminati.",
                    "Rimuovi dalla libreria", onCancel = { sheet = Sheet.MANAGE }) {
                    sheet = null
                    scope.launch { delay(250); closePlayer(); delay(600); vm.removeBook(sheetBook, false) }
                }
                Sheet.COPIES -> ConfirmSheet("Eliminare le copie nell’app?",
                    "Verranno eliminate solo le copie audio gestite dall’app. Progressi e segnalibri rimangono; dovrai ricollegare gli audio. Gli originali non saranno toccati.",
                    "Elimina copie", onCancel = { sheet = Sheet.MANAGE }) { sheet = null; vm.removeBook(sheetBook, true) }
                else -> {}
            }
        }
        ThemeTexture(style.fx, style.fxAlpha, sv.dark)
        ToastHost(toast, onAction = { vm.undoReset() }, onDone = { toast = null })
        BusyOverlay(vm)
    }
    }
    }
}

internal data class Toast(val text: String, val action: String?, val id: Long)

@Composable
private fun FlyingCover(book: Book, flight: CoverFlight) {
    val colors = rememberBookColors(book)
    val density = LocalDensity.current
    val p = flight.progress.value
    val rect = lerp(flight.from, flight.to, p)
    val radius = flight.fromRadius + (flight.toRadius - flight.fromRadius) * p
    with(density) {
        BookCover(book, colors, Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(rect.width.toDp(), rect.height.toDp())
            .graphicsLayer { if (flight.drop) { alpha = 1f - p; val s = 1f - .15f * p; scaleX = s; scaleY = s } },
            radius = radius)
    }
}
