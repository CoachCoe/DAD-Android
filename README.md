# DAD Android

Kotlin/Jetpack Compose donor and partner app using DAD-Main's Go API. Separate native
apps were selected on 18 October 2026. Every donor and partner screen runs against the generated client; the
DEBUG preview launch extras (`preview_giving`, `preview_events`, `preview_partner`, and so on) render the same
screens over synthetic services for design review and are compiled out of release builds.

**Wired against the API (Phase 1):** donor phone + SMS sign-in (`beginPhoneSignIn`/`finishPhoneSignIn`,
existing accounts only), claim-link sign-in, profile read, settings load/save (`savePreferences`),
refresh, logout and real account deletion. Authenticated calls go through `ProductClient.perform` ->
`AccountSession.authorized` (single-flight refresh, one bounded 401 retry, epoch-guarded); sign-in
installs credentials through `AccountSession.establish` so the session owns the await. Settings is
reachable from the donor shell; sign-out, deletion and a revoked session return to sign-in. A
release build with no `dadApiOrigin` shows a "not configured" notice and never serves synthetic data.

**Wired against the API (Phase 2, donor home):** the signed-in donor landing now shows the real dashboard
(`GET /donor/me/dashboard`: streak card with the 30-day ladder and at-risk grace notice, national/city rank,
total given, network and referral totals; `preparing` shows "Getting your dashboard ready"), today's initiative
(`todayInitiative`; a 404 means nothing is in rotation, not an error; the card carries the A-5 note that this is
display attribution, not designated giving) and a gift history screen (`gifts` 50 per page, capped at 500 rows in
memory, a non-advancing offset ends paging, paging cancels when the screen closes) with an inline gift record
(`receipt`) and the calendar-year total (`annualSummary`). The rank line follows the explicit
`rankings_opted_out` flag (opted out shows no rank line; opted in but unranked says "Not ranked yet"), and
`national_percentile` is shown as the top bucket it is (2 = "Top 2%"). Unknown figures (a null streak, network,
referrals or total given) are shown as unknown, never as zero. A failed refresh keeps the cards on screen with a
notice; a 401 refreshes once and then signs out; an undocumented 429 is "too many requests", any other
undocumented status is "unavailable". These five GETs are decoded from raw JSON in `WireDashboard` with strict
null handling, because the generated Kotlin models flatten the dashboard's `oneOf` and turn nullable members
into non-null types that Gson would silently fill with zeros.

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
By default a debug build uses a labeled synthetic account through the session API (`preview_signed_out` starts it signed out; code 123456 signs in). The `stub_backend` extra runs the REAL sign-in, session, settings and donor-home code on a scripted in-process transport (debug source set only) for the instrumentation tests; `stub_signed_in` starts with a stored session, and `stub_dashboard_empty` / `stub_dashboard_fail_first` / `stub_dashboard_unranked` pick the donor-home scenario. Android
instrumentation tests cover the Compose journeys and actual Keystore storage:
`./gradlew :app:connectedDebugAndroidTest` with a running emulator. CI runs the same
checks on API 35, and lint treats warnings as errors. Emulator journeys require the software
keyboard even when a hardware keyboard is connected: enable "Use on-screen keyboard" in the
emulator settings, or run `adb shell settings put secure show_ime_with_hard_keyboard 1`.
The tests wait for that keyboard and check that tapped controls are not covered by it. AGP 9.4 itself emits Gradle
configuration deprecation diagnostics; those upstream warnings are not suppressed.

## Contract, sessions, and transport

`foundation.lock.json` pins the mobile foundation release (currently 2.10.1, tag
`mobile-foundation-v2.10.1`) and its manifest
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
Referral and company-code links are recorded natively (below); Custom Tabs preserve website
behavior for email changes and unimplemented features. Claim links with supported dashboard or payment-method return destinations are claimed
in-app and open the requested native screen. Notifications admit only approved relative paths.

The manifest's `.AppLinks` alias carries the `autoVerify` HTTPS VIEW intent filter for the host of
`dadPublicOrigin`, on the paths DAD-Main's `apple-app-site-association` names (`/r/`, `/c/`, `/partner`,
`/partner/accept`, and the `/account/...` links); without an origin it is disabled. Verification also needs the
signing certificate's SHA-256 and the application ID in DAD-Main's `assetlinks.json`
(`DONOR_ANDROID_APP_CERT_SHA256`, `DONOR_ANDROID_APP_PACKAGE`); until then Android opens the links in the browser. The release build ships application ID `org.dad.foundation`; the debug
build uses `org.dad.foundation.debug` so both install side by side. Phone, email signup and
password sign-in are wired (see below). No permission is requested on startup, and no
staging/production service is contacted by the synthetic preview.

## Sign-in methods (Phase 1b, contract 2.4.0)

**Wired:** email signup (`postDonorSignup`: name, email, ZIP, city; the server emails a single-use link, which
the app claims when it opens or from a pasted link on "Check your email"), email + password sign-in
(`postDonorPasswordSignIn`, sends `device_name` and platform `ANDROID`), and set/change password in Settings
(`postDonorMePassword`: a 422 with code `CURRENT_PASSWORD_INCORRECT` is a wrong current password and keeps the
session; a real 401 refreshes once, then signs out). Passwords are masked, never logged, and cleared after send.

