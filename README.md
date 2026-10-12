# Ordnungshelfer – Dateien auf Android sortieren

Eine schlanke Android-App (Kotlin, Jetpack Compose), mit der du Dateien in selbst gewählten Ordnern aufräumst.

## Funktionen
- **Ordner selbst wählen** – die App arbeitet nur in den Ordnern, die du hinzufügst.
- **Download-Ordner & Co.** – mit der optionalen Freigabe „Zugriff auf alle Dateien“ lassen sich Download, Documents, Pictures, Movies und Music per Tipp hinzufügen.
- **Nach Dateityp** – Bilder, Videos, Audio, PDFs, Dokumente, Tabellen, Präsentationen, Archive, Apps, E-Books, Sonstiges.
- **Nach Datum** – optional Unterordner Jahr/Monat, z. B. `Bilder/2026/10`. Bei Fotos und Videos zählt das Aufnahmedatum, sonst ein Datum im Dateinamen (z. B. `IMG_20261003_…`), zuletzt das Änderungsdatum.
- **Eigene Regeln** – „Name enthält …“ oder „Endung ist …“ → Zielordner (auch verschachtelt, z. B. `Finanzen/Rechnungen`). Die erste passende Regel gewinnt, Reihenfolge per Pfeilen.
- **Vorschau vor dem Verschieben** – du siehst jede Datei mit Ziel und Grund, kannst einzelne abwählen und bestätigst erst dann.
- **Rückgängig** – die letzte Sortierung lässt sich vollständig zurückdrehen.
- **Duplikate finden** – inhaltlicher Vergleich (SHA-256), auch über Unterordner hinweg; die älteste Datei bleibt vorausgewählt erhalten.
- **Papierkorb** – aussortierte Duplikate landen im versteckten Ordner `.Papierkorb` und lassen sich 30 Tage lang wiederherstellen. Danach werden sie automatisch gelöscht.
- Es wird **nie etwas überschrieben**: Bei Namensgleichheit wird `Datei (2).pdf` verwendet.

## Installieren ohne PC (GitHub)
Das Projekt enthält einen Bauauftrag (`.github/workflows/build.yml`). Sobald der Code in einem GitHub-Repository liegt, baut GitHub die App automatisch. Die fertige `Ordnungshelfer.apk` steht danach im Repository unter **Releases** zum Download bereit.

## Installieren mit PC (Android Studio)
1. [Android Studio](https://developer.android.com/studio) installieren.
2. Den Ordner `Ordnungshelfer` öffnen („Open“) und warten, bis Gradle fertig synchronisiert ist.
3. Am Handy die Entwickleroptionen und **USB-Debugging** aktivieren, Handy per USB verbinden.
4. Oben auf ▶ **Run** klicken – die App wird installiert und gestartet.

Alternativ eine APK bauen: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. Die Datei liegt dann unter `app/build/outputs/apk/debug/app-debug.apk` und kann aufs Handy kopiert und installiert werden.

Voraussetzung: Android 8.0 oder neuer.

## Hinweis
Ab Android 11 lässt die normale Ordnerauswahl den Ordner `Download` nicht zu. Dafür gibt es in der App die Schaltfläche „Zugriff erlauben“ (Freigabe „Zugriff auf alle Dateien“). Ohne diese Freigabe funktioniert die App weiterhin mit Ordnern wie `Documents` oder `Pictures`.
