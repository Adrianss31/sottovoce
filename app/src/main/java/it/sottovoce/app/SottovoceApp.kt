package it.sottovoce.app

import android.app.Application
import it.sottovoce.app.data.LibraryRepository
import it.sottovoce.app.data.AudioImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SottovoceApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        android.util.Log.e("Sottovoce", "Operazione locale non riuscita", error)
        it.sottovoce.app.playback.PlaybackSignals.error.value = "Impossibile salvare o leggere i dati. Controlla lo spazio disponibile e riapri l’app."
    })
    lateinit var library: LibraryRepository
        private set
    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        scope.launch { library.load(); AudioImporter(this@SottovoceApp, library).refreshChapters() }
    }
}
