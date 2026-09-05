# Architettura della prima versione

App nativa Kotlin, Jetpack Compose Material 3. Un’unica Activity conserva il ViewModel durante i cambiamenti di configurazione. Il servizio Media3 gestisce audio e sessione indipendentemente dallo schermo aperto.

L’interfaccia non separa la scheda del libro dal lettore: la stessa destinazione mostra metadati, comandi di ascolto, progresso, capitoli e segnalibri. L’avvio della riproduzione aggiorna i controlli sul posto e non cambia schermata.

La navigazione conserva il rapporto gerarchico tra libreria, serie, libro e destinazioni secondarie. Dalla 0.5.0 le pagine non usano slide, fade o zoom generici: `SharedTransitionLayout` coordina il movimento degli elementi di origine. La copertina usa `sharedElement` tra scaffale/pannello di ascolto e intestazione fissa del libro; i comandi di ascolto si dispiegano sotto l’intestazione dopo l’avvio del movimento e si richiudono al ritorno. L’icona delle impostazioni si collega alla stessa icona nell’intestazione fissa della pagina. Le superfici di serie, statistiche e riordino mantengono la trasformazione contestuale del contenitore. Lo stato di scorrimento della libreria e delle serie è conservato durante la visita a un libro.

La home ha un solo pannello di ascolto. Mentre `NowPlaying.playing` è vero è collocato sopra la griglia: lo scorrimento riduce copertina e dettagli mantenendo i comandi. In cima torna esteso; in pausa o senza riproduzione è un normale elemento della griglia. Non esiste più il mini-player inferiore. Le copertine usano `FillBounds` per riempire il rapporto uniforme 2:3 senza bande aggiunte. L’intestazione del libro si riduce a copertina piccola e titolo quando si scorre, lasciando spazio ai capitoli senza perdere l’ancora del ritorno. I capitoli sono righe lazy di coppie consecutive, con una cella finale singola se dispari.

I feedback locali restano legati alla loro funzione: pressione dei comandi, direzione dei salti, cursore e avanzamento, indicatore dei filtri, apertura delle opzioni e aggiornamento delle icone di stato. `SottovoceMotion.kt` raccoglie il movimento condiviso e la politica per le animazioni disabilitate.

Il comando “Segna come non iniziato” attende lo stop e tutti i salvataggi di posizione del servizio, poi azzera traccia, posizione, ultimo ascolto e completamento. Cancella la pausa memorizzata per la ripresa intelligente di quel libro. Conserva audio, metadati, velocità, segnalibri e statistiche di tempo reale già ascoltato. Nessuna migrazione del database.

## Dati locali

SQLiteOpenHelper, database `library.db`, schema 2, WAL e transazioni per importazione e ripristino. Lo schema 2 aggiunge la tabella `sessions(book_id, day, duration_ms)` per le statistiche di ascolto locali, creata da una migrazione esplicita da schema 1 (i dati esistenti restano intatti). Le entità serializzate JSON consentono di aggiungere campi con valori predefiniti. Modifiche allo schema SQL richiederanno una migrazione esplicita: non esiste cancellazione automatica del database in caso di errore di versione.

Book contiene metadati, serie e posizione nella serie, tracce ordinate, posizione, velocità, completamento e stato di collegamento. I segnalibri sono associati al libro con chiave esterna. UPDATE conserva i segnalibri; non viene usato REPLACE sui libri. Copie audio in `files/books/<id>/`; gli originali content:// sono letti tramite permessi espliciti del selettore di Android. Non si richiede MANAGE_EXTERNAL_STORAGE.

L’importazione di copie usa file intermedi, controllo dimensione e sincronizzazione prima di registrare il libro. Una cancellazione non deve eliminare copie già registrate nel database. In caso di arresto forzato durante la copia possono rimanere file incompleti privati: le impostazioni consentono di eliminare i file incompleti. Le copie complete sono conservate per il recupero.

