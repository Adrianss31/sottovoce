# Pubblicare una release

GitHub Actions compila, verifica e firma usando le chiavi originali custodite nei Secrets privati del repository. **Non caricare chiavi private o password nel codice, negli artifact o nei log**. Conserva anche la copia locale del keystore, della password e della chiave privata del descrittore.

## Rilascio dal cloud

Il workflow `Android` esegue compilazione, unit test, lint e prove su emulatore. Solo dopo il successo di entrambi i job, su `main` e fuori dalle pull request, il job `release` scarica l'APK senza firma della stessa esecuzione, lo firma e verifica certificato originale, firme APK v2/v3, firma del descrittore e checksum.

Per pubblicare un aggiornamento:

1. Incrementa `versionCode` e `versionName` in `app/build.gradle.kts` e scrivi `docs/CHANGELOG-<versione>.md`.
2. Pubblica le modifiche su `main`. Se la connessione della chat cloud nega la scrittura, i Secrets di firma non risolvono quel permesso: usa l'accesso GitHub autorizzato o pubblica il branch/PR dal flusso supportato dalla chat.
3. Attendi tutti e tre i job del workflow. Una nuova versione con codice maggiore dell'ultima release viene caricata in bozza sul commit verificato. Il workflow confronta i file caricati, pubblica come `Latest` e scarica nuovamente i file pubblici per verificarne i checksum.
4. Verifica sull'app il banner e l'installazione. Il successo delle Actions non dimostra l'installazione su un telefono fisico.

Se versione e codice sono già pubblici, il job collauda comunque la firma e conserva la release esistente. Le release e i tag esistenti non vengono sovrascritti. Per ripetere il processo puoi avviare manualmente `Android` su `main`.

Secrets configurati una sola volta:

| Nome | Contenuto |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore originale PKCS12 codificato Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Password originale del keystore |
| `ANDROID_KEY_ALIAS` | Alias originale `sottovoce` |
| `ANDROID_KEY_PASSWORD` | Password della chiave, uguale a quella del keystore originale |
| `UPDATE_PRIVATE_KEY_PEM` | Chiave privata originale del descrittore |

Le chiavi vengono ricostruite soltanto in una cartella temporanea privata del runner, rimossa al termine; non vengono conservate negli artifact. Non inserire questi valori nei messaggi della chat cloud. Il certificato APK atteso è `871fc3e7e7972bfa6e8643ae274b30126fb0dc3a9b6abfcbe8340203d5a37855`.

## Firma locale alternativa

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
  --version 0.6.3 --code 23 \
  --notes-file /percorso/note-release.md \
  --output /percorso/release-0.6.3
```

Lo script verifica con aapt identità, versione e Android minimo dell’APK, controlla la corrispondenza della chiave pubblica, firma e verifica l’APK, calcola il checksum, firma il descrittore e verifica anche questa firma. I numeri passati allo script **devono corrispondere all’APK compilato**; lo script li verifica con `aapt dump badging` (aapt deve trovarsi nella stessa cartella di apksigner). Lo script non modifica il codice dell’APK.

4. Crea una release in bozza con un tag corrispondente alla versione, per esempio `v0.6.3`, sul commit effettivamente verificato. Allega l’APK firmato, `update.json` e `SHA256SUMS`.
5. Dopo la verifica, pubblicala come release normale, non prerelease: il controllo integrato usa `releases/latest/download/update.json`, che non seleziona prerelease.
6. Verifica da una connessione pubblica manifest e APK, quindi prova sul telefono l’aggiornamento dalla versione precedente. Con la prima release non esiste ancora una versione pubblica precedente da cui eseguire questa prova.

I file `signing.properties`, keystore e PEM sono ignorati da Git. `signing.properties`, se presente, permette anche compilazioni firmate locali; non è necessario per le Actions. Non rigenerare le chiavi a ogni build e non utilizzare il certificato debug per le release.
