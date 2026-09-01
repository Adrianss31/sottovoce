# Verifiche

La versione 0.1.0 è stata compilata e verificata dal [workflow Android](https://github.com/Adrianss31/sottovoce/actions/runs/33439574862): 7 unit test, analisi Android e 5 prove su emulatore Android 15, senza errori. La versione 0.1.1 aggiunge una prova sintetica completa di una traccia capitoli QuickTime con titoli UTF-8 e UTF-16; i risultati della sua build restano visibili nella pagina Actions.

La suite comprende ordinamento numerico delle tracce, calcolo dei progressi, validazione del backup, parsing dei capitoli Nero e QuickTime, firma del descrittore e rifiuto di aggiornamenti manomessi. Le prove su emulatore comprendono libreria inizialmente vuota, riproduzione di un WAV sintetico, salvataggio di posizione/velocità/segnalibro, timer di fine traccia, importazione di una copia senza toccare l’originale e ripristino con ricollegamento obbligatorio.

Non equivale a una certificazione su ogni dispositivo: vanno ancora provati Android 8–14/16, codec reali differenti, cuffie Bluetooth, telefonate, risparmio energetico dei produttori e un aggiornamento completo tra due release pubbliche.