Il backup esportato elimina URI e percorsi, conservando metadati e dati di ascolto. Il ripristino valida formato, quantità, indici, identificatori e velocità; recupera le copie private corrispondenti e richiede la selezione esplicita degli audio mancanti. Prima di sostituire i dati conserva `files/before-restore.json` nello spazio privato. La copia è recuperabile dalle impostazioni. Le vecchie copie audio non sono eliminate dal ripristino; possono occupare spazio fino alla pulizia/disinstallazione. Non disinstallare prima di recuperare gli audio che non possiedi altrove.

## Riproduzione

ExoPlayer e MediaSessionService, audio focus con contenuto parlato, wake lock locale, notifica di sistema e gestione dello scollegamento delle cuffie. Nei file normali ogni capitolo viene esposto alla sessione come MediaItem ritagliato sulla sorgente originale: Android riceve titolo, durata e posizione relativi al capitolo, mentre l’app converte sempre salvataggi e ricerche nella posizione assoluta della traccia. Un file capitolato di almeno 1 GiB usa invece un solo MediaItem non ritagliato per traccia e salti assoluti tra i capitoli: questo evita di duplicare in memoria le grandi tabelle MP4 quando si cambia capitolo. In questo caso l’app e il widget continuano a calcolare capitolo e avanzamento corretti; la scheda multimediale di Android mostra il progresso della traccia completa. I controlli multimediali preferiti sono play/pausa, indietro 10 secondi e un comando personalizzato che avvia il timer o lo prolunga di 10 minuti. Il servizio salva periodicamente la posizione (circa ogni 3 secondi) e agli eventi principali; un arresto improvviso può far perdere gli ultimissimi secondi. Velocità conservata per libro.

La ripresa intelligente conserva soltanto libro e istante dell’ultima pausa nelle preferenze e applica un ritorno graduato tra 0 e 30 secondi, senza uscire dal capitolo corrente. Il timer supporta scadenza temporale o fine capitolo, dissolvenza nell’ultimo minuto e prolungamento tramite notifica o accelerometro. Il timer notturno usa ora locale, orario e durata scelti dall’utente e registra la sessione della notte per non riattivarsi dopo ogni pausa.

Widget e riquadro dei comandi rapidi si collegano alla stessa MediaSession e non ricevono URI dall’esterno. Il widget mostra il capitolo corrente, il suo avanzamento e i comandi essenziali; il riquadro esegue play/pausa. Se la sessione non contiene elementi, viene scelto esclusivamente un libro valido dalla libreria locale.

Le richieste esterne non possono fornire URL arbitrari al servizio: gli elementi della sessione vengono risolti per identificatore nella libreria locale. Si accettano controller dell’app e controller considerati affidabili dal sistema. I comandi per fermare/salvare e impostare il timer sono riservati all’app stessa.

