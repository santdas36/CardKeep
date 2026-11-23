# CardKeep

A private, local-first wallet for your credit, debit and prepaid cards on Android.

CardKeep keeps your card details encrypted on your phone, reminds you before bills are due, and reads statements straight from your inbox, without accounts, analytics or a cloud.

> Status: early and moving fast. Built for Indian cards and banks first.

## Features

- **Card stack**: your top three cards stacked like a real wallet. Pull down to cycle them, scroll to fan them out, and tap "+ N more" for the rest.
- **Card designs**: logo-free art for popular Indian cards, picked automatically from the bank and the card's nickname, with the bank's colours as the fallback. Contributions welcome, see [Card art](#contributing-card-art).
- **Add a card quickly**: scan it with the camera, tap it on the phone over NFC, or type the number. The bank, network and type are filled in from an offline BIN table.
- **Encrypted vault**: the card number, expiry, CVV and name on card are encrypted with keys in the Android Keystore. Revealing, copying or autofilling them asks for your fingerprint or PIN.
- **Autofill**: fill card details at checkout in other apps. Every fill needs your fingerprint or PIN.
- **Due reminders**: for each credit card, choose how many days before the due date to be reminded. Reminders are rescheduled after a reboot or a time change.
- **Statement import**: optionally, connect Gmail with an app password. Statement PDFs are fetched and parsed on the phone to update dues, limits and transactions.
- **Activity**: transactions from imported statements, grouped by category.
- **Encrypted backup**: a password-protected backup file that you keep.
- **App lock and privacy**: the app locks with your phone's lock, screenshots are blocked, and Android's cloud backup is off.

## Privacy

- Everything is stored on the phone in an SQLCipher database. The database key lives in the Android Keystore.
- There's no account and no analytics. The app's only network use is reading statement emails from your own mailbox, and only if you connect it.
- Card art is drawn in code. No images of real cards are downloaded or included.

## Building

Requirements:
- JDK 17
- Android SDK with platform 36
- Gradle 8.14.3

The repo doesn't include a Gradle wrapper yet, so you need Gradle 8.14.3 installed yourself.

```sh
gradle :app:assembleDebug        # debug build, installs alongside release as app.cardkeep.debug
gradle :app:assembleRelease      # release build (signed only if keystore.properties exists)
gradle :app:testReleaseUnitTest  # parser tests
```

To sign release builds, add `keystore.properties` at the repo root. It's ignored by git.

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

**Releases**: the "Build and release APK" GitHub Action (run manually from the Actions tab) builds a signed APK and publishes it as a GitHub release. It needs the signing secrets listed at the top of [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Project layout

```
app/src/main/java/app/cardkeep/
  autofill/    Autofill service and unlock screen
  capture/     NFC (EMV) reader and camera card-text parsing
  data/        Room entities, DAOs, migrations, backup, ledger
  domain/      Billing cycles, banks (Issuers), BIN lookup, formatting
  ingest/      Gmail (IMAP) sync, PDF unlock, statement parsers
  reminders/   Alarms, notifications, workers
  security/    Keystore keys, vault, biometric prompt
  ui/          Compose screens; card face, card art, home stack
```

Stack: Kotlin, Jetpack Compose (Material 3), Room with SQLCipher, WorkManager, CameraX with ML Kit text recognition, PDFBox-Android and JavaMail.

## Contributing

Issues and pull requests are welcome. Some ground rules:

- **No personal data.** Never commit real card numbers, real statement text, PDFs or passwords. Tests use synthetic data only. `StatementProbe` reads real PDFs from outside the repo, through environment variables, for local debugging.
- **No new network access** beyond the user's own mailbox, and no analytics or tracking.
- **Keep it small and readable.** Follow the existing style: Kotlin official style, short comments that explain why, and the shared components in `ui/Components.kt`.
- **Database changes** need a Room migration in `data/AppDatabase.kt`. Commit the generated schema JSON from `app/schemas/`.
- **Statement parsers** live in `ingest/`. A new bank parser should come with a synthetic test in `ParserTest`.

### Contributing card art

Card art lives in [`app/src/main/java/app/cardkeep/ui/CardArt.kt`](app/src/main/java/app/cardkeep/ui/CardArt.kt) as one line per design:

```kotlin
CardArt(
    "hdfc_millennia",                 // unique id: bank_card
    HDFC,                             // bank name exactly as in domain/Issuers.kt
    "Millennia",                      // display name
    listOf(0xFF1A3A8C, 0xFF2D66D4),   // background gradient, top-left to bottom-right
    Motif.SHEEN,                      // the card's main motif
    listOf(0xFF9CC0FF),               // motif colours
    listOf("millennia"),              // nickname words that select this design
    // optional: light = true (dark text on light cards), x/y (motif position), horizontal = true
),
```

The available motifs are in the `Motif` enum: `SHEEN`, `GLOW`, `RAYS`, `CHEVRON`, `ARROW`, `PYRAMID`, `RIBBON`, `STREAK`, `ORB`, `GLOBE`, `DOTS`, `FACETS`, `PIXELS`, `SWIRL`, `BAND`, `LINES`, `BRUSHED`, `RIPPLES`, `RINGS`, `SPARKLE`, `MOSAIC`, `SPLIT`, `ARC`, `CROSS` and `WAVES`. A new motif is welcome if no existing one fits.

Guidelines:
- **Approximate, don't copy.** Match the card's main colours and pattern. Don't include logos, bank names, network marks, mascots or text. A light blur is added automatically.
- **Check contrast.** Set `light = true` when the card is light enough that white text would be hard to read.
- **Choose specific match words.** When nicknames overlap, the longer match wins: for example, "regalia gold" beats "regalia".
- **Say what you based it on.** In the PR, name the card and describe its look (colours, motif). Please don't attach copyrighted card images.

## Credits and licences

- BIN data adapted from [binlist-data](https://github.com/iannuttall/binlist-data) by Ian Nuttall and [bin-list-data](https://github.com/venelinkochev/bin-list-data) by Venelin Kochev, both CC BY 4.0 ([`third_party/binlist-data-CC-BY-4.0.txt`](third_party/binlist-data-CC-BY-4.0.txt)).
- Inter typeface, SIL Open Font License ([`third_party/Inter-OFL.txt`](third_party/Inter-OFL.txt)).
- Bank and card names are trademarks of their owners. CardKeep isn't affiliated with any bank or card network, and its card designs are original approximations.

## License

[GNU General Public License v3.0](LICENSE). You can use, change and share CardKeep, and anything you distribute that's built on it has to stay open source under the same licence. Contributions, including card art, are accepted under the same terms.
