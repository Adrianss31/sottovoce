# Architettura della prima versione

App nativa Kotlin, Jetpack Compose Material 3. Un’unica Activity conserva il ViewModel durante i cambiamenti di configurazione. Il servizio Media3 gestisce audio e sessione indipendentemente dallo schermo aperto.

L’interfaccia non separa la scheda del libro dal lettore: la stessa destinazione mostra metadati, comandi di ascolto, progresso, capitoli e segnalibri. L’avvio della riproduzione aggiorna i controlli sul posto e non cambia schermata.

La navigazione conserva il rapporto gerarchico tra libreria, serie, libro e destinazioni secondarie. Le transizioni in avanti e indietro usano direzioni opposte, mentre l’apertura di un libro mantiene continuità con la copertina e il contenitore di partenza. Le frazioni di offset e le durate sono raccolte in `SottovoceMotionTokens`, così la transizione principale delle pagine e i piccoli elementi legati al movimento (icona “indietro” nella barra superiore, riordino delle tracce in importazione) restano allineati quando cambiano le curve o la velocità. Libreria e pagine delle serie conservano posizione di scorrimento e stato dei controlli mentre si visita un libro, così il ritorno ricompone la schermata nel punto di partenza. Il sistema di movimento separa la navigazione dai cambi di stato locali: play/pausa, salti, filtri, capitoli, segnalibri, timer, velocità, progressi e inserimenti nella libreria hanno feedback brevi e specifici, senza riavviare l’animazione dell’intera schermata.

## Dati locali

SQLiteOpenHelper, database `library.db`, schema 2, WAL e transazioni per importazione e ripristino. Lo schema 2 aggiunge la tabella `sessions(book_id, day, duration_ms)` per le statistiche di ascolto locali, creata da una migrazione esplicita da schema 1 (i dati esistenti restano intatti). Le entità serializzate JSON consentono di aggiungere campi con valori predefiniti. Modifiche allo schema SQL richiederanno una migrazione esplicita: non esiste cancellazione automatica del database in caso di errore di versione.

Book contiene metadati, serie e posizione nella serie, tracce ordinate, posizione, velocità, completamento e stato di collegamento. I segnalibri sono associati al libro con chiave esterna. UPDATE conserva i segnalibri; non viene usato REPLACE sui libri. Copie audio in `files/books/<id>/`; gli originali content:// sono letti tramite permessi espliciti del selettore di Android. Non si richiede MANAGE_EXTERNAL_STORAGE.

L’importazione di copie usa file intermedi, controllo dimensione e sincronizzazione prima di registrare il libro. Una cancellazione non deve eliminare copie già registrate nel database. In caso di arresto forzato durante la copia possono rimanere file incompleti privati: una gestione automatica degli orfani è un miglioramento futuro.

Il backup esportato elimina URI e percorsi, conservando metadati e dati di ascolto. Il ripristino valida formato, quantità, indici, identificatori e velocità, e richiede una nuova selezione esplicita degli audio. Prima di sostituire i dati conserva `files/before-restore.json` nello spazio privato. L’interfaccia di recupero di questa copia non è ancora disponibile. Le vecchie copie audio non sono eliminate dal ripristino; possono occupare spazio fino alla pulizia/disinstallazione. Non disinstallare prima di recuperare gli audio che non possiedi altrove.

## Riproduzione

ExoPlayer e MediaSessionService, audio focus con contenuto parlato, wake lock locale, notifica di sistema e gestione dello scollegamento delle cuffie. Nei file normali ogni capitolo viene esposto alla sessione come MediaItem ritagliato sulla sorgente originale: Android riceve titolo, durata e posizione relativi al capitolo, mentre l’app converte sempre salvataggi e ricerche nella posizione assoluta della traccia. Un file capitolato di almeno 1 GiB usa invece un solo MediaItem non ritagliato per traccia e salti assoluti tra i capitoli: questo evita di duplicare in memoria le grandi tabelle MP4 quando si cambia capitolo. In questo caso l’app e il widget continuano a calcolare capitolo e avanzamento corretti; la scheda multimediale di Android mostra il progresso della traccia completa. I controlli multimediali preferiti sono play/pausa, indietro 10 secondi e un comando personalizzato che avvia il timer o lo prolunga di 10 minuti. Il servizio salva periodicamente la posizione (circa ogni 3 secondi) e agli eventi principali; un arresto improvviso può far perdere gli ultimissimi secondi. Velocità conservata per libro.

