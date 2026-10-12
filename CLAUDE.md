# Working agreement for Poké Trader

The owner (joelvdc) is new to GitHub: explain things in plain language, step by step when they have to do something.

## How changes are made
1. **Plan first.** When the owner asks for changes or improvements, draft a plan (what changes, why, which screens or
   files) and do **not** change code yet.
2. **Green light.** Once the owner approves the plan ("green light", "go ahead", "approved"), you have permission to
   carry it out all the way, without asking again:
   - make the changes on a branch and run the checks locally:
     `./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
     (after an intended visual change, `recordRoborazziDebug`, look at the new pictures, and commit them);
   - raise `versionCode` and `versionName` in `app/build.gradle.kts`, write `release-notes/<version>.md` (`## New` and other headings with bullet points)
     and update the README where it describes the changed feature;
   - open a pull request and **merge it yourself once the Tests check on GitHub has passed**. Never merge while a check
     is red or still running;
   - start the release: Actions → Release → Run workflow on `main` (the workflow tags `v<version>`, signs the APKs and
     publishes the release);
   - check the published `PokeTrader-<version>.apk` is signed with the same certificate as the previous release
     (`apksigner verify --print-certs`, compare the SHA-256 digests). If it isn't, tell the owner at once;
   - report what's new, with links to the pull request and the release.
3. **Still ask first**, even after a green light, before anything that:
   - deletes or rewrites the owner's data, or changes the database in a way older versions or other phones can't read;
   - touches signing, the keystore or the repository secrets;
   - goes beyond the approved plan.
4. When something fails (tests, build, release), fix it if it's within the plan; otherwise stop and explain what
   happened and what is needed.

## Two ways to release
- **Cloud sessions** (no keystore, can't sign): the pull request and Release workflow route described above.
- **Sessions on the owner's PC** (local keystore): faster by hand. Pull `main` first; commit straight to `main`; run
  `./gradlew testDebugUnitTest verifyRoborazziDebug assembleRelease`; check the APK's certificate against the
  previous release (`apksigner verify --print-certs`); then publish with `gh release create v<version>` and the two
  APKs, using `release-notes/<version>.md` as the notes. The Release workflow only starts by hand, so it won't build
  a second copy.
- The screenshot tests run in UTC with English formats and ignore invisible colour differences (see
  `app/build.gradle.kts` and `ScreenshotOptions.kt`), so the same reference pictures pass on GitHub's Linux machines
  and on the owner's Windows PC. Reference pictures may be re-recorded on either.
- Either way: plan first and wait for the green light, write `release-notes/<version>.md`, keep the README current,
  and never reuse a released version number.

## Releases
- Releases are published on GitHub (Obtainium installs them on the owner's phones), as `PokeTrader-<version>.apk`
  (64-bit ARM) and `PokeTrader-<version>-universal.apk`.
- The release workflow needs the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD`; never print, copy or change them.
- The owner can also build releases on their PC with the local keystore; never reuse a version number that was
  already released.