**Built but switched off until configured:** Google sign-in (`oidcSignIn`) and passkeys
(`beginDonorPasskeySignIn`/`finishDonorPasskeySignIn`). The server exchanges, models, UI states and stub-transport
tests exist; the screen says "Not available yet" because no platform provider is linked. Sign in with Apple is
not offered on Android. To turn them on you must supply:

- **Both:** the AndroidX Credential Manager dependency (`androidx.credentials`, plus
  `androidx.credentials:credentials-play-services-auth` for older devices), and an implementation of
  `GoogleIdTokenProvider` / `PasskeyAuthenticator` passed in `ExternalSignInDeps` (not yet written, a dependency decision).
- **Google:** a Google Cloud *web* OAuth client ID (`-PdadGoogleWebClientId=...apps.googleusercontent.com`), an Android
  OAuth client for the release signing certificate's SHA-1, and the Google ID library
  (`com.google.android.libraries.identity.googleid`). The same client ID must be accepted by DAD-Main's OIDC verifier.
- **Passkeys:** the relying-party domain (`-PdadPasskeyRpId=dad.example`) and that domain's
  `/.well-known/assetlinks.json` with the `delegate_permission/common.get_login_creds` relation for the app
  package and release certificate fingerprint.

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

## Giving (wiring phase 3, contract 2.6.0)

Starting a gift uses `startRecurringCheckout`; changing the amount, cancelling and the card update use
`changeAmount`, `cancelRecurring` and `beginPaymentUpdate`. Hosted Stripe pages open in a Custom Tab
(`androidx.browser`, already a dependency). The return page proves nothing: when the donor comes back the app
polls `GET /donor/me/recurring` (5 reads, 2 s apart) until a subscription is present. Each command holds one
Idempotency-Key, reused after a 429, 503 or lost response and retired on success, 403, 409 or 422. The giving
reads are parsed from raw JSON so an unlisted Stripe status (`paused`, `trialing`) never fails the response.
No App Link is registered for `/app/return`, so the donor closes the tab to return. Deleting an account with a
renewing gift (409) offers "Cancel my monthly gift" first. Debug stub extras: `stub_gift_active`,
`stub_gift_fail_first`, `stub_dashboard_slow`, `stub_open_gift`.

## Events (wiring phase 4, contract 2.7.0)

`WireEvents` merges `donorEvents` and `inviteOnlyEvents` (de-duplicated, soonest first) and drives detail,
reserve / waitlist, cancel, check-in and the authenticated calendar file. The reads are parsed from raw JSON so
an unlisted `type`, `reservation_status` or reserve result never fails the list. Times show in the event's own
`time_zone` with the zone named. Check-in is offered only for a held seat between start and end (no early grace)
and shows `checked_in_at`; the 66-character token shape is checked before anything is sent. "Scan QR code" asks for
the camera permission only when tapped, then shows a live CameraX viewfinder decoded by ML Kit's bundled
QR model (works offline; ZXing core remains for generating the invite QR). The first well-formed token checks in
once; other codes show a hint and scanning continues. Denied or unavailable camera falls back to typing the code,
which is always on screen. Live capture is device-only; tests use a fake scanner source. "Add to calendar" shares the `.ics`
through a FileProvider URI and the share sheet (no calendar permission). The contract defines no Idempotency-Key
for these commands. Debug stub extras: `stub_events_invited`, `stub_events_fail_first`, `stub_events_not_eligible`.

## Engagement (wiring phase 5, contract 2.7.0)

Real `WireEngagement` behind `EngagementService`, opened from the donor home ("See your impact"): lifetime impact
and rank from `dashboard`, the badge grid (`donorBadges`; earned / locked and the perk are stated in text, perk
gating follows the server codes F / 1K / TC, perk keys map to copy), the referral network (`donorNetwork`),
the invite (`sharing` + `conversions`) and the rankings choice (`profile` + `savePreferences`, explicit
`rankings_opted_out`: read the profile, save the whole block with its version, one re-read on a 409; the card flips
at once and reverts with an inline notice if the save fails). Today's initiative card opens the spend drill-in
(`initiativeSpend`, A-3, totals by category only).

- **Unknown is not zero.** Each section the server did not give shows "can't be shown right now" (or "—"); an
  unreadable section never becomes an empty grid, a zero or "not ranked". Sections are read concurrently and kept
  independently.
- **Rank label.** `national_percentile` is the top bucket as sent (2 reads "Top 2% nationally"), clamped to 1..100.
- **Invite links** are `<DAD_PUBLIC_ORIGIN>/r/<code>`; the in-person variant (and the QR, drawn locally from ZXing's
  matrix) is `/r/<code>?via=irl`. The code is parsed from the `sharing` link path (1-64 of `[A-Za-z0-9_-]`); the link is
  rebuilt from the configured origin, never taken from the server, and with no configured origin there is no link.
  A donor with no public name is prompted to choose one (`saveSharing`).