La ripresa intelligente conserva soltanto libro e istante dell’ultima pausa nelle preferenze e applica un ritorno graduato tra 0 e 30 secondi, senza uscire dal capitolo corrente. Il timer supporta scadenza temporale o fine capitolo, dissolvenza nell’ultimo minuto e prolungamento tramite notifica o accelerometro. Il timer notturno usa ora locale, orario e durata scelti dall’utente e registra la sessione della notte per non riattivarsi dopo ogni pausa.

Widget e riquadro dei comandi rapidi si collegano alla stessa MediaSession e non ricevono URI dall’esterno. Il widget mostra il capitolo corrente, il suo avanzamento e i comandi essenziali; il riquadro esegue play/pausa. Se la sessione non contiene elementi, viene scelto esclusivamente un libro valido dalla libreria locale.

Le richieste esterne non possono fornire URL arbitrari al servizio: gli elementi della sessione vengono risolti per identificatore nella libreria locale. Si accettano controller dell’app e controller considerati affidabili dal sistema. I comandi per fermare/salvare e impostare il timer sono riservati all’app stessa.

Il parser dei capitoli M4B legge box Nero chpl e tracce di testo QuickTime referenziate da `tref/chap`. Per queste ultime interpreta tempi, dimensioni, mappatura campioni/chunk, offset a 32 o 64 bit e titoli UTF-8/UTF-16, senza caricare l’audiolibro in memoria. Tabelle, campioni e conteggi hanno limiti espliciti; metadati sconosciuti o malformati tornano alla singola traccia. Riferimenti del formato: [Chapter lists di Apple](https://developer.apple.com/documentation/quicktime-file-format/chapter_lists), [Text sample data di Apple](https://developer.apple.com/documentation/quicktime-file-format/text_sample_data) e [demuxer MOV di FFmpeg](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mov.c). Non include codice o librerie FFmpeg.

Dalla versione 0.1.1, al primo avvio vengono riesaminati automaticamente gli M4B importati con il parser precedente. L’operazione aggiorna soltanto i capitoli e conserva posizione, velocità e segnalibri.

## Statistiche e serie

Il tempo di ascolto è misurato come tempo reale trascorso in riproduzione: il servizio accumula l’intervallo dei suoi tick (250 ms) mentre l’audio è in riproduzione e lo registra a ogni salvataggio in `sessions`, una riga per libro e giorno (`duration_ms` cresce con UPDATE; la prima riga del giorno nasce con INSERT). I salti avanti e indietro non contano come ascolto, la velocità di riproduzione non altera il tempo registrato. Le righe sono eliminate dal ripristino del backup (le statistiche non sono esportate) e in cascata quando un libro viene rimosso. La schermata statistiche aggrega le righe per mese (ultimi 6 mesi), totale, mese corrente, completamenti e titoli più ascoltati: calcolo puro, nessun dato lascia il dispositivo.

Nella pagina principale i libri che appartengono a una serie vengono raggruppati in una card unica (copertina del primo titolo, conteggio e progresso della serie); un tocco apre la vista della serie con i suoi libri ordinati per numero. La ricerca testuale mostra di nuovo i singoli libri per trovarli facilmente. Il raggruppamento è una funzione pura (`groupForLibrary`) e non modifica i dati.

## Aggiornamenti

All’apertura l’app scarica e verifica soltanto il piccolo descrittore pubblico `update.json`; l’APK viene scaricato esclusivamente dopo il tocco dell’utente sul banner. Il descrittore contiene un payload Base64 e una firma RSA/SHA-256. La chiave pubblica è incorporata nell’app. Il payload indica versione, codice crescente, Android minimo, URL, dimensione, SHA-256 e note.

La rete ammette esclusivamente HTTPS e gli host GitHub previsti per repository e release assets. L’URL APK firmato deve appartenere alle release di questo repository. Prima dell’installazione si verificano dimensione, checksum, nome del pacchetto, versione e certificato di firma uguale all’app installata. Android svolge a sua volta la verifica crittografica del pacchetto. FileProvider espone soltanto la cartella privata di cache per gli aggiornamenti.

Le chiavi private non sono incluse in Git, nelle Actions o nell’APK. La firma del pacchetto è stabile tra le release: perderla impedisce di aggiornare installazioni esistenti mantenendo l’identità dell’app. Anche la chiave di firma del descrittore va custodita e copiata in un luogo sicuro. Il modello attuale non implementa la rotazione automatica delle chiavi.

Un solo tocco nell’app avvia download, verifica e apertura dell’installatore. Se manca l’autorizzazione per questa origine, al ritorno dalle impostazioni l’installatore viene aperto automaticamente. Android richiede comunque la conferma finale: un’app normale non può installare un APK in silenzio. Il meccanismo è pensato per distribuzione diretta, non per una pubblicazione su Google Play senza ulteriori adattamenti alle sue regole.
