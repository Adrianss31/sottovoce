# Sottovoce 0.7.2

Nuovi widget per la schermata Home: ascolta senza aprire l’app. Tre formati, tutti sincronizzati con il libro in ascolto.

- **5×2**: copertina, capitolo in corso con il carattere del tema, “In ascolto · 1,25×”, salti indietro e avanti, play, forma d’onda del capitolo, tempo trascorso e mancante, ore rimaste nel libro.
- **2×3**: mini player verticale con copertina (e velocità), capitolo, minuti rimasti, forma d’onda e comandi indietro / play / avanti.
- **2×2**: la copertina è il widget; l’anello attorno al tasto play mostra l’avanzamento del capitolo.
- I widget seguono il tema dell’app (anche il cambio giorno/notte) e i caratteri dei temi; il 2×2 usa i colori della copertina.
- I salti usano le durate scelte in Impostazioni → Salti del lettore e passano al capitolo vicino quando serve.
- Aggiornamenti più leggeri: i widget si ridisegnano fuori dal thread principale e, durante l’ascolto, inviano solo le parti che cambiano.
- Il widget già presente sulla Home diventa automaticamente il nuovo 5×2. Il comando del timer non è più nel widget: resta nella notifica e nel lettore.
