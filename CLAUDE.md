# Working agreement for Poké Trader

The owner (joelvdc) is new to GitHub: explain in plain language, step by step when they have to do something.

## To-do list
When the owner mentions an idea "for the list", add it to `TODO.md` (date, their wording, open questions) and change
no code. The list is built only after a green light for the list, planned all together.

## How changes are made
1. **Plan first**: what changes, why, which screens or files; no code changes yet. Describe visual changes in words.
   Mockup screenshots only when the owner asks or has a real design choice to make: draw them with a temporary
   Roborazzi test (real theme, fonts, sample cards; not committed), alternatives side by side. Otherwise check the
   look yourself (Roborazzi pictures, or the emulator on the PC). Every plan says whether the weekly health check
   (scheduled task "MTG & Poké Trader health and new-release check", which checks every outside service and data
   field both apps use) needs updating: a new or changed service, address or field.
2. **Green light** ("green light", "go ahead", "approved"): carry it out all the way without asking again:
   - make the changes; run `./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug` (after an intended visual
     change, `recordRoborazziDebug`, look at the pictures, commit them);
   - raise `versionCode` and `versionName` in `app/build.gradle.kts` (never reuse a released version), write
     `release-notes/<version>.md` (`## New`, `## Fixed` and other headings with bullet points), update the README
     where it describes the changed feature;
   - release by one of the two routes below, and check the published `PokeTrader-<version>.apk` has the same
     certificate as the previous release (`apksigner verify --print-certs`, compare SHA-256); if not, tell the owner
     at once;
   - if the health check needs it, update the scheduled task after the release (including the app version it
     mentions);
   - report what's new, with links to the release (and pull request), and what the health check gained.
3. **Still ask first**, even after a green light, before anything that:
   - deletes or rewrites the owner's data, or changes the database so older versions or other phones can't read it;
   - touches signing, the keystore or the repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
     `KEY_PASSWORD`: never print, copy or change them);
   - goes beyond the approved plan.
4. When something fails, fix it if it's within the plan; otherwise stop and explain what happened and what's needed.

## Releases
Published on GitHub (Obtainium installs them on the owner's phones) as `PokeTrader-<version>.apk` (64-bit ARM) and
`PokeTrader-<version>-universal.apk`.
- **Cloud sessions** (can't sign): work on a branch, open a pull request, merge it yourself once GitHub's Tests check
  has passed (never while red or running), then Actions → Release → Run workflow on `main` (tags `v<version>`, signs,
  publishes).
- **Sessions on the owner's PC** (local keystore): pull `main` first, commit straight to `main`, run
  `./gradlew testDebugUnitTest verifyRoborazziDebug assembleRelease`, check the certificate, then
  `gh release create v<version>` with both APKs and `release-notes/<version>.md` as notes. (The Release workflow only
  starts by hand.)
- Screenshot tests run in UTC with English formats and ignore invisible colour differences (`app/build.gradle.kts`,
  `ScreenshotOptions.kt`), so reference pictures pass, and may be re-recorded, on GitHub's Linux machines and the PC.

## Building on the owner's PC
- Use JDK 18 (`C:\Program Files\Java\jdk-18.0.2`); newer JDKs are too new for this Gradle.
- The build folder is `%LOCALAPPDATA%\poketrader-build` (derived from `LOCALAPPDATA` in the root `build.gradle.kts`).
  VS Code's Java extension can lock it; a separate copy of the code needs its own `LOCALAPPDATA`.
- Never edit source files with PowerShell `Get-Content | Set-Content`: it garbles é, —, €. Use an editor tool or
  Python with UTF-8.

## Delegated work
If you're working from a brief given by another session (a helper such as Sonnet or Mistral), follow the brief: work
only in your own copy of the code; don't commit, merge, release, change versions or touch signing. The session that
delegated does that after reviewing your work.

## After a release
The owner clears the conversation after every release. Before the final report, make sure everything worth keeping is
in the repository: work committed and merged, and `TODO.md` up to date (shipped, still open). The full health check
text lives in the MTG Trader repository (`docs/health-check-instructions.txt`, next door in
`..\MTG trading app android`); when the health check changed, update that file there too, and give the owner the full
text in the report to paste (assume they do, without telling you).

---
**For AI assistants:** Keep `AGENTS.md` identical to this file; when either changes, update the other and commit both
together.
