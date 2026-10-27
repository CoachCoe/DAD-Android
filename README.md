# DAD Android

Kotlin/Jetpack Compose donor-app foundation using DAD-Main's Go API. Separate native
apps were selected on 18 October 2026. Product features and navigation still require
the mobile PRD.

## Build and test

Requires JDK 21, Node 24, Android SDK package `platforms;android-37.0`/build tools 36.0.0, and Android Studio
with AGP 9.4 support. Set `ANDROID_HOME` to your SDK directory or use an ignored
`local.properties` with `sdk.dir`. The checksum-pinned Gradle 9.8.2 wrapper downloads
its own Gradle distribution.

```sh
node scripts/check-foundation.mjs
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --warning-mode all
```

Open this folder in Android Studio and run the app on Android 8/API 26 or newer.
By default it uses a labeled synthetic account through the session API. Android
instrumentation tests cover Compose loading/retry and actual Keystore storage:
`./gradlew :app:connectedDebugAndroidTest` with a running emulator. CI runs the same
checks on API 35, and lint treats warnings as errors. AGP 9.4 itself emits Gradle
configuration deprecation diagnostics; those upstream warnings are not suppressed.

## Contract, sessions, and transport

`foundation.lock.json` pins mobile foundation 2.0.0 and its manifest SHA-256.
The vendored release is verified before code generation. The build invokes pinned
OpenAPI Generator CLI 7.20.0 and generates Retrofit coroutine interfaces and Gson models
for profile, claim, refresh, and logout. No client is generated from a moving branch.
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

Production app links require the final application ID, signing certificate, HTTPS VIEW
intent filter with verified host, and matching DAD-Main assetlinks.json. The preview ID
`org.dad.foundation.preview` is not a registered production identity. Camera, push, and
Wallet ports are defined; provider configuration, permission timing, sign-in UI/passkeys,
final screens, and real-device acceptance follow the PRD. No permission is requested on
startup, analytics are not enabled, and no staging/production service is contacted by
the synthetic preview.

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
