# Sottovoce

[![Android](https://github.com/Adrianss31/sottovoce/actions/workflows/android.yml/badge.svg)](https://github.com/Adrianss31/sottovoce/actions/workflows/android.yml)

Un lettore Android per gli audiolibri che possiedi già. Interfaccia in italiano, nessun account, nessuna pubblicità, ascolto offline.

**Stato del codice:** versione 0.4.3. Scarica l’ultima versione pubblicata dalla [pagina delle release](https://github.com/Adrianss31/sottovoce/releases/latest). Risultati delle prove e limiti in [VALIDATION.md](docs/VALIDATION.md).

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
- Usa un sistema di movimento legato al contesto: apertura e ritorno seguono la gerarchia di navigazione, mentre comandi, filtri, player e contenuti dinamici ricevono feedback visivi specifici.
- Riprende in modo intelligente tornando indietro da 5 a 30 secondi in base alla durata della pausa.
- Offre timer personalizzati, fine capitolo, dissolvenza, estensione di 10 minuti e attivazione automatica dopo un orario notturno scelto.
- Organizza i libri per serie e numero, con mosaico di copertine, progresso complessivo, ordinamento dedicato e vista compatta; nella pagina principale ogni serie appare come una tessera unica e un tocco mostra i suoi libri.
- Mostra statistiche di ascolto locali: riepilogo in home, oggi, ultimi 7 giorni, continuità, ultimi 6 mesi, libri e serie completati, titoli più ascoltati.
- Include un widget con capitolo e avanzamento e un riquadro dei comandi rapidi Android per play/pausa.

**Non contiene fonti web, cataloghi, streaming né download di audiolibri.** La rete viene usata all’apertura per verificare il piccolo descrittore firmato degli aggiornamenti e, solo dopo un tuo tocco, per scaricare l’APK. Il collegamento “Codice sorgente” apre il browser esterno.

## Novità della 0.4.3

- Rende speculare anche il movimento che porta dentro e fuori le pagine: l’animazione ora si vede come la stessa transizione in avanti e indietro, non più come una dissolvenza generica che copre il movimento.
- L’icona delle impostazioni, la tessera delle serie, il riepilogo del tuo ascolto, la copertina del libro, l’anteprima di importazione e la schermata di riordino delle tracce ora si trasformano nel contenitore di destinazione quando le tocchi, e tornano al loro posto quando torni indietro.

## Novità della 0.4.2

- Rende l’animazione di ritorno speculare al percorso di andata: la pagina in entrata scivola dal bordo opposto e quella in uscita torna nello stesso verso da cui era arrivata, così il gesto di back legge come l’esatto inverso dell’apertura.
- Allinea la comparsa e la scomparsa dell’icona “indietro” nella barra superiore alla direzione del movimento: appare scivolando e scompare tornando indietro, invece di restare ferma durante il gesto.
- Allinea anche la transizione tra l’anteprima di importazione e la schermata di riordino delle tracce, in modo che apri e chiudi il riordino come un unico movimento coerente.

## Novità della 0.4.1

- Conserva posizione di scorrimento, ricerca, filtro e ordinamento quando torni da un libro alla libreria o a una serie.
- Riproduce i grandi file capitolati con una sola sorgente fisica, evitando di duplicare in memoria le tabelle MP4 quando cambi capitolo.
- Selezionare un altro capitolo dello stesso libro ora esegue un salto diretto senza ricreare la playlist né interrompere la riproduzione.

## Novità della 0.4.0

- Rende coerenti durata, curve e direzione delle transizioni tra libreria, serie, libro, statistiche e impostazioni.
- Distingue le animazioni di navigazione dai feedback locali di riproduzione, ricerca, filtri, capitoli, segnalibri, timer e velocità.
- Migliora continuità e leggibilità dei cambi di stato del player, del mini-player, delle barre di avanzamento e dei contenuti che entrano o si riordinano.

## Installazione

Android 8.0 o successivo. Scarica l’APK dalla [pagina delle release](https://github.com/Adrianss31/sottovoce/releases), aprilo sul telefono e autorizza l’installazione per l’app utilizzata per aprirlo, se Android lo richiede. Non occorrono permessi di accesso generale all’archivio: scegli esplicitamente file e cartelle tramite il selettore di Android.

Per i successivi aggiornamenti usa il banner mostrato automaticamente in alto: **Aggiorna** scarica e verifica il pacchetto, poi apre l’installazione. Se è la prima volta, Android chiede di autorizzare Sottovoce come origine; tornando indietro l’app apre automaticamente l’installazione. La conferma finale del sistema non può essere eliminata. Non disinstallare la vecchia versione: perderesti i dati privati e le copie degli audio. Fai un backup prima di aggiornamenti importanti.

La build debug ha un’identità separata (`it.sottovoce.app.debug`) e non viene aggiornata dalle release.

## Importazione e formati

MP3, M4B/M4A/MP4 audio, AAC, OGG, OPUS, FLAC e WAV, nei limiti dei codec supportati dal dispositivo e da Media3. Nessun supporto DRM/AAX. La selezione multipla crea un solo libro; nella selezione di una cartella, ogni sottocartella di primo livello è proposta come libro separato. Le tracce vengono ordinate numericamente, con possibilità di riordinarle prima della conferma.

I capitoli M4B vengono letti sia dal formato Nero `chpl`, sia dalle tracce di capitoli QuickTime referenziate (`chap`) usate da molti file riconosciuti da ffprobe e Audiobookshelf. I titoli UTF-8 e UTF-16 sono supportati. Per libri composti da più file, ogni file è una traccia navigabile. Le copertine sono ricavate dai metadati incorporati; in loro assenza viene mostrata una copertina tipografica.

Se sposti o cancelli un originale collegato, ricollegalo dalla scheda del libro. Usa la stessa registrazione, lo stesso numero di file e lo stesso ordine: posizioni e segnalibri dipendono da essi. La rimozione di un libro non cancella mai gli originali scelti dal dispositivo.

## Backup e privacy

Il backup include i dati di ascolto e le preferenze, **non gli audio, le copertine o le statistiche di ascolto**. Il ripristino sostituisce la libreria corrente dopo conferma e azzera le statistiche. Prima della sostituzione viene mantenuta una copia locale di sicurezza dei dati, e gli audio non vengono cancellati. Per sicurezza il backup non può imporre percorsi di file: dopo il ripristino devi ricollegare gli audio.

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
