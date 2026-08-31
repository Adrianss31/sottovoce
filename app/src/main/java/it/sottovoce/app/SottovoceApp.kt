package it.sottovoce.app

import android.app.Application
import it.sottovoce.app.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SottovoceApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var library: LibraryRepository
        private set
    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        scope.launch { library.load() }
    }
}
