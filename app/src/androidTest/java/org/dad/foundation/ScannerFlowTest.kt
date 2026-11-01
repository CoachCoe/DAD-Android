package org.dad.foundation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.rememberUpdatedState

/**
 * Event check-in by QR through the real screens, with a FAKE scanner source and permission (no camera in the
 * emulator): a valid scan checks in once, an invalid code shows a hint and keeps scanning, a denied permission falls
 * back to typed entry. The live CameraX + ML Kit capture itself is device-only.
 */
class ScannerFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Stands in for the camera: the test pushes decoded strings through [emit], as the analyzer would. */
    private class FakeSource : QrScannerSource {
        @Volatile var sink: ((String) -> Unit)? = null
        @Volatile var composed = false
        override @Composable fun Viewfinder(modifier: Modifier, torchOn: Boolean, onDecoded: (String) -> Unit, onStatus: (ScannerStatus) -> Unit) {
            val latest = rememberUpdatedState(onDecoded)
            LaunchedEffect(Unit) {
                composed = true
                sink = { text -> Handler(Looper.getMainLooper()).post { latest.value(text) } }
                onStatus(ScannerStatus.Ready(hasTorch = true))
            }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { composed = false; sink = null } }
            Box(modifier.background(Color.DarkGray).testTag("fake-camera")) { Box(Modifier.fillMaxSize()) }
        }
        fun emit(text: String) = checkNotNull(sink) { "the viewfinder is not showing" }(text)
    }

    /** A permission request that answers [granted] at once, with no system dialog. */
    private class ImmediateAnswer(private val granted: Boolean) : ActivityResultContract<String, Boolean>() {
        var requests = 0
        override fun createIntent(context: Context, input: String) = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?) = granted
        override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Boolean> {
            requests++
            return SynchronousResult(granted)
        }
    }

    private val fake = FakeSource()

    @After fun restoreEnvironment() { ScannerEnvironments.current = ScannerEnvironment() }

    private fun launchLiveEvent(env: ScannerEnvironment): ActivityScenario<MainActivity> {
        ScannerEnvironments.current = env
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        DebugBackend.resetRequestLog()
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        waitFor("open-events")
        compose.waitUntil(20_000) { !exists("dashboard-loading") && !exists("initiative-loading") }
        compose.waitForIdle()
        compose.tapSafely("open-events")
        waitFor("events-home")
        waitFor("open-event-${StubEvents.LIVE}")
        compose.tapSafely("open-event-${StubEvents.LIVE}")
        waitFor("checkin-scan")
        return scenario
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { exists(tag) }
    private fun checkIns() = DebugBackend.requestLog.count { it == "POST /donor/me/events/check-in" }

    private fun granted() = ScannerEnvironment(source = fake, isGranted = { true })

    @Test fun aValidScanChecksInExactlyOnceEvenWhenFramesRepeat() {
        launchLiveEvent(granted()).use {
            compose.tapSafely("checkin-scan")
            waitFor("scanner-viewfinder")
            compose.waitUntil(10_000) { fake.sink != null }
            repeat(5) { fake.emit(StubEvents.TOKEN) }
            waitFor("checkedin-${StubEvents.LIVE}")
            assertEquals(1, checkIns())
            assertTrue("the camera is released once a code is read", !exists("scanner-panel"))
            assertTrue(!fake.composed)
        }
    }

    @Test fun anInvalidCodeShowsAHintAndScanningContinues() {
        launchLiveEvent(granted()).use {
            compose.tapSafely("checkin-scan")
            compose.waitUntil(10_000) { fake.sink != null }
            fake.emit("https://example.org/not-a-checkin")
            waitFor("scan-hint")
            compose.onNodeWithTag("scan-hint").assertTextContains("isn’t an event check-in code", substring = true)
            assertEquals(0, checkIns())
            assertTrue(exists("scanner-panel"))
            fake.emit(StubEvents.TOKEN)
            waitFor("checkedin-${StubEvents.LIVE}")
            assertEquals(1, checkIns())
        }
    }

    @Test fun typeTheCodeInsteadClosesTheScannerAndTypedEntryStillWorks() {
        launchLiveEvent(granted()).use {
            compose.tapSafely("checkin-scan")
            waitFor("scan-type-instead")
            compose.onNodeWithTag("scan-type-instead").assertIsDisplayed()
            compose.tapSafely("scan-type-instead")
            compose.waitUntil(10_000) { !exists("scanner-panel") }
            assertTrue(!fake.composed)
            compose.enterText("checkin-code", StubEvents.TOKEN)
            compose.tapSafely("checkin-submit")
            waitFor("checkedin-${StubEvents.LIVE}")
            assertEquals(1, checkIns())
        }
    }

    @Test fun aDeniedPermissionExplainsAndTypedEntryStillWorks() {
        val answer = ImmediateAnswer(granted = false)
        launchLiveEvent(ScannerEnvironment(source = fake, isGranted = { false }, permissionContract = answer, canAskAgain = { true })).use {
            compose.tapSafely("checkin-scan")
            waitFor("scan-denied")
            assertEquals(1, answer.requests)
            assertTrue(!exists("scanner-panel") && !exists("scan-open-settings"))
            compose.enterText("checkin-code", StubEvents.TOKEN)
            compose.tapSafely("checkin-submit")
            waitFor("checkedin-${StubEvents.LIVE}")
            assertEquals(1, checkIns())
        }
    }

    @Test fun aPermanentlyDeniedPermissionOffersSettingsAndTypedEntry() {
        val answer = ImmediateAnswer(granted = false)
        launchLiveEvent(ScannerEnvironment(source = fake, isGranted = { false }, permissionContract = answer, canAskAgain = { false })).use {
            compose.tapSafely("checkin-scan")
            waitFor("scan-denied-permanent")
            waitFor("scan-open-settings")
            compose.onNodeWithTag("scan-open-settings").assertIsDisplayed()
            compose.enterText("checkin-code", StubEvents.TOKEN)
            compose.tapSafely("checkin-submit")
            waitFor("checkedin-${StubEvents.LIVE}")
        }
    }

    @Test fun grantingThePermissionOpensTheViewfinder() {
        val answer = ImmediateAnswer(granted = true)
        launchLiveEvent(ScannerEnvironment(source = fake, isGranted = { false }, permissionContract = answer)).use {
            compose.tapSafely("checkin-scan")
            waitFor("scanner-viewfinder")
            assertEquals(1, answer.requests)
        }
    }

    @Test fun anUnavailableCameraSaysSoAndKeepsTypedEntry() {
        val broken = object : QrScannerSource {
            override @Composable fun Viewfinder(modifier: Modifier, torchOn: Boolean, onDecoded: (String) -> Unit, onStatus: (ScannerStatus) -> Unit) {
                LaunchedEffect(Unit) { onStatus(ScannerStatus.Unavailable) }
            }
        }
        launchLiveEvent(ScannerEnvironment(source = broken, isGranted = { true })).use {
            compose.tapSafely("checkin-scan")
            waitFor("scan-unavailable")
            waitFor("scan-type-instead")
            compose.tapSafely("scan-type-instead")
            compose.enterText("checkin-code", StubEvents.TOKEN)
            compose.tapSafely("checkin-submit")
            waitFor("checkedin-${StubEvents.LIVE}")
        }
    }
}
