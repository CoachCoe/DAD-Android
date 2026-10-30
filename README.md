# DAD Android

Kotlin/Jetpack Compose donor and partner app using DAD-Main's Go API. Separate native
apps were selected on 18 October 2026. The session, transport, and deep-link foundation
is production-grade. The product screens (giving, impact, network and badges, events,
settings, partner portal) are built against service seams and are reachable today only
through DEBUG preview launch extras (`preview_giving`, `preview_events`, and so on);
wiring them to the generated client is the remaining integration work (phased; Phase 1 below is done).

**Wired against the API (Phase 1):** donor phone + SMS sign-in (`beginPhoneSignIn`/`finishPhoneSignIn`,
existing accounts only), claim-link sign-in, profile read, settings load/save (`savePreferences`),
refresh, logout and real account deletion. Authenticated calls go through `ProductClient.perform` ->
`AccountSession.authorized` (single-flight refresh, one bounded 401 retry, epoch-guarded); sign-in
installs credentials through `AccountSession.establish` so the session owns the await. Settings is
reachable from the donor shell; sign-out, deletion and a revoked session return to sign-in. Passkey,
OIDC, email signup/password and partner sign-in are not wired (partner shows an honest notice). A
release build with no `dadApiOrigin` shows a "not configured" notice and never serves synthetic data.

Account deletion calls `POST /donor/me/delete` first and wipes local state (Keystore credentials and
attribution) only on success; a 409 (active monthly gift or open checkout) is explained and nothing is
wiped. Disclose in the Play Data safety "account deletion" answers that personal data is deleted while
required gift/receipt records are retained.

## Build and test

Requires JDK 21, Node 24, Android SDK package `platforms;android-37.0`/build tools 36.0.0, and Android Studio
with AGP 9.4 support. Set `ANDROID_HOME` to your SDK directory or use an ignored
`local.properties` with `sdk.dir`. The checksum-pinned Gradle 9.8.2 wrapper downloads
its own Gradle distribution.

```sh
node scripts/check-foundation.mjs
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease --warning-mode all
```

Open this folder in Android Studio and run the app on Android 8/API 26 or newer.
By default a debug build uses a labeled synthetic account through the session API (`preview_signed_out` starts it signed out; code 123456 signs in). The `stub_backend` extra runs the REAL sign-in, session and settings code on a scripted in-process transport (debug source set only) for the instrumentation tests. Android
instrumentation tests cover the Compose journeys and actual Keystore storage:
`./gradlew :app:connectedDebugAndroidTest` with a running emulator. CI runs the same
checks on API 35, and lint treats warnings as errors. AGP 9.4 itself emits Gradle
configuration deprecation diagnostics; those upstream warnings are not suppressed.

## Contract, sessions, and transport

`foundation.lock.json` pins the mobile foundation release (currently 2.3.0, tag
`mobile-foundation-v2.3.0`) and its manifest
SHA-256. The vendored release is verified before code generation. The build invokes pinned
OpenAPI Generator CLI 7.20.0 and generates Retrofit coroutine interfaces and Gson models
for the operations allow-listed in `scripts/prepare-client.mjs`. No client is generated
from a moving branch.
Generated sources and copied tokens/link assets stay in ignored build directories.
The application packages link rules, not the API schemas.

The native-only claim response is selected from its documented NativeSession variant.
The generator incorrectly emits a String literal for logout's boolean constant;
`LogoutAcknowledgement` is an explicit type override, with a test rejecting false
acknowledgements. The canonical pinned schema is unchanged.

`AccountSession` serializes shared refresh and credential persistence, isolates refresh
failures, checks revocation, retries an unauthorized read once, and prevents old refreshes
from restoring a logged-out or replaced session. Secure persistence runs off the UI thread:
AES-GCM ciphertext is atomically written under `noBackupFilesDir`, using a non-exportable
Android Keystore key and an application-bound authentication tag. Both legacy and current
backup/transfer policies exclude credentials. Tokens/proofs have redacted descriptions.
Logout clears local storage first and uses the newest known access token for remote
revocation; offline remote failure is surfaced rather than reported as confirmed success.

OkHttp disables cookies, redirects, and automatic transport retries, uses connect/read/
call timeouts, and caps bodies at 1 MiB. Requests use `X-DAD-Client: native` and a bearer
access token only on the direct HTTPS API origin. Go owns financial/recognition rules.

For a real environment, pass `-PdadApiOrigin=https://API_HOST` and
`-PdadPublicOrigin=https://PUBLIC_HOST`. These are public configuration values, not secrets.
App-link hooks handle launch and foreground intents using the pinned origin/path policy;
profile access can wait for sign-in, and plain claim links can establish a session.
Custom Tabs preserve website behavior for referrals, email changes, and unimplemented
features. A claim with a web-only return destination stays on the web before consuming
its one-use proof. Notifications admit only approved relative paths.

Production app links require the signing certificate's SHA-256 in a matching
DAD-Main `assetlinks.json`, plus an HTTPS VIEW intent filter with the verified
host. The release build ships application ID `org.dad.foundation`; the debug
build uses `org.dad.foundation.debug` so both install side by side. Camera, push, and
Wallet ports are defined; provider configuration, permission timing, final screens, and
real-device acceptance follow the PRD. Phone sign-in is wired (see above); passkey and OIDC
sign-in operations are in the generated client (contract 2.3.0) but have no screens yet. No permission is requested on
startup, analytics are not enabled, and no staging/production service is contacted by
the synthetic preview.

## Release signing

The release build type enables R8 minification and resource shrinking and is
signed from an upload keystore. Credentials are read from a gitignored
`keystore.properties` at the repo root; `keystore.properties.example` shows the
four required values. Without that file the release build still assembles, but
produces an explicitly **unsigned** artifact rather than silently falling back to
the debug key — so a misconfigured release fails loudly instead of shipping a
debug-signed build.

Application ID is `org.dad.foundation` for release and `org.dad.foundation.debug`
for debug (so both install side by side). The ID can be changed freely up until
the first upload to Google Play; it becomes permanent only once published.

Generate an upload keystore once and keep both the `.jks` file and its passwords
backed up safely — losing them after publishing requires a Play key reset:

```sh
keytool -genkeypair -v -keystore upload-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias dad-upload
cp keystore.properties.example keystore.properties   # then fill in the values
./gradlew :app:bundleRelease                          # signed AAB for Play
```

Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each Play release.
Device-gated pre-submission items (verified App Links against `assetlinks.json`,
real-device FCM, Instruments/perf, store listing) remain a hands-on checklist.

## Dependency updates

Download a specific `mobile-foundation-vX.Y.Z` release from `CoachCoe/DAD-Main`, review
its source revision and changes, and update the vendored snapshot and lock together.
`node scripts/check-foundation.mjs` verifies the version, revision, manifest, and payload
hashes offline. Never edit the generated snapshot or fetch latest/dev during builds.

## Real backend acceptance

From the matching DAD-Main checkout, run `make test-native-clients` with
`TEST_DATABASE_URL` naming its migrated synthetic `donor_go` database. Override
`DAD_IOS_DIR` and `DAD_ANDROID_DIR` when the checkouts are elsewhere. This opt-in
suite exercises the actual generated client and session store against a loopback
HTTPS Go API: claim/profile, expired-access refresh and credential replacement,
revocation cleanup, and logout. Test-only transports trust the temporary server's
certificate; production TLS settings are unchanged. No provider credentials are used.
The test runner removes the private proof fixtures after either success or failure.
