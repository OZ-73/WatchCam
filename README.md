# WatchCam

Comanda foto e video della fotocamera del telefono dai pulsanti multimediali dell'orologio.

## Compilare
1. Android Studio (Ladybug o successivo) -> File > Open -> cartella WatchCam
2. Lascia fare il Gradle Sync (genera da solo il wrapper; accetta l'eventuale aggiornamento di AGP)
3. Collega il Pixel con debug USB attivo -> Run
4. Concedi permessi Fotocamera e Microfono

## Uso
- Apri l'app e tienila aperta (schermo acceso; usa "Scuro" per abbassare la luminosità)
- Orologio, controlli musica: Play/Pausa = foto, Avanti = avvia/ferma video, Indietro = cambia camera
- File salvati in Immagini/WatchCam e Film/WatchCam

## Senza Android Studio
Carica la cartella su un repository GitHub: la Action "Build APK" compila da sola.
Scarica l'APK da Actions > ultimo run > Artifacts > WatchCam-apk e installalo sul telefono.
