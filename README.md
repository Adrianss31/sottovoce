# Sottovoce

[![Android](https://github.com/Adrianss31/sottovoce/actions/workflows/android.yml/badge.svg)](https://github.com/Adrianss31/sottovoce/actions/workflows/android.yml)

Un lettore Android per gli audiolibri che possiedi già. Interfaccia in italiano, nessun account, nessuna pubblicità, ascolto offline.

**Stato:** versione 0.1.6. Scarica l’APK dalla [pagina delle release](https://github.com/Adrianss31/sottovoce/releases/latest). Risultati delle prove e limiti in [VALIDATION.md](docs/VALIDATION.md).

## Cosa fa

- Importa file locali o cartelle, con anteprima del titolo, autore e ordine delle tracce.
- Collega i file originali oppure ne conserva una copia nello spazio privato dell’app.
- Organizza libri in una libreria visuale a griglia con ricerca, ordinamento, filtri, copertina incorporata e metadati modificabili.
- Riproduce in sottofondo con avanzamento del capitolo nella scheda multimediale di Android, play/pausa, indietro 10 secondi e timer rapido da 30 minuti.
- Salva posizione e velocità per ciascun libro; offre salti, velocità 0,5–3×, timer e segnalibri con note.
- Esporta e ripristina un backup JSON di libreria, progressi, segnalibri e preferenze. **Gli audio non sono inclusi.**
- Controlla automaticamente gli aggiornamenti all’apertura e mostra un banner; un tocco scarica, verifica e apre l’installazione di Android.
- Mostra avanzamento, durata e tempo residuo del capitolo corrente, mantenendo anche tempi e avanzamento complessivi del libro.
- Riunisce scheda del libro e lettore: copertina, progresso del capitolo, comandi, timer, velocità, capitoli e segnalibri restano nella stessa schermata.
- Usa animazioni legate al contesto: il libro e la copertina si espandono dalla libreria, il ritorno scorre indietro e le altre destinazioni scorrono in avanti.

**Non contiene fonti web, cataloghi, streaming né download di audiolibri.** La rete viene usata all’apertura per verificare il piccolo descrittore firmato degli aggiornamenti e, solo dopo un tuo tocco, per scaricare l’APK. Il collegamento “Codice sorgente” apre il browser esterno.

## Installazione

Android 8.0 o successivo. Scarica l’APK dalla [pagina delle release](https://github.com/Adrianss31/sottovoce/releases), aprilo sul telefono e autorizza l’installazione per l’app utilizzata per aprirlo, se Android lo richiede. Non occorrono permessi di accesso generale all’archivio: scegli esplicitamente file e cartelle tramite il selettore di Android.

Per i successivi aggiornamenti usa il banner mostrato automaticamente in alto: **Aggiorna** scarica e verifica il pacchetto, poi apre l’installazione. Se è la prima volta, Android chiede di autorizzare Sottovoce come origine; tornando indietro l’app apre automaticamente l’installazione. La conferma finale del sistema non può essere eliminata. Non disinstallare la vecchia versione: perderesti i dati privati e le copie degli audio. Fai un backup prima di aggiornamenti importanti.

La build debug ha un’identità separata (`it.sottovoce.app.debug`) e non viene aggiornata dalle release.

## Importazione e formati

MP3, M4B/M4A/MP4 audio, AAC, OGG, OPUS, FLAC e WAV, nei limiti dei codec supportati dal dispositivo e da Media3. Nessun supporto DRM/AAX. La selezione multipla crea un solo libro; nella selezione di una cartella, ogni sottocartella di primo livello è proposta come libro separato. Le tracce vengono ordinate numericamente, con possibilità di riordinarle prima della conferma.

I capitoli M4B vengono letti sia dal formato Nero `chpl`, sia dalle tracce di capitoli QuickTime referenziate (`chap`) usate da molti file riconosciuti da ffprobe e Audiobookshelf. I titoli UTF-8 e UTF-16 sono supportati. Per libri composti da più file, ogni file è una traccia navigabile. Le copertine sono ricavate dai metadati incorporati; in loro assenza viene mostrata una copertina tipografica.

Se sposti o cancelli un originale collegato, ricollegalo dalla scheda del libro. Usa la stessa registrazione, lo stesso numero di file e lo stesso ordine: posizioni e segnalibri dipendono da essi. La rimozione di un libro non cancella mai gli originali scelti dal dispositivo.

## Backup e privacy

Il backup include i dati di ascolto e le preferenze, **non gli audio o le copertine**. Il ripristino sostituisce la libreria corrente dopo conferma. Prima della sostituzione viene mantenuta una copia locale di sicurezza dei dati, e gli audio non vengono cancellati. Per sicurezza il backup non può imporre percorsi di file: dopo il ripristino devi ricollegare gli audio.

Nessuna telemetria, analisi, sincronizzazione cloud o credenziale GitHub nell’app. Un controllo aggiornamenti contatta GitHub, che può vedere i normali dati di connessione, come l’indirizzo IP. Leggi [la progettazione tecnica](docs/ARCHITECTURE.md) per archiviazione, permessi e modello di verifica.

## Compilazione

JDK 17, Android SDK 36, Build Tools 35.0.0; Gradle Wrapper 8.13 incluso e verificato con checksum. AGP 8.13.2, Kotlin 2.1.20, Jetpack Compose e Media3.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Il secondo comando richiede un emulatore o dispositivo Android 13+ per la suite strumentale corrente. La compatibilità minima dichiarata dall’app resta Android 8.0.

[GitHub Actions](.github/workflows/android.yml) compila ad ogni push/PR e su avvio manuale, esegue unit test e lint, poi prove su un emulatore Android 15. Gli artifact contengono APK, rapporti e schermate; sono conservati 30 giorni. L’APK release prodotto da Actions è **senza firma**, finché non viene firmato localmente per la pubblicazione.

La firma locale evita di trasferire le chiavi private a GitHub. Il repository contiene soltanto la chiave **pubblica** che verifica il descrittore degli aggiornamenti. Istruzioni per le prossime release in [RELEASING.md](docs/RELEASING.md).

## Evoluzioni possibili

Android Auto dedicato, verifica più estesa su dispositivi fisici e versioni Android diverse, recupero assistito dei file dopo un ripristino, backup opzionale comprensivo degli audio. Queste funzioni non sono incluse. L’aggiunta di download di audiolibri rimane esclusa dall’ambito attuale.

## Licenza

Codice originale MIT; le librerie conservano le proprie licenze. Le dipendenze AndroidX e Kotlin sono distribuite principalmente sotto Apache 2.0. Nessun audiolibro di terzi è incluso; l’audio usato nei test è un tono sintetico generato dal test stesso.