Il parser dei capitoli M4B legge box Nero chpl e tracce di testo QuickTime referenziate da `tref/chap`. Per queste ultime interpreta tempi, dimensioni, mappatura campioni/chunk, offset a 32 o 64 bit e titoli UTF-8/UTF-16, senza caricare l’audiolibro in memoria. Tabelle, campioni e conteggi hanno limiti espliciti; metadati sconosciuti o malformati tornano alla singola traccia. Riferimenti del formato: [Chapter lists di Apple](https://developer.apple.com/documentation/quicktime-file-format/chapter_lists), [Text sample data di Apple](https://developer.apple.com/documentation/quicktime-file-format/text_sample_data) e [demuxer MOV di FFmpeg](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mov.c). Non include codice o librerie FFmpeg.

Dalla versione 0.1.1, al primo avvio vengono riesaminati automaticamente gli M4B importati con il parser precedente. L’operazione aggiorna soltanto i capitoli e conserva posizione, velocità e segnalibri.

## Statistiche e serie

Il tempo di ascolto è misurato come tempo reale trascorso in riproduzione: il servizio accumula l’intervallo dei suoi tick (250 ms) mentre l’audio è in riproduzione e lo registra a ogni salvataggio in `sessions`, una riga per libro e giorno (`duration_ms` cresce con UPDATE; la prima riga del giorno nasce con INSERT). I salti avanti e indietro non contano come ascolto, la velocità di riproduzione non altera il tempo registrato. Il backup versione 2 esporta e ripristina queste righe; un backup versione 1 conserva le righe locali dei libri corrispondenti. La rimozione di un libro le elimina in cascata. La schermata statistiche aggrega le righe per mese (ultimi 6 mesi), totale, mese corrente, completamenti e titoli più ascoltati: calcolo puro, nessun dato lascia il dispositivo.

Nella pagina principale i libri che appartengono a una serie vengono raggruppati in una card unica (copertina del primo titolo, conteggio e progresso della serie); un tocco apre la vista della serie con i suoi libri ordinati per numero. La ricerca testuale mostra di nuovo i singoli libri per trovarli facilmente. Il raggruppamento è una funzione pura (`groupForLibrary`) e non modifica i dati.

## Aggiornamenti

All’apertura l’app scarica e verifica soltanto il piccolo descrittore pubblico `update.json`; l’APK viene scaricato esclusivamente dopo il tocco dell’utente sul banner. Il descrittore contiene un payload Base64 e una firma RSA/SHA-256. La chiave pubblica è incorporata nell’app. Il payload indica versione, codice crescente, Android minimo, URL, dimensione, SHA-256 e note.

La rete ammette esclusivamente HTTPS e gli host GitHub previsti per repository e release assets. L’URL APK firmato deve appartenere alle release di questo repository. Prima dell’installazione si verificano dimensione, checksum, nome del pacchetto, versione e certificato di firma uguale all’app installata. Android svolge a sua volta la verifica crittografica del pacchetto. FileProvider espone soltanto la cartella privata di cache per gli aggiornamenti.

Le chiavi private non sono incluse in Git, nelle Actions o nell’APK. La firma del pacchetto è stabile tra le release: perderla impedisce di aggiornare installazioni esistenti mantenendo l’identità dell’app. Anche la chiave di firma del descrittore va custodita e copiata in un luogo sicuro. Il modello attuale non implementa la rotazione automatica delle chiavi.

Un solo tocco nell’app avvia download, verifica e apertura dell’installatore. Se manca l’autorizzazione per questa origine, al ritorno dalle impostazioni l’installatore viene aperto automaticamente. Android richiede comunque la conferma finale: un’app normale non può installare un APK in silenzio. Il meccanismo è pensato per distribuzione diretta, non per una pubblicazione su Google Play senza ulteriori adattamenti alle sue regole.

## Revisione 0.6.0

Il backup versione 2 include `sessions`. La versione 1 resta leggibile e conserva le statistiche locali dei libri corrispondenti. L’esportazione valida il proprio risultato e il limite di 16 MiB prima di scrivere. Il ripristino riconosce esclusivamente copie private già presenti, mai percorsi autorizzati dal documento esterno. Le impostazioni espongono la copia precedente e la pulizia dei file incompleti. Le copie complete non vengono cancellate automaticamente.

I capitoli vengono normalizzati: inizio a zero, rimozione di marcatori duplicati e fuori durata. Lo stato della riga non ricostruisce la timeline; il dettaglio conserva la timeline per lista di tracce. I salti usano la posizione assoluta nel libro. La ripresa intelligente rispetta il limite del capitolo anche su sorgente unica. Le ricerche manuali annullano il timer di fine capitolo. Il conteggio dell’ascolto mantiene il riferimento temporale fra salvataggi e attribuisce gli intervalli ai rispettivi giorni.

Le destinazioni animate includono gli identificatori del libro e della serie, conservando la sorgente durante il ritorno. Lo stack e i piccoli stati UI sopravvivono alla rotazione. La ricerca mostra titoli singoli e la chiusura cancella il filtro testuale. Il dettaglio offre ricerca capitoli, filtro sul corrente, menu gestione fisso, timer in attesa di avvio e annullamento del reset. Le statistiche della home occupano una riga.
