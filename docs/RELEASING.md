# Pubblicare una release

GitHub Actions compila e verifica. La firma avviene localmente: **non caricare chiavi private o password nel repository o negli artifact**. Custodisci una copia sicura del keystore, della sua password e della chiave privata del descrittore, separatamente dal progetto.

1. Incrementa `versionCode` (sempre crescente) e `versionName` in `app/build.gradle.kts`. Aggiorna note e test, poi pubblica il commit su main.
2. Attendi che entrambi i job del workflow Android riescano. Scarica l’artifact `sottovoce-build-<commit>` di quel commit. Usa il file `app-release-unsigned.apk`.
3. Con JDK 17 e gli Android Build Tools disponibili localmente, esegui lo script seguente. Sostituisci i percorsi con quelli privati locali; la password viene letta da file, non inserita nella riga di comando.

```sh
python3 scripts/sign_release.py \
  --apk /percorso/app-release-unsigned.apk \
  --apksigner /percorso/build-tools/35.0.0/apksigner \
  --keystore /percorso/privato/sottovoce-release.p12 \
  --password-file /percorso/privato/keystore-password.txt \
  --update-key /percorso/privato/update-private.pem \
  --version 0.1.0 --code 1 \
  --notes-file /percorso/note-release.md \
  --output /percorso/release-0.1.0
```

Lo script controlla la corrispondenza della chiave pubblica, firma e verifica l’APK, calcola il checksum, firma il descrittore e verifica anche questa firma. I numeri passati allo script **devono corrispondere all’APK compilato**; controllali con `aapt dump badging` o APK Analyzer. Lo script non modifica il codice dell’APK.

4. Crea una release in bozza con tag `v0.1.0` sul commit effettivamente verificato. Allega `sottovoce-0.1.0.apk`, `update.json` e `SHA256SUMS`.
5. Dopo la verifica, pubblicala come release normale, non prerelease: il controllo integrato usa `releases/latest/download/update.json`, che non seleziona prerelease.
6. Verifica da una connessione pubblica manifest e APK, quindi prova sul telefono l’aggiornamento dalla versione precedente. Con la prima release non esiste ancora una versione pubblica precedente da cui eseguire questa prova.

I file `signing.properties`, keystore e PEM sono ignorati da Git. `signing.properties`, se presente, permette anche compilazioni firmate locali; non è necessario per le Actions. Non rigenerare le chiavi a ogni build e non utilizzare il certificato debug per le release.
