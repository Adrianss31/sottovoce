# Architettura della prima versione

App nativa Kotlin, Jetpack Compose Material 3. Un’unica Activity conserva il ViewModel durante i cambiamenti di configurazione. Il servizio Media3 gestisce audio e sessione indipendentemente dallo schermo aperto.

## Dati locali

SQLiteOpenHelper, database `library.db`, schema 1, WAL e transazioni per importazione e ripristino. Le entità serializzate JSON consentono di aggiungere campi con valori predefiniti. Modifiche allo schema SQL richiederanno una migrazione esplicita: non esiste cancellazione automatica del database in caso di errore di versione.

Book contiene metadati, tracce ordinate, posizione, velocità, completamento e stato di collegamento. I segnalibri sono associati al libro con chiave esterna. UPDATE conserva i segnalibri; non viene usato REPLACE sui libri. Copie audio in `files/books/<id>/`; gli originali content:// sono letti tramite permessi espliciti del selettore di Android. Non si richiede MANAGE_EXTERNAL_STORAGE.

L’importazione di copie usa file intermedi, controllo dimensione e sincronizzazione prima di registrare il libro. Una cancellazione non deve eliminare copie già registrate nel database. In caso di arresto forzato durante la copia possono rimanere file incompleti privati: una gestione automatica degli orfani è un miglioramento futuro.

Il backup esportato elimina URI e percorsi, conservando metadati e dati di ascolto. Il ripristino valida formato, quantità, indici, identificatori e velocità, e richiede una nuova selezione esplicita degli audio. Prima di sostituire i dati conserva `files/before-restore.json` nello spazio privato. L’interfaccia di recupero di questa copia non è ancora disponibile. Le vecchie copie audio non sono eliminate dal ripristino; possono occupare spazio fino alla pulizia/disinstallazione. Non disinstallare prima di recuperare gli audio che non possiedi altrove.

## Riproduzione

ExoPlayer e MediaSessionService, audio focus con contenuto parlato, wake lock locale, notifica di sistema e gestione dello scollegamento delle cuffie. Il servizio salva periodicamente la posizione (circa ogni 3 secondi) e agli eventi principali; un arresto improvviso può far perdere gli ultimissimi secondi. Velocità conservata per libro.

Le richieste esterne non possono fornire URL arbitrari al servizio: gli elementi della sessione vengono risolti per identificatore nella libreria locale. Si accettano controller dell’app e controller considerati affidabili dal sistema. I comandi per fermare/salvare e impostare il timer sono riservati all’app stessa.

Il parser dei capitoli M4B legge box Nero chpl e tracce di testo QuickTime referenziate da `tref/chap`. Per queste ultime interpreta tempi, dimensioni, mappatura campioni/chunk, offset a 32 o 64 bit e titoli UTF-8/UTF-16, senza caricare l’audiolibro in memoria. Tabelle, campioni e conteggi hanno limiti espliciti; metadati sconosciuti o malformati tornano alla singola traccia. Riferimenti del formato: [Chapter lists di Apple](https://developer.apple.com/documentation/quicktime-file-format/chapter_lists), [Text sample data di Apple](https://developer.apple.com/documentation/quicktime-file-format/text_sample_data) e [demuxer MOV di FFmpeg](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mov.c). Non include codice o librerie FFmpeg.

Dalla versione 0.1.1, al primo avvio vengono riesaminati automaticamente gli M4B importati con il parser precedente. L’operazione aggiorna soltanto i capitoli e conserva posizione, velocità e segnalibri.

## Aggiornamenti

All’apertura l’app scarica e verifica soltanto il piccolo descrittore pubblico `update.json`; l’APK viene scaricato esclusivamente dopo il tocco dell’utente sul banner. Il descrittore contiene un payload Base64 e una firma RSA/SHA-256. La chiave pubblica è incorporata nell’app. Il payload indica versione, codice crescente, Android minimo, URL, dimensione, SHA-256 e note.

La rete ammette esclusivamente HTTPS e gli host GitHub previsti per repository e release assets. L’URL APK firmato deve appartenere alle release di questo repository. Prima dell’installazione si verificano dimensione, checksum, nome del pacchetto, versione e certificato di firma uguale all’app installata. Android svolge a sua volta la verifica crittografica del pacchetto. FileProvider espone soltanto la cartella privata di cache per gli aggiornamenti.

Le chiavi private non sono incluse in Git, nelle Actions o nell’APK. La firma del pacchetto è stabile tra le release: perderla impedisce di aggiornare installazioni esistenti mantenendo l’identità dell’app. Anche la chiave di firma del descrittore va custodita e copiata in un luogo sicuro. Il modello attuale non implementa la rotazione automatica delle chiavi.

Un solo tocco nell’app avvia download, verifica e apertura dell’installatore. Se manca l’autorizzazione per questa origine, al ritorno dalle impostazioni l’installatore viene aperto automaticamente. Android richiede comunque la conferma finale: un’app normale non può installare un APK in silenzio. Il meccanismo è pensato per distribuzione diretta, non per una pubblicazione su Google Play senza ulteriori adattamenti alle sue regole.