- **Raw JSON.** The engagement routes are decoded from raw JSON so `Conversion.kind` (an open string) and nullable
  members cannot be coerced by Gson. Statuses map as in `WireStatus.kt`.
- **Test stub.** `stub_backend` + `stub_signed_in` serves badges, network, sharing, conversions and a spend
  (`stub_open_engagement`, `stub_no_public_name`, `stub_rankings_fail_toggle`, `stub_badges_down`,
  `stub_network_down`, `stub_spend_unpublished`). `preview_impact` still shows the in-memory preview.

## Account (contract 2.9.0)

Settings carries the rest of the donor account: contact details (`saveProfile`: ZIP, city, phone), an email change
(`requestEmailChange`; the emailed `/account/email-change#token=` link opens the website
for the two-step confirmation, and completion revokes the app session), signed-in
devices (`sessions`, `revokeSession`, `revokeOtherSessions`, and `logoutAll` to sign out everywhere), the employer
programme (`employeeTeam`, `employeeEnroll` with a company code, `employeeJoinTeam`), and the referral code as a
Google Wallet pass (`walletProviders` / `googlePass`, offered only when the server says Google Wallet is configured).

## Partner portal (contract 2.9.0)

A partner signs in with an emailed link: "Partner" on the start screen asks for a work email
(`partnerRequestSignIn`); the `/partner/accept#token=` link, opened on the phone or pasted on "Check your email", is
exchanged for a 12-hour bearer (`partnerAcceptInvite`; a used or expired link is 422, a blocked partner 403). The
bearer is kept in its own Keystore entry (`partner-session`), cannot be refreshed, and is forgotten on expiry, on a
401, or on sign-out (local first, then `partnerSignOut`), returning the partner to sign-in. Becoming a partner is a
website application (`applyWithReceipt`, browser-only), linked from partner sign-in as `<public origin>/apply`.

The portal has four sections. **Overview** shows only verified figures (§7.3; an unverified figure is left out,
never estimated) and spend by category. **Initiatives** lists the partner's initiatives and their metrics.
**People** shows the employee-programme summary (suppressed below the privacy threshold) and team standings.
**Organization** edits the profile, workforce size and logo (PNG, picked with the system photo picker), shows the
company code with its sign-up QR and share link, manages users (admins invite by name, email and role and remove
others after confirmation; viewers are read-only), and lets admins export a PDF impact report or re-download a
retained one (an aged-out report is a 404, "no longer available"). Reports are shared through the FileProvider and
capped at 16 MiB. The `stub_backend` transport serves the same routes (`stub_partner_signed_in` starts with a stored
partner session; links whose token starts `goodpartner` are accepted).

## Analytics and push (contract 2.9.0)

**Analytics are opt-in**, worded and counted as on the website. The start screen asks once ("Allow usage
analytics?") and Settings has a "Usage analytics" switch; until the donor allows it nothing is recorded. Events go
only to DAD-Main's `ingestAnalytics` (first party, `X-DAD-Platform: ANDROID`, attributed to the donor when signed
in) in batches of up to 100 with a stable id each, so a retried batch is not double-counted; declining drops
anything still buffered. The app emits `app_opened`, `role_selected`, `invite_shared` (`share_sheet` / `copy`),
`irl_code_shown` and `push_opened`; every other event is derived from the successful API call it stands for by
the same rules as the website's `observeAPI` (`AnalyticsObserver`), e.g. `signup_started` for `POST /donor/signup`.
Declare this in the Play Data safety form as app interactions collected for analytics, optional, not shared.

**Push** registers the donor (`registerPushDevice`, at every launch and sign-in; the server drops the token when the
session ends) and the partner (`partnerRegisterPushDevice`, unregistered on sign-out). A tapped notification's
`category` is counted as `push_opened` and its `path` opens the matching screen (`/account/gifts` gift history,
`/account/payment-method` the gift screen, `/account/profile` Settings), after sign-in if need be. **Owner
dependency:** no push provider is linked, so there is no token and nothing is registered. Turning push on needs a
Firebase project for the app ID, its `google-services` configuration, the Firebase Messaging dependency (a
dependency decision), an implementation of `PushTokenProvider`, the `POST_NOTIFICATIONS` runtime permission on
Android 13+, and DAD-Main's FCM credentials. `stub_push` exercises the rest with a fixed token.

**Referral and company-code links (contract 2.10).** An invite (`/r/<code>`, `?via=irl` in person) or company
(`/c/<code>`) link that opens the app is recorded with DAD-Main (`captureReferral` / `captureCompany`), and the visitor
token it returns is kept where the website keeps its cookie (`LinkAttribution`, SharedPreferences; the latest link
wins, and a visit that keeps nothing discards it). The referral token goes with the first monthly gift
(`startRecurringCheckout`'s `referral_token`, part of the command's idempotency fingerprint), so the referrer is credited
as on the web; the company token is bound once the donor is signed in (`bindCompanyTouch`). Before sign-in an invite
names the referrer on the start screen. Account deletion forgets both.
