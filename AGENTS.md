# Agent guide — DAD-Android

Kotlin/Jetpack Compose donor app built against a vendored snapshot of DAD-Main's API
contract. Start with these; this file only adds what they do not say.

- **[README.md](README.md)** — build/test, the contract/sessions/transport model, release
  signing, dependency updates, real-backend acceptance.
- Cross-repo system docs (topology, the mobile contract, re-vendor model) live in the
  separate **DAD-Docs** repo. Read the mobile contract before any contract change.

## Build and test

```sh
node scripts/check-foundation.mjs
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease --warning-mode all
```

CI runs the same on API 35 and **lint treats warnings as errors**.

## Branch and push discipline

- Work on `dev`; ship via `dev` → PR → `main`. Never push to `main` directly.
- Run `git commit` and `git push` as two separate commands.

## Gotchas not written in the docs

- **`git add -A` silently stages planning docs.** `docs/requirements/` (dev plan, gap
  analysis, PRD) is kept untracked but was historically **not** in `.gitignore`. It is now
  (PR #13), but still: stage specific files, and after any `git add -A` check `git status`
  for stray `docs/` staging before committing.
- **Vendored contract is hash-locked.** `foundation.lock.json` pins version + 40-char
  source revision + manifest sha256; `scripts/check-foundation.mjs` verifies every file.
  Re-vendoring requires an **actual tagged** DAD-Main mobile-foundation release — bump the
  triple, copy the files, widen the OpenAPI Generator allow-list for new ops, regenerate,
  run check-foundation + build. Never generate a client from a moving branch.
- **Re-vendor also touches the test pin:** `scripts/check-foundation.test.mjs` hardcodes the
  expected version (currently 2.9.0); bump it with `foundation.lock.json`.
- **Analytics payloads must match DAD-Main's ingest allow-list** (`internal/analytics`):
  string-to-string properties, extra keys are rejected. Pinned per event in `AttributionTest`.
- **Generator constraints:** `anyOf` + `type: null` and `additionalProperties: false`
  need handling (the `LogoutAcknowledgement` pattern); new nullable shapes may too.
- **Do not promote time-varying lint advisories to errors.** `NewerVersionAvailable` /
  `AndroidGradlePluginVersion` flag that an upstream release merely exists, not a code
  defect — so `warningsAsErrors` on them breaks CI on untouched code whenever any
  dependency ships. Disable those specific checks rather than chasing version bumps.
- **The synthetic preview session is DEBUG-gated** (`check(BuildConfig.DEBUG)` in the
  session factory) so a release build with an unconfigured origin never serves synthetic
  data as real. Keep that guard on any new session fallback.
- **Never track working documents.** Plans/findings/notes stay untracked (`docs/requirements/`).
