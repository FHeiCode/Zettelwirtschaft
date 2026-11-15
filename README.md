# Tresenkasse

Kassen-App für Kneipen auf dem Android-Handy. Sie arbeitet mit einer Cloud-TSE (fiskaly) und unterstützt Deckel, Barzahlung, Überweisung per GiroCode und SEPA-Lastschrift für Stammgäste.

> **Hinweis:** Die App ist ein Eigenbau und nicht von Steuerberatern oder dem Finanzamt geprüft. Vor dem echten Einsatz bitte mit dem Steuerberater abstimmen, insbesondere Belegausgabe, Kassenmeldung und DSFinV-K-Export.

## Funktionen

- **Kasse:** Getränke antippen, Summe, dann **Bar** (mit Rückgeld), **Unbar** oder **Deckel**.
- **Deckel:** Für jeden Gast oder Tisch. Jede Bestellung wird als TSE-Bestellung signiert. Einzelne Posten lassen sich stornieren, bezahlt wird der ganze Deckel.
- **Überweisung:** Die App zeigt einen GiroCode (EPC-QR) mit Betrag und Belegnummer. Der Gast scannt ihn mit seiner Banking-App. Erst wenn du **„Zahlung eingegangen“** bestätigst, wird der Verkauf abgeschlossen. Ein Abbruch wird als Belegabbruch signiert.
- **Lastschrift:** Ein Stammgast erteilt einmal ein SEPA-Lastschriftmandat (Basislastschrift, wiederkehrend) und unterschreibt auf dem Display. Danach kann er per Lastschrift bezahlen, und der Beleg dient als Vorabankündigung. Unter „Abschluss“ erstellt die App eine SEPA-Datei (pain.008.001.08) für das Online-Banking. Dafür brauchst du eine Gläubiger-ID von der Bundesbank und die Freischaltung für Lastschrifteinzug bei deiner Bank.
- **Beleg:** Der Beleg wird auf dem Display angezeigt und enthält alle Pflichtangaben nach § 6 KassenSichV. Der Gast kann ihn als QR-Code mit der Handykamera übernehmen, oder du teilst ihn als Bild. Zusätzlich gibt es den Prüf-QR-Code der TSE für die Kassen-Nachschau.
- **Storno:** Ein abgeschlossener Beleg wird durch einen Storno-Beleg mit negativen Beträgen zurückgenommen.
- **Tagesabschluss (Z-Bon):** Fasst alle Belege seit dem letzten Abschluss zusammen und überträgt sie an die DSFinV-K-Schnittstelle von fiskaly.
- **DSFinV-K-Export:** Erstellt eine ZIP-Datei für Steuerberater oder Betriebsprüfung. Den TSE-Export (TAR) gibt es im fiskaly-Dashboard.
- **Ausfall der TSE:** Die Kasse läuft weiter, der Beleg bekommt den Vermerk „TSE nicht erreichbar“. Nachträglich wird nicht signiert.

## Einrichtung

1. Bei [fiskaly](https://www.fiskaly.com) ein Konto anlegen und einen API-Key mit Secret erzeugen. Zum Testen reicht die kostenlose TEST-Umgebung.
2. In der App unter **Einstellungen** eintragen:
   - Name und Anschrift des Betriebs
   - Konto (Kontoinhaber, IBAN, BIC optional)
   - Gläubiger-ID, falls Lastschrift genutzt wird
   - API-Key und Secret
3. **„TSE einrichten“** tippen. Die App legt die TSE an, setzt eine zufällige Admin-PIN, meldet die Kasse als Client an und registriert sie für den DSFinV-K-Export.
4. Die Kasse innerhalb eines Monats über **Mein ELSTER** beim Finanzamt melden (§ 146a Abs. 4 AO). Die Seriennummern dafür stehen in den Einstellungen.
5. Unter **Einstellungen → Produkte bearbeiten** die Getränkekarte pflegen: 19 % für Getränke, 7 % für Speisen.

Alle Daten bleiben auf dem Handy und gehen nicht in Cloud-Backups. Ausnahme sind die Daten, die für TSE und DSFinV-K an fiskaly übertragen werden müssen. Kassendaten musst du 10 Jahre aufbewahren. Dafür regelmäßig den DSFinV-K-Export sichern.

## Installation

Die aktuelle APK gibt es immer unter
`https://github.com/FHeiCode/Tresenkasse/releases/latest/download/tresenkasse.apk`

## Build und Release

- Jeder Push baut über GitHub Actions eine APK, zu finden unter *Actions → Lauf → Artifacts*. Dabei laufen auch die Unit-Tests.
- **Release:** Steht in `version.txt` auf `main` eine Version, zu der es noch kein Release gibt, legt der Build das Release `v<Version>` mit APK an. Titel und Text kommen aus `release-notes.md`.
- **Signatur:** Damit Updates sich über die installierte App installieren lassen, im Repo unter *Settings → Secrets and variables → Actions* diese Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Keystore als Base64 (`base64 -w0 release.jks`) |
| `SIGNING_STORE_PASSWORD` | Keystore-Passwort |
| `SIGNING_KEY_ALIAS` | Alias des Schlüssels |
| `SIGNING_KEY_PASSWORD` | Schlüssel-Passwort |

Einen neuen Keystore erzeugst du mit:
```bash
keytool -genkeypair -v -keystore release.jks -alias tresenkasse -keyalg RSA -keysize 4096 -validity 10000
```

Lokal bauen: `gradle testDebugUnitTest assembleDebug` (Gradle 9.8, JDK 17, Android SDK 35).

## Technik

Kotlin, Jetpack Compose, Material 3, Room, OkHttp, ZXing. minSdk 26.
`fiscal/` enthält die Schnittstellen und reine Logik (mit Tests): fiskaly SIGN DE v2, DSFinV-K, GiroCode, SEPA pain.008 und Belegtext.
