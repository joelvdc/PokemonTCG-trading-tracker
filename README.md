# Poké Trader (Android)

A kid-friendly app for trading Pokémon cards: scan or search the cards on each side, see at a glance whether the
trade is fair (Cardmarket prices, €), and keep "My cards" (the collection) up to date when the trade is done.

## Install
Copy `PokeTrader-1.2.apk` to the phone and open it (allow "install unknown apps" for your file manager/browser when
asked). It's built for 64-bit ARM phones (practically every phone from the last ~6 years). If it refuses to install, use
`PokeTrader-1.2-universal.apk` instead (bigger, runs on any device).

## Where the data comes from (no app updates needed for new sets)
- **Cards, pictures, names in all languages:** [TCGdex](https://tcgdex.net) (free, open API), looked up live.
  International prints use TCGdex's English data (French/German/… prints share numbers and Cardmarket products);
  Japanese prints are separate sets with their own ids and Cardmarket products.
- **Prices:** Cardmarket's public daily Pokémon price guide (`price_guide_6.json`, ~15 MB), downloaded once a day.
  Each card *variant* (normal, reverse holo, Poké Ball / Master Ball pattern, stamped…) has its own Cardmarket
  product id in TCGdex; reverse holos use the guide's "holo" price columns.
- **Pokémon TCG Pocket** (the phone game) cards are in TCGdex too; they're filtered out everywhere since they don't
  exist on paper.

## Known limits
- 1st Edition and Shadowless vintage cards share one Cardmarket product, so 1st Edition can be under-priced — the app
  warns and offers an "agreed price".
- Card pictures come from TCGdex. For international cards TCGdex has no picture for (some promos and special
  collections), the app falls back to pokemontcg.io's image server, matching sets via pokemontcg.io's set list on
  GitHub. There is no open source for missing Japanese pictures (about 90% of Japanese cards in TCGdex have none);
  those show name + number + "no picture". Search results can be filtered by card number ("86", "86/110", "TG05").
- Oversized (jumbo) cards are a version of the normal card ("Jumbo · Holo"), with their own Cardmarket price. The
  camera can't tell size, so a scanned card is added as standard size with a "Big card? Tap it" hint when a jumbo
  version exists. Jumbo cards TCGdex doesn't list can't be added.
- The scanner identifies cards mainly by the printed number ("025/165") plus the name. Holo vs reverse holo can't be
  seen by the camera: use the "✨ Shiny" toggle or tap a scanned card to change its version.

## Rebuilding
Same toolchain as the MTG Trader app next door: JDK 17+ and the Android SDK at `%USERPROFILE%\Android\sdk`.
```
set JAVA_HOME=C:\Program Files\Java\jdk-18.0.2
gradlew assembleRelease
```
APKs land in `%LOCALAPPDATA%\poketrader-build\app\outputs\apk\release\` (kept out of OneDrive on purpose).
Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each new release.

**Keep `keystore/` and `keystore.properties` safe and private** (they're git-ignored). Android only installs an update
over the existing app if it's signed with the same key; losing it means uninstalling (and losing the app's data).

Tests: `gradlew testDebugUnitTest` (logic, parser, variants) and `gradlew connectedDebugAndroidTest` (real OCR on six
card images in `app/src/androidTest/assets`, needs a device/emulator with internet).

## Code map
- `data/Tcgdex.kt` – TCGdex client, variant naming, card → printings.
- `data/SetCatalog.kt` – cached set lists (for "/165 → which set?"), Pocket filter.
- `data/PriceGuideRepository.kt` – Cardmarket price guide download/import.
- `data/Repository.kt` – trades, collection, apply/undo, CSV.
- `scan/CardTextParser.kt` – reads name / number / set code / language from OCR lines.
- `scan/CardRecognizer.kt` – turns those clues into a card (or a "which one is it?" choice).
- `ui/` – Compose screens.
